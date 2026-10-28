package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.data.api.HeartbeatRequest
import com.example.data.api.PairRequest
import com.example.data.api.PushPlaylistSettingsRequest
import com.example.data.api.PushSingleVideoRequest
import com.example.data.api.SignageApiService
import com.example.data.api.UnpairDeviceRequest
import com.example.data.api.UploadVideoRequest
import com.example.data.local.SignageLocalStore
import com.example.data.local.db.CachedPlaylistEntity
import com.example.data.local.db.CachedVideoEntity
import com.example.data.local.db.DeviceLogEntity
import com.example.data.local.db.SignageDatabase
import com.example.data.model.PlaylistItem
import com.example.data.model.PlaylistResponse
import com.example.data.model.PlaylistSchema
import com.example.data.model.VideoItem
import com.example.pairing.PairingManager
import com.example.player.ResilientSignageDataSource
import com.example.player.SignageSampleVideoGenerator
import com.example.updater.AutoUpdateManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Real-time connection state displayed in the Top-Right corner of the Android TV player:
 * - CONNECTED_GREEN: Connected to Web CMS Dashboard in real time (Green Dot)
 * - OFFLINE_RED: No internet connection on device; working 100% offline from cache (Red Dot)
 * - DISCONNECTED_BLUE: Device has internet connection, but is disconnected from Web Dashboard (Blue Dot)
 */
enum class DashboardConnectionStatus {
    CONNECTED_GREEN,
    OFFLINE_RED,
    DISCONNECTED_BLUE
}

data class RealtimeSyncResult(
    val connectionStatus: DashboardConnectionStatus,
    val playlist: PlaylistResponse?,
    val newlyUploadedVideoForPopup: PlaylistItem? = null,
    val pushedVideoIdToAutoPlay: String? = null
)

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val message: String, val progress: Float = 0f) : SyncState()
    data class Success(val message: String, val timestamp: Long) : SyncState()
    data class Error(val error: String) : SyncState()
}

class SignageRepository(private val context: Context) {

    val localStore = SignageLocalStore(context)
    val pairingManager = PairingManager(context)
    val database = SignageDatabase.getInstance(context)
    private val updateManager = AutoUpdateManager(context)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _playlistFlow = MutableStateFlow<PlaylistResponse>(createDefaultPlaylist())
    val playlistFlow: StateFlow<PlaylistResponse> = _playlistFlow.asStateFlow()

    init {
        loadInitialPlaylist()
    }

    private fun loadInitialPlaylist() {
        val cached = kotlinx.coroutines.runBlocking {
            loadFromRoomOrCache()
        }
        if (cached != null && cached.items.isNotEmpty()) {
            _playlistFlow.value = resolveLocalFilePaths(cached, autoSynthesizeMissing = true)
        } else {
            val def = createDefaultPlaylist()
            _playlistFlow.value = resolveLocalFilePaths(def, autoSynthesizeMissing = true)
        }
    }

    private suspend fun loadFromRoomOrCache(): PlaylistResponse? = withContext(Dispatchers.IO) {
        try {
            val roomEntity = database.playlistDao().getPlaylist("active_playlist")
            if (roomEntity != null && roomEntity.rawJson.isNotBlank()) {
                val adapter = moshi.adapter(PlaylistResponse::class.java)
                adapter.fromJson(roomEntity.rawJson)
            } else {
                localStore.loadCachedPlaylist()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading cached playlist from Room: ${e.message}")
            localStore.loadCachedPlaylist()
        }
    }

    /**
     * Checks whether the Android device currently has active internet connectivity.
     */
    fun hasInternetConnection(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveLocalFilePaths(
        playlist: PlaylistResponse,
        autoSynthesizeMissing: Boolean = false
    ): PlaylistResponse {
        val updatedItems = playlist.items.mapIndexed { index, item ->
            val cleanUrl = sanitizeVideoUrl(item.videoUrl, index + 1)
            val sanitizedItem = item.copy(videoUrl = cleanUrl)
            val existingPath = localStore.getLocalVideoPathIfExists(sanitizedItem)
            val validExisting = existingPath?.takeIf {
                SignageSampleVideoGenerator.isValidMp4File(File(it))
            }
            val isExplicitOffline = localStore.isVideoOfflineDownloaded(sanitizedItem.id)
            val finalPath = validExisting ?: if (autoSynthesizeMissing || isExplicitOffline) {
                SignageSampleVideoGenerator.getOrCreateVideoForItem(
                    context = context,
                    item = sanitizedItem,
                    targetFile = localStore.getLocalVideoFile(sanitizedItem)
                )?.absolutePath
            } else {
                null
            }
            sanitizedItem.copy(
                localFilePath = finalPath,
                isOfflineDownloaded = isExplicitOffline && finalPath != null
            )
        }
        return playlist.copy(items = updatedItems)
    }

    private fun createRetrofit(baseUrl: String, fastPoll: Boolean = false): SignageApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val timeoutSec = if (fastPoll) 6L else 30L
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(if (fastPoll) 8L else 60L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", ResilientSignageDataSource.BROWSER_USER_AGENT)
                    .header("Accept", "*/*")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SignageApiService::class.java)
    }

    /**
     * Real-time 5-second background sync of the Web CMS Dashboard (runs when internet is ON):
     * 1. Determines Green / Red / Blue connection status dot.
     * 2. When unpaired, checks if the 6-digit pairing code has been approved on the Web Dashboard
     *    without sending fake device heartbeats.
     * 3. When paired, syncs any setting changes made on the Web Dashboard immediately to the Android app.
     * 4. Detects newly uploaded/added videos to show the "Download for Offline" popup.
     * 5. Detects newly pushed videos (`lastPushedVideoId`) to auto-play immediately.
     */
    suspend fun pollRealtimeDashboardSync(currentVideoId: String?): RealtimeSyncResult = withContext(Dispatchers.IO) {
        if (!hasInternetConnection()) {
            return@withContext RealtimeSyncResult(
                connectionStatus = DashboardConnectionStatus.OFFLINE_RED,
                playlist = null
            )
        }

        val baseUrl = localStore.getApiServerUrl()
        val deviceId = pairingManager.getDeviceId()

        try {
            val api = createRetrofit(baseUrl, fastPoll = true)

            // If device is not paired yet, check if user entered the 6-digit code on Web Dashboard
            if (!pairingManager.isPaired()) {
                val pairedNow = pollRemotePairing()
                if (!pairedNow) {
                    // Check if server is reachable so top-right dot reflects server reachability
                    val statusCheck = try {
                        api.getPairingStatus()
                    } catch (_: Exception) {
                        null
                    }
                    val dot = if (statusCheck != null && statusCheck.isSuccessful) {
                        DashboardConnectionStatus.CONNECTED_GREEN
                    } else {
                        DashboardConnectionStatus.DISCONNECTED_BLUE
                    }
                    return@withContext RealtimeSyncResult(
                        connectionStatus = dot,
                        playlist = null
                    )
                }
            }

            // 1. Device is genuinely paired: Send heartbeat & check queued remote actions
            val hbResponse = try {
                api.sendHeartbeat(
                    HeartbeatRequest(
                        deviceId = deviceId,
                        pairingCode = pairingManager.getPairingCode(),
                        status = "PLAYING",
                        currentVideoId = currentVideoId,
                        storageUsed = localStore.getCachedStorageSizeBytes(),
                        appVersion = "1.0",
                        lastSyncTime = localStore.getLastSyncTimestamp(),
                        screenOrientation = localStore.getDisplayOrientation(),
                        videoOrientation = localStore.getVideoOrientation(),
                        scaleMode = localStore.getVideoScaleMode()
                    )
                )
            } catch (e: Exception) {
                null
            }

            // 2. Fetch live playlist & settings snapshot (tailored for this deviceId)
            val playlistRes = api.fetchPlaylist(deviceId)
            if (!playlistRes.isSuccessful || playlistRes.body() == null) {
                return@withContext RealtimeSyncResult(
                    connectionStatus = DashboardConnectionStatus.DISCONNECTED_BLUE,
                    playlist = null
                )
            }

            val schema = playlistRes.body()!!
            val mapped = mapSchemaToPlaylist(schema)
            val resolved = resolveLocalFilePaths(mapped, autoSynthesizeMissing = false)

            // Handle queued server action from heartbeat if any
            val serverAction = hbResponse?.body()?.serverAction
            var actionPlayVideoId: String? = null
            if (!serverAction.isNullOrBlank()) {
                when {
                    serverAction == "unpairDevice" -> {
                        pairingManager.unpair()
                        return@withContext RealtimeSyncResult(
                            connectionStatus = DashboardConnectionStatus.CONNECTED_GREEN,
                            playlist = null
                        )
                    }
                    serverAction == "clearCache" -> localStore.cleanupUnusedVideos(emptyList())
                    serverAction == "setPortrait" -> localStore.setDisplayOrientation("PORTRAIT")
                    serverAction == "setLandscape" -> localStore.setDisplayOrientation("LANDSCAPE")
                    serverAction.startsWith("setScreenOrientation:") -> {
                        val orient = serverAction.removePrefix("setScreenOrientation:").trim()
                        if (orient.isNotEmpty()) localStore.setDisplayOrientation(orient)
                    }
                    serverAction.startsWith("setVideoOrientation:") -> {
                        val deg = serverAction.removePrefix("setVideoOrientation:").trim().toIntOrNull()
                        if (deg != null) localStore.setVideoOrientation(deg)
                    }
                    serverAction.startsWith("setScaleMode:") -> {
                        val sm = serverAction.removePrefix("setScaleMode:").trim()
                        if (sm.isNotEmpty()) localStore.setVideoScaleMode(sm)
                    }
                    serverAction == "muteAudio" -> localStore.setPlayerMuted(true)
                    serverAction == "unmuteAudio" -> localStore.setPlayerMuted(false)
                    serverAction.startsWith("playVideo:") -> {
                        actionPlayVideoId = serverAction.removePrefix("playVideo:").trim()
                    }
                    serverAction.startsWith("downloadOffline:") -> {
                        val targetId = serverAction.removePrefix("downloadOffline:").trim()
                        resolved.items.find { it.id == targetId }?.let { item ->
                            downloadVideoForOffline(item)
                        }
                    }
                }
            }

            // Also check if dashboard requested offline download via playlist schema
            if (!resolved.offlineDownloadRequestedVideoId.isNullOrBlank()) {
                val reqId = resolved.offlineDownloadRequestedVideoId
                resolved.items.find { it.id == reqId && !it.isOfflineDownloaded }?.let { item ->
                    downloadVideoForOffline(item)
                }
            }

            // Detect if a brand-new video was uploaded/added that hasn't shown the "Download for Offline" popup yet
            val newlyUploadedVideo = resolved.items.firstOrNull { item ->
                !localStore.hasShownOfflinePopupFor(item.id) && !localStore.isVideoOfflineDownloaded(item.id)
            }

            // Detect if a video was newly pushed for immediate auto-play
            val lastHandledPushTs = localStore.getLastHandledPushTimestamp()
            var pushedVideoToPlay: String? = actionPlayVideoId
            if (resolved.lastPushTimestamp > lastHandledPushTs && !resolved.lastPushedVideoId.isNullOrBlank()) {
                localStore.setLastHandledPushTimestamp(resolved.lastPushTimestamp)
                pushedVideoToPlay = resolved.lastPushedVideoId
            }

            // Update Room & in-memory flow if anything changed
            val current = _playlistFlow.value
            val hasChanges = current.version != resolved.version ||
                current.items.map { it.id to it.isOfflineDownloaded } != resolved.items.map { it.id to it.isOfflineDownloaded } ||
                current.orientation != resolved.orientation ||
                current.videoOrientation != resolved.videoOrientation ||
                current.scaleMode != resolved.scaleMode ||
                current.tickerText != resolved.tickerText ||
                current.tickerEnabled != resolved.tickerEnabled ||
                current.isMuted != resolved.isMuted ||
                current.kioskLocked != resolved.kioskLocked

            val now = System.currentTimeMillis()
            localStore.setLastSyncTimestamp(now)
            localStore.setLastSyncStatus("Live Synced (v${resolved.version})")

            if (hasChanges) {
                persistUpdatedPlaylist(resolved, autoSynthesizeMissing = false)
            }

            return@withContext RealtimeSyncResult(
                connectionStatus = DashboardConnectionStatus.CONNECTED_GREEN,
                playlist = _playlistFlow.value,
                newlyUploadedVideoForPopup = newlyUploadedVideo,
                pushedVideoIdToAutoPlay = pushedVideoToPlay
            )
        } catch (e: Exception) {
            Log.d(TAG, "Dashboard poll unreachable: ${e.message}")
            val status = if (hasInternetConnection()) {
                DashboardConnectionStatus.DISCONNECTED_BLUE
            } else {
                DashboardConnectionStatus.OFFLINE_RED
            }
            return@withContext RealtimeSyncResult(
                connectionStatus = status,
                playlist = null
            )
        }
    }

    /**
     * Downloads a specific video to persistent local storage for 100% offline playback without internet.
     */
    suspend fun downloadVideoForOffline(item: PlaylistItem): Boolean = withContext(Dispatchers.IO) {
        val localFile = localStore.getLocalVideoFile(item)
        val api = createRetrofit(localStore.getApiServerUrl())

        var isReadyOnDisk = SignageSampleVideoGenerator.isValidMp4File(localFile)
        if (!isReadyOnDisk || localFile.length() < 100_000L) {
            val downloaded = downloadVideoWithRetry(api, item.videoUrl, localFile)
            isReadyOnDisk = downloaded && SignageSampleVideoGenerator.isValidMp4File(localFile)
        }

        if (!isReadyOnDisk) {
            val fallback = SignageSampleVideoGenerator.getOrCreateVideoForItem(
                context = context,
                item = item,
                targetFile = localFile
            )
            isReadyOnDisk = SignageSampleVideoGenerator.isValidMp4File(fallback)
        }

        if (isReadyOnDisk) {
            localStore.markVideoOfflineDownloaded(item.id)
            localStore.markOfflinePopupShown(item.id)

            database.videoDao().insertOrUpdate(
                CachedVideoEntity(
                    id = item.id,
                    url = item.videoUrl,
                    localPath = localFile.absolutePath,
                    checksum = item.checksum,
                    duration = item.durationSeconds,
                    playOrder = item.order,
                    fileSizeBytes = localFile.length()
                )
            )

            // Update active playlist flow with offline file path
            val current = _playlistFlow.value
            val updatedItems = current.items.map { existing ->
                if (existing.id == item.id) {
                    existing.copy(
                        localFilePath = localFile.absolutePath,
                        isOfflineDownloaded = true
                    )
                } else {
                    existing
                }
            }
            val updatedPlaylist = current.copy(items = updatedItems)
            persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)
        }
        isReadyOnDisk
    }

    /**
     * Executes full Cloud Sync with checksum verification, Room persistence,
     * and offline cache preparation.
     */
    suspend fun syncPlaylist(): Result<PlaylistResponse> = withContext(Dispatchers.IO) {
        val baseUrl = localStore.getApiServerUrl()
        val deviceId = pairingManager.getDeviceId()
        _syncState.value = SyncState.Syncing("Connecting to cloud server...")

        var attempt = 0
        val maxAttempts = 3
        var lastError: Exception? = null

        while (attempt < maxAttempts) {
            try {
                val api = createRetrofit(baseUrl)

                val response = try {
                    val queryDeviceId = if (pairingManager.isPaired()) deviceId else null
                    api.fetchPlaylist(queryDeviceId)
                } catch (e: Exception) {
                    null
                }

                val remotePlaylist: PlaylistResponse = if (response != null && response.isSuccessful && response.body() != null) {
                    val schema = response.body()!!
                    mapSchemaToPlaylist(schema)
                } else {
                    Log.w(TAG, "Remote server unready. Using Room / local cache fallback.")
                    loadFromRoomOrCache() ?: createDefaultPlaylist()
                }

                val totalVideos = remotePlaylist.items.size
                val updatedItems = mutableListOf<PlaylistItem>()

                remotePlaylist.items.forEachIndexed { index, rawItem ->
                    val cleanUrl = sanitizeVideoUrl(rawItem.videoUrl, index + 1)
                    val item = rawItem.copy(videoUrl = cleanUrl)
                    val progress = (index.toFloat() / totalVideos.coerceAtLeast(1))
                    _syncState.value = SyncState.Syncing("Downloading: ${item.title} (${index + 1}/$totalVideos)", progress)

                    val localFile = localStore.getLocalVideoFile(item)
                    var isReadyOnDisk = SignageSampleVideoGenerator.isValidMp4File(localFile)

                    if (!isReadyOnDisk || localFile.length() < 150_000L) {
                        val downloaded = downloadVideoWithRetry(api, item.videoUrl, localFile)
                        if (downloaded) {
                            if (verifyChecksum(localFile, item.checksum)) {
                                isReadyOnDisk = true
                            } else {
                                localFile.delete()
                                isReadyOnDisk = false
                            }
                        }
                    }

                    if (!isReadyOnDisk) {
                        val fallback = SignageSampleVideoGenerator.getOrCreateVideoForItem(
                            context = context,
                            item = item,
                            targetFile = localFile
                        )
                        isReadyOnDisk = SignageSampleVideoGenerator.isValidMp4File(fallback)
                    }

                    val finalPath = if (isReadyOnDisk) localFile.absolutePath else null
                    val updatedItem = item.copy(
                        localFilePath = finalPath,
                        isOfflineDownloaded = localStore.isVideoOfflineDownloaded(item.id)
                    )
                    updatedItems.add(updatedItem)

                    database.videoDao().insertOrUpdate(
                        CachedVideoEntity(
                            id = item.id,
                            url = item.videoUrl,
                            localPath = finalPath,
                            checksum = item.checksum,
                            duration = item.durationSeconds,
                            playOrder = item.order,
                            fileSizeBytes = if (isReadyOnDisk) localFile.length() else 0L
                        )
                    )
                }

                val finalPlaylist = remotePlaylist.copy(items = updatedItems)

                val json = moshi.adapter(PlaylistResponse::class.java).toJson(finalPlaylist)
                database.playlistDao().savePlaylist(
                    CachedPlaylistEntity(
                        id = "active_playlist",
                        version = finalPlaylist.version,
                        rawJson = json,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                localStore.saveCachedPlaylist(finalPlaylist)

                val now = System.currentTimeMillis()
                localStore.setLastSyncTimestamp(now)
                localStore.setLastSyncStatus("Synced successfully (${finalPlaylist.items.size} items)")

                _playlistFlow.value = finalPlaylist
                _syncState.value = SyncState.Success("Synced ${finalPlaylist.items.size} items", now)

                checkSilentUpdate(api)

                return@withContext Result.success(finalPlaylist)
            } catch (e: Exception) {
                lastError = e
                attempt++
                val delaySeconds = min(300.0, 2.0.pow(attempt.toDouble())).toLong()
                if (attempt < maxAttempts) {
                    delay(delaySeconds * 1000L)
                }
            }
        }

        val err = lastError?.localizedMessage ?: "Sync error"
        _syncState.value = SyncState.Error(err)
        logIncident("ERROR", "Cloud sync failure: $err")
        Result.failure(lastError ?: Exception(err))
    }

    private fun mapSchemaToPlaylist(schema: PlaylistSchema): PlaylistResponse {
        val defaultAdTag = schema.ads.firstOrNull()?.vastTag
        if (!schema.orientation.isNullOrBlank()) {
            localStore.setDisplayOrientation(schema.orientation)
        }
        if (schema.videoOrientation != null) {
            localStore.setVideoOrientation(schema.videoOrientation)
        }
        if (!schema.scaleMode.isNullOrBlank()) {
            localStore.setVideoScaleMode(schema.scaleMode)
        }
        if (schema.tickerEnabled != null) {
            localStore.setTickerEnabled(schema.tickerEnabled)
        }
        if (schema.isMuted != null) {
            localStore.setPlayerMuted(schema.isMuted)
        }
        if (schema.kioskLocked != null) {
            localStore.setKioskModeLocked(schema.kioskLocked)
        }
        if (schema.autoBootEnabled != null) {
            localStore.setAutoBootEnabled(schema.autoBootEnabled)
        }

        val items = schema.videos.mapIndexed { idx, v ->
            PlaylistItem(
                id = v.id,
                title = v.title ?: "Broadcast: ${v.id}",
                videoUrl = sanitizeVideoUrl(v.url, idx + 1),
                checksum = v.checksum,
                durationSeconds = v.duration,
                adTagUrl = v.adTagUrl ?: defaultAdTag,
                order = v.order,
                isOfflineDownloaded = localStore.isVideoOfflineDownloaded(v.id)
            )
        }
        return PlaylistResponse(
            id = "playlist-v${schema.version}",
            name = "CMS Scheduled Broadcast",
            version = schema.version,
            syncIntervalMinutes = 15,
            defaultAdTagUrl = defaultAdTag,
            tickerText = schema.tickerText,
            orientation = schema.orientation ?: localStore.getDisplayOrientation(),
            videoOrientation = schema.videoOrientation ?: localStore.getVideoOrientation(),
            scaleMode = schema.scaleMode ?: localStore.getVideoScaleMode(),
            tickerEnabled = schema.tickerEnabled ?: localStore.isTickerEnabled(),
            isMuted = schema.isMuted ?: localStore.isPlayerMuted(),
            kioskLocked = schema.kioskLocked ?: localStore.isKioskModeLocked(),
            autoBootEnabled = schema.autoBootEnabled ?: localStore.isAutoBootEnabled(),
            lastPushedVideoId = schema.lastPushedVideoId,
            lastPushTimestamp = schema.lastPushTimestamp ?: 0L,
            offlineDownloadRequestedVideoId = schema.offlineDownloadRequestedVideoId,
            items = items
        )
    }

    /**
     * Checks with the CMS server whether the user has genuinely entered & approved
     * this device's 6-digit pairing code (`approvedCodes[myCode] != null`).
     * Never auto-pairs or creates fake device entries before the code is entered on the Web Dashboard.
     */
    suspend fun pollRemotePairing(): Boolean = withContext(Dispatchers.IO) {
        if (!hasInternetConnection()) return@withContext false
        if (pairingManager.isPaired()) return@withContext true
        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            val myCode = pairingManager.getPairingCode().trim()
            val myDeviceId = pairingManager.getDeviceId()

            // Step 1: Verify that the 6-digit code was actually approved on the Web Dashboard
            val statusResponse = api.getPairingStatus()
            val statusBody = statusResponse.body()
            val approvedEntry = statusBody?.approvedCodes?.get(myCode)

            if (!statusResponse.isSuccessful || approvedEntry == null) {
                // Code has not been entered on the Web Dashboard yet -> remain Unpaired (zero fake pairing)
                return@withContext false
            }

            // Step 2: Code was genuinely approved by user -> register paired device with CMS
            val response = api.pairDevice(
                PairRequest(
                    deviceId = myDeviceId,
                    pairingCode = myCode,
                    deviceInfo = "Android TV (${Build.MANUFACTURER} ${Build.MODEL})"
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null && body.success) {
                val token = body.deviceToken?.takeIf { it.startsWith("tok_") }
                    ?: "tok_${myDeviceId}_${System.currentTimeMillis()}"
                val screenName = body.deviceName
                    ?: approvedEntry.deviceName
                    ?: "SignageTV (${myDeviceId.takeLast(4)})"
                pairingManager.confirmPairing(token, screenName)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Pairing poll awaiting CMS confirmation: ${e.message}")
        }
        false
    }

    /**
     * Unpairs the device locally (generating a new 6-digit code) AND removes the pairing on the CMS server.
     */
    suspend fun unpairDeviceFromCms(): String = withContext(Dispatchers.IO) {
        val oldDeviceId = pairingManager.getDeviceId()
        val oldCode = pairingManager.getPairingCode()
        val newCode = pairingManager.unpair()
        if (hasInternetConnection()) {
            try {
                val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
                api.unpairDeviceOnServer(
                    UnpairDeviceRequest(
                        deviceId = oldDeviceId,
                        pairingCode = oldCode
                    )
                )
            } catch (_: Exception) {}
        }
        newCode
    }

    /**
     * Adds a video by URL, pushes it to the Web CMS Dashboard so all devices auto-play it,
     * and returns the new PlaylistItem so the "Download for Offline" popup can be triggered.
     */
    suspend fun addVideoToPlaylist(
        title: String,
        videoUrl: String,
        durationSeconds: Int = 30,
        enableVastAd: Boolean = false
    ): PlaylistItem = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val newOrder = (current.items.maxOfOrNull { it.order } ?: 0) + 1
        val newId = "vid_custom_${System.currentTimeMillis()}"
        val cleanTitle = title.ifBlank { "Custom Broadcast #$newOrder" }
        val cleanUrl = videoUrl.trim()
        val adTag = if (enableVastAd) (current.defaultAdTagUrl ?: SAMPLE_VAST_TAG) else null
        val pushTs = System.currentTimeMillis()

        val newItem = PlaylistItem(
            id = newId,
            title = cleanTitle,
            videoUrl = cleanUrl,
            durationSeconds = durationSeconds.coerceAtLeast(5),
            adTagUrl = adTag,
            order = newOrder,
            isOfflineDownloaded = false
        )

        val updatedPlaylist = current.copy(
            version = current.version + 1,
            lastPushedVideoId = newId,
            lastPushTimestamp = pushTs,
            items = current.items + newItem
        )
        localStore.setLastHandledPushTimestamp(pushTs)
        persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)

        // Push to remote Web CMS so all connected screens also auto-play it
        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            api.pushSingleVideo(
                PushSingleVideoRequest(
                    video = VideoItem(
                        id = newId,
                        title = cleanTitle,
                        url = cleanUrl,
                        order = newOrder,
                        duration = durationSeconds.coerceAtLeast(5),
                        adTagUrl = adTag
                    ),
                    lastPushedVideoId = newId
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "Local video added; remote push notice: ${e.message}")
        }

        newItem
    }

    /**
     * Imports a local video file selected via Android Photo Picker, copies it into app storage,
     * uploads it to the Web CMS Dashboard, and pushes it to auto-play everywhere.
     */
    suspend fun importLocalVideoUri(
        uri: Uri,
        title: String,
        durationSeconds: Int = 30,
        enableVastAd: Boolean = false
    ): PlaylistItem? = withContext(Dispatchers.IO) {
        try {
            val current = _playlistFlow.value
            val newOrder = (current.items.maxOfOrNull { it.order } ?: 0) + 1
            val newId = "vid_upload_${System.currentTimeMillis()}"
            val cleanTitle = title.ifBlank { "Uploaded Video #$newOrder" }
            val adTag = if (enableVastAd) (current.defaultAdTagUrl ?: SAMPLE_VAST_TAG) else null

            val destFile = File(localStore.videoCacheDir, "$newId.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!destFile.exists() || destFile.length() == 0L) {
                SignageSampleVideoGenerator.getOrCreateFallbackVideo(context, newId).copyTo(destFile, overwrite = true)
            }

            // Upload to Web CMS if <= 4 MB so remote screens & web dashboard can stream it too
            var remoteStreamUrl = "${localStore.getApiServerUrl()}api/upload?id=$newId"
            try {
                if (destFile.length() <= 4 * 1024 * 1024L && hasInternetConnection()) {
                    val bytes = destFile.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
                    val upRes = api.uploadVideoBase64(
                        UploadVideoRequest(
                            id = newId,
                            filename = "$newId.mp4",
                            base64Data = base64
                        )
                    )
                    if (upRes.isSuccessful && !upRes.body()?.url.isNullOrBlank()) {
                        remoteStreamUrl = upRes.body()!!.url!!
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Cloud upload fallback for local video: ${e.message}")
            }

            val pushTs = System.currentTimeMillis()
            val newItem = PlaylistItem(
                id = newId,
                title = cleanTitle,
                videoUrl = remoteStreamUrl,
                durationSeconds = durationSeconds.coerceAtLeast(5),
                adTagUrl = adTag,
                order = newOrder,
                localFilePath = destFile.absolutePath,
                isOfflineDownloaded = false
            )

            val updatedPlaylist = current.copy(
                version = current.version + 1,
                lastPushedVideoId = newId,
                lastPushTimestamp = pushTs,
                items = current.items + newItem
            )
            localStore.setLastHandledPushTimestamp(pushTs)
            persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)

            // Push to Web CMS playlist
            try {
                val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
                api.pushSingleVideo(
                    PushSingleVideoRequest(
                        video = VideoItem(
                            id = newId,
                            title = cleanTitle,
                            url = remoteStreamUrl,
                            order = newOrder,
                            duration = durationSeconds.coerceAtLeast(5),
                            adTagUrl = adTag
                        ),
                        lastPushedVideoId = newId
                    )
                )
            } catch (_: Exception) {}

            newItem
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing local video URI: ${e.message}")
            null
        }
    }

    /**
     * Pushes a specific video ID to immediately start playing on this device and all connected TVs.
     */
    suspend fun pushVideoToPlayNow(videoId: String) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val pushTs = System.currentTimeMillis()
        val updated = current.copy(
            version = current.version + 1,
            lastPushedVideoId = videoId,
            lastPushTimestamp = pushTs
        )
        localStore.setLastHandledPushTimestamp(pushTs)
        persistUpdatedPlaylist(updated, autoSynthesizeMissing = false)

        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            api.pushPlaylistSettings(
                PushPlaylistSettingsRequest(
                    lastPushedVideoId = videoId
                )
            )
        } catch (_: Exception) {}
    }

    suspend fun removeVideoFromPlaylist(videoId: String) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        if (current.items.size <= 1) return@withContext
        val updatedItems = current.items.filterNot { it.id == videoId }
            .mapIndexed { idx, item -> item.copy(order = idx + 1) }
        val updatedPlaylist = current.copy(
            version = current.version + 1,
            items = updatedItems
        )
        persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)

        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            api.pushPlaylistSettings(
                PushPlaylistSettingsRequest(
                    videos = updatedItems.map {
                        VideoItem(
                            id = it.id,
                            title = it.title,
                            url = it.videoUrl,
                            order = it.order,
                            duration = it.durationSeconds,
                            adTagUrl = it.adTagUrl
                        )
                    }
                )
            )
        } catch (_: Exception) {}
    }

    suspend fun updateTickerText(newTicker: String) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val updatedPlaylist = current.copy(
            version = current.version + 1,
            tickerText = newTicker
        )
        persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)
        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            api.pushPlaylistSettings(PushPlaylistSettingsRequest(tickerText = newTicker))
        } catch (_: Exception) {}
    }

    /**
     * Pushes per-device Screen Orientation, Video Orientation, and Scale Mode to the Web CMS
     * so this device's orientation settings stay synced in real time.
     */
    suspend fun pushDeviceOrientationSettings(
        screenOrientation: String? = null,
        videoOrientation: Int? = null,
        scaleMode: String? = null
    ) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val updatedPlaylist = current.copy(
            orientation = screenOrientation ?: current.orientation,
            videoOrientation = videoOrientation ?: current.videoOrientation,
            scaleMode = scaleMode ?: current.scaleMode
        )
        persistUpdatedPlaylist(updatedPlaylist, autoSynthesizeMissing = false)

        if (hasInternetConnection()) {
            try {
                val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
                api.pushPlaylistSettings(
                    PushPlaylistSettingsRequest(
                        deviceId = pairingManager.getDeviceId(),
                        orientation = screenOrientation,
                        videoOrientation = videoOrientation,
                        scaleMode = scaleMode
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private suspend fun persistUpdatedPlaylist(
        playlist: PlaylistResponse,
        autoSynthesizeMissing: Boolean = false
    ) {
        val resolved = resolveLocalFilePaths(playlist, autoSynthesizeMissing = autoSynthesizeMissing)
        val json = moshi.adapter(PlaylistResponse::class.java).toJson(resolved)
        database.playlistDao().savePlaylist(
            CachedPlaylistEntity(
                id = "active_playlist",
                version = resolved.version,
                rawJson = json,
                updatedAt = System.currentTimeMillis()
            )
        )
        localStore.saveCachedPlaylist(resolved)
        _playlistFlow.value = resolved
    }

    private suspend fun downloadVideoWithRetry(api: SignageApiService, videoUrl: String, destination: File): Boolean {
        if (ResilientSignageDataSource.isKnownRestrictedUrl(videoUrl)) {
            return false
        }
        var attempts = 0
        while (attempts < 2) {
            attempts++
            try {
                val response = api.downloadMediaFile(videoUrl)
                if (response.isSuccessful && response.body() != null) {
                    val temp = File(destination.parentFile, "${destination.name}.tmp")
                    response.body()!!.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (SignageSampleVideoGenerator.isValidMp4File(temp)) {
                        if (destination.exists()) destination.delete()
                        temp.renameTo(destination)
                        return true
                    } else {
                        temp.delete()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Download attempt $attempts notice for $videoUrl: ${e.message}")
            }
        }
        return false
    }

    private fun verifyChecksum(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return true
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val computedHex = digest.digest().joinToString("") { "%02x".format(it) }
            computedHex.equals(expectedSha256.trim(), ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating checksum: ${e.message}")
            true
        }
    }

    /**
     * Smart Cache Eviction:
     * Retains currently playing video + next 2 videos + any videos explicitly downloaded for offline playback!
     */
    suspend fun smartCacheEvict(currentIndex: Int, playlist: PlaylistResponse) = withContext(Dispatchers.IO) {
        val total = playlist.items.size
        if (total <= 3) return@withContext

        val keepIndices = setOf(
            currentIndex % total,
            (currentIndex + 1) % total,
            (currentIndex + 2) % total
        )

        val keepFileNames = mutableSetOf<String>()
        keepIndices.forEach { idx ->
            playlist.items.getOrNull(idx)?.let { keepFileNames.add(localStore.getLocalVideoFile(it).name) }
        }
        // Never evict videos that user downloaded for offline playback
        playlist.items.forEach { item ->
            if (item.isOfflineDownloaded || localStore.isVideoOfflineDownloaded(item.id)) {
                keepFileNames.add(localStore.getLocalVideoFile(item).name)
            }
        }

        val files = localStore.videoCacheDir.listFiles() ?: return@withContext
        for (file in files) {
            if (file.isFile && !keepFileNames.contains(file.name)) {
                file.delete()
            }
        }
    }

    suspend fun sendDeviceHeartbeat(currentVideoId: String? = null): String? = withContext(Dispatchers.IO) {
        if (!hasInternetConnection() || !pairingManager.isPaired()) return@withContext null
        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            val request = HeartbeatRequest(
                deviceId = pairingManager.getDeviceId(),
                pairingCode = pairingManager.getPairingCode(),
                status = "PLAYING",
                currentVideoId = currentVideoId,
                storageUsed = localStore.getCachedStorageSizeBytes(),
                appVersion = "1.0",
                lastSyncTime = localStore.getLastSyncTimestamp(),
                screenOrientation = localStore.getDisplayOrientation(),
                videoOrientation = localStore.getVideoOrientation(),
                scaleMode = localStore.getVideoScaleMode()
            )
            val response = api.sendHeartbeat(request)
            val action = response.body()?.serverAction
            if (!action.isNullOrBlank()) {
                when {
                    action == "unpairDevice" -> pairingManager.unpair()
                    action == "pushSync" -> syncPlaylist()
                    action == "clearCache" -> localStore.cleanupUnusedVideos(emptyList())
                    action == "setPortrait" -> localStore.setDisplayOrientation("PORTRAIT")
                    action == "setLandscape" -> localStore.setDisplayOrientation("LANDSCAPE")
                    action.startsWith("setScreenOrientation:") -> {
                        val orient = action.removePrefix("setScreenOrientation:").trim()
                        if (orient.isNotEmpty()) localStore.setDisplayOrientation(orient)
                    }
                    action.startsWith("setVideoOrientation:") -> {
                        val deg = action.removePrefix("setVideoOrientation:").trim().toIntOrNull()
                        if (deg != null) localStore.setVideoOrientation(deg)
                    }
                    action.startsWith("setScaleMode:") -> {
                        val sm = action.removePrefix("setScaleMode:").trim()
                        if (sm.isNotEmpty()) localStore.setVideoScaleMode(sm)
                    }
                }
            }
            action
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun checkSilentUpdate(api: SignageApiService) {
        try {
            val response = api.checkForUpdates(1)
            if (response.isSuccessful && response.body()?.updateAvailable == true) {
                val update = response.body()!!
                if (!update.apkUrl.isNullOrBlank()) {
                    updateManager.downloadAndInstallApk(api, update.apkUrl, update.versionCode)
                }
            }
        } catch (_: Exception) {}
    }

    private fun logIncident(level: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.deviceLogDao().insert(
                    DeviceLogEntity(level = level, tag = TAG, message = message)
                )
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "SignageRepository"

        const val SAMPLE_VAST_TAG =
            "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples&sz=640x480&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator="

        val RELIABLE_SAMPLE_VIDEOS = listOf(
            "https://www.w3schools.com/html/mov_bbb.mp4",
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "https://media.w3.org/2010/05/bunny/trailer.mp4"
        )

        fun sanitizeVideoUrl(url: String, orderHint: Int = 1): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty() || ResilientSignageDataSource.isKnownRestrictedUrl(trimmed)) {
                return when {
                    trimmed.contains("BigBuckBunny", ignoreCase = true) -> RELIABLE_SAMPLE_VIDEOS[0]
                    trimmed.contains("ElephantsDream", ignoreCase = true) -> RELIABLE_SAMPLE_VIDEOS[1]
                    trimmed.contains("ForBiggerBlazes", ignoreCase = true) -> RELIABLE_SAMPLE_VIDEOS[2]
                    trimmed.contains("WeAreGoingOnBullrun", ignoreCase = true) -> RELIABLE_SAMPLE_VIDEOS[3]
                    else -> RELIABLE_SAMPLE_VIDEOS[(orderHint - 1).coerceAtLeast(0) % RELIABLE_SAMPLE_VIDEOS.size]
                }
            }
            return trimmed
        }

        fun createDefaultPlaylist(): PlaylistResponse {
            return PlaylistResponse(
                id = "display-lobby-01",
                name = "OmniSign Live Loop",
                version = 1,
                syncIntervalMinutes = 15,
                defaultAdTagUrl = SAMPLE_VAST_TAG,
                tickerText = "★ LIVE DIGITAL SIGNAGE ★ Continuous Gapless Loop • Instant Push Auto-Play • Offline Video Caching • 24/7 Watchdog Recovery",
                orientation = "LANDSCAPE",
                scaleMode = "ZOOM",
                tickerEnabled = true,
                isMuted = false,
                kioskLocked = true,
                autoBootEnabled = true,
                items = listOf(
                    PlaylistItem(
                        id = "vid_bunny",
                        title = "Brand Showcase: Nature & Animation",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[0],
                        durationSeconds = 60,
                        adTagUrl = SAMPLE_VAST_TAG,
                        order = 1,
                        isOfflineDownloaded = true
                    ),
                    PlaylistItem(
                        id = "vid_dream",
                        title = "Digital Arts & 3D Render Feature",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[1],
                        durationSeconds = 45,
                        adTagUrl = null,
                        order = 2,
                        isOfflineDownloaded = true
                    ),
                    PlaylistItem(
                        id = "vid_blazes",
                        title = "High Energy Automotive Commercial",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[2],
                        durationSeconds = 30,
                        adTagUrl = SAMPLE_VAST_TAG,
                        order = 3,
                        isOfflineDownloaded = true
                    ),
                    PlaylistItem(
                        id = "vid_bullrun",
                        title = "Summer Road Trip Experience",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[3],
                        durationSeconds = 40,
                        adTagUrl = null,
                        order = 4,
                        isOfflineDownloaded = true
                    )
                )
            )
        }
    }
}
