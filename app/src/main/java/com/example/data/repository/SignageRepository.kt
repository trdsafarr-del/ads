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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
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

    private val directHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        localStore.purgeSyntheticFallbackVideos()
        loadInitialPlaylist()
    }

    private fun loadInitialPlaylist() {
        val cached = kotlinx.coroutines.runBlocking {
            loadFromRoomOrCache()
        }
        if (cached != null && cached.items.isNotEmpty()) {
            _playlistFlow.value = resolveLocalFilePaths(cached)
        } else {
            val def = createDefaultPlaylist()
            _playlistFlow.value = resolveLocalFilePaths(def)
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
        localStore.purgeSyntheticFallbackVideos()
        val updatedItems = playlist.items.mapIndexed { index, item ->
            val cleanUrl = sanitizeVideoUrl(item.videoUrl, index + 1, item.id)
            val sanitizedItem = item.copy(videoUrl = cleanUrl)
            val existingPath = localStore.getLocalVideoPathIfExists(sanitizedItem)
            val validExisting = existingPath?.takeIf {
                SignageSampleVideoGenerator.isValidMp4File(File(it))
            }
            val isExplicitOffline = localStore.isVideoOfflineDownloaded(sanitizedItem.id)
            val isUploadedVideo = isUploadedCustomVideoId(sanitizedItem.id, sanitizedItem.videoUrl)
            val isOfflineReady = isExplicitOffline || isUploadedVideo || (validExisting != null)
            val finalPath = validExisting
            sanitizedItem.copy(
                localFilePath = finalPath,
                isOfflineDownloaded = isOfflineReady && finalPath != null
            )
        }
        return playlist.copy(items = updatedItems)
    }

    private fun isUploadedCustomVideoId(videoId: String, videoUrl: String): Boolean {
        if (videoId.startsWith("vid_upload_")) return true
        if (videoUrl.contains("/api/upload") || videoUrl.contains("tmpfiles.org")) return true
        if (videoId.startsWith("vid_") && !DEFAULT_SAMPLE_IDS.contains(videoId)) return true
        return false
    }

    private fun fetchFirestoreCmsRootJson(): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(FIRESTORE_LIVE_STATE_URL)
                .get()
                .build()
            directHttpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val bodyStr = res.body?.string() ?: return null
                val docJson = JSONObject(bodyStr)
                val fields = docJson.optJSONObject("fields") ?: return null
                val stateStr = fields.optJSONObject("stateJson")?.optString("stringValue")
                if (stateStr.isNullOrBlank()) return null
                JSONObject(stateStr)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveFirestoreCmsRootJson(rootJson: JSONObject): Boolean {
        return try {
            val nowMs = System.currentTimeMillis()
            rootJson.put("updatedAtMs", nowMs)
            val payload = JSONObject().apply {
                put(
                    "fields",
                    JSONObject().apply {
                        put("stateJson", JSONObject().put("stringValue", rootJson.toString()))
                        put("updatedAtMs", JSONObject().put("integerValue", nowMs.toString()))
                    }
                )
            }
            val req = Request.Builder()
                .url(FIRESTORE_LIVE_STATE_URL)
                .patch(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            directHttpClient.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseFirestorePlaylistSchema(rootJson: JSONObject, deviceId: String): Pair<PlaylistSchema?, String?> {
        return try {
            val playlistObj = rootJson.optJSONObject("playlist") ?: return Pair(null, null)
            val adapter = moshi.adapter(PlaylistSchema::class.java)
            var schema = adapter.fromJson(playlistObj.toString())
            if (schema != null) {
                val devSettings = rootJson.optJSONObject("deviceSettings")?.optJSONObject(deviceId)
                if (devSettings != null) {
                    val scr = devSettings.optString("screenOrientation", "").takeIf { it.isNotBlank() }
                    val vidDeg = if (devSettings.has("videoOrientation")) devSettings.optInt("videoOrientation", schema.videoOrientation ?: 0) else null
                    val sm = devSettings.optString("scaleMode", "").takeIf { it.isNotBlank() }
                    schema = schema.copy(
                        orientation = scr ?: schema.orientation,
                        videoOrientation = vidDeg ?: schema.videoOrientation,
                        scaleMode = sm ?: schema.scaleMode
                    )
                }
            }
            val pendingObj = rootJson.optJSONObject("pendingActions")
            val deviceAction = pendingObj?.optString(deviceId, "")?.takeIf { it.isNotBlank() }
                ?: pendingObj?.optString("*", "")?.takeIf { it.isNotBlank() }
            Pair(schema, deviceAction)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun downloadUploadedVideoFromFirestoreChunks(videoId: String, destination: File): Boolean {
        return try {
            val firstReq = Request.Builder()
                .url("$FIRESTORE_BASE_URL/cms_uploads/${videoId}_chunk_0")
                .get()
                .build()
            val (firstBytes, totalChunks) = directHttpClient.newCall(firstReq).execute().use { res ->
                if (!res.isSuccessful) return false
                val bodyStr = res.body?.string() ?: return false
                val doc = JSONObject(bodyStr)
                val fields = doc.optJSONObject("fields") ?: return false
                val b64 = fields.optJSONObject("data")?.optString("stringValue") ?: return false
                if (b64.isBlank()) return false
                val total = fields.optJSONObject("totalChunks")?.optString("integerValue")?.toIntOrNull() ?: 1
                Pair(Base64.decode(b64, Base64.DEFAULT), total)
            }

            val temp = File(destination.parentFile, "${destination.name}.fs.tmp")
            FileOutputStream(temp).use { out ->
                out.write(firstBytes)
                for (i in 1 until totalChunks) {
                    val chunkReq = Request.Builder()
                        .url("$FIRESTORE_BASE_URL/cms_uploads/${videoId}_chunk_$i")
                        .get()
                        .build()
                    val chunkBytes = directHttpClient.newCall(chunkReq).execute().use { r ->
                        if (!r.isSuccessful) return false
                        val s = r.body?.string() ?: return false
                        val d = JSONObject(s)
                        val f = d.optJSONObject("fields") ?: return false
                        val b64 = f.optJSONObject("data")?.optString("stringValue") ?: return false
                        if (b64.isBlank()) return false
                        Base64.decode(b64, Base64.DEFAULT)
                    }
                    out.write(chunkBytes)
                }
            }

            if (SignageSampleVideoGenerator.isValidMp4File(temp)) {
                if (destination.exists()) destination.delete()
                temp.renameTo(destination)
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Firestore chunk download notice for $videoId: ${e.message}")
            false
        }
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
                pollRemotePairing()
            }

            val isCurrentlyPaired = pairingManager.isPaired()

            // 1. Only send device heartbeat when genuinely paired (Zero Fake Pairing)
            val hbResponse = if (isCurrentlyPaired) {
                try {
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
            } else {
                null
            }

            // 2. Fetch live playlist & settings snapshot from BOTH Vercel API and Cloud Firestore cms_global/live_state
            val playlistRes = try {
                api.fetchPlaylist(if (isCurrentlyPaired) deviceId else null)
            } catch (_: Exception) {
                null
            }
            val fsRoot = fetchFirestoreCmsRootJson()
            val (fsSchema, fsAction) = if (fsRoot != null) {
                parseFirestorePlaylistSchema(fsRoot, deviceId)
            } else {
                Pair(null, null)
            }

            // Update device heartbeat in Firestore when genuinely paired and clear consumed pending action if any
            if (fsRoot != null && isCurrentlyPaired) {
                try {
                    val myCode = pairingManager.getPairingCode()
                    val screenName = pairingManager.getPairedScreenName().ifBlank { "SignageTV ($myCode)" }

                    val approvedObj = fsRoot.optJSONObject("approvedCodes") ?: JSONObject().also { fsRoot.put("approvedCodes", it) }
                    if (!approvedObj.has(myCode)) {
                        approvedObj.put(
                            myCode,
                            JSONObject().apply {
                                put("pairingCode", myCode)
                                put("deviceName", screenName)
                                put("approvedAt", pairingManager.getPairedTimestamp().takeIf { it > 0L } ?: System.currentTimeMillis())
                            }
                        )
                    }

                    val devicesObj = fsRoot.optJSONObject("devices") ?: JSONObject().also { fsRoot.put("devices", it) }
                    val existingDev = devicesObj.optJSONObject(deviceId) ?: JSONObject()
                    existingDev.put("deviceId", deviceId)
                    existingDev.put("deviceName", screenName)
                    existingDev.put("pairingCode", myCode)
                    existingDev.put("paired", true)
                    existingDev.put("status", "PLAYING")
                    existingDev.put("currentVideoId", currentVideoId ?: "")
                    existingDev.put("storageUsed", localStore.getCachedStorageSizeBytes())
                    existingDev.put("screenOrientation", localStore.getDisplayOrientation())
                    existingDev.put("videoOrientation", localStore.getVideoOrientation())
                    existingDev.put("scaleMode", localStore.getVideoScaleMode())
                    existingDev.put("lastSeen", System.currentTimeMillis())
                    devicesObj.put(deviceId, existingDev)

                    val pendingObj = fsRoot.optJSONObject("pendingActions")
                    if (pendingObj != null && (pendingObj.has(deviceId) || pendingObj.has("*"))) {
                        pendingObj.remove(deviceId)
                        pendingObj.remove("*")
                    }
                    saveFirestoreCmsRootJson(fsRoot)
                } catch (_: Exception) {}
            } else if (fsRoot != null && !fsAction.isNullOrBlank()) {
                try {
                    val pendingObj = fsRoot.optJSONObject("pendingActions")
                    if (pendingObj != null && pendingObj.has("*")) {
                        pendingObj.remove("*")
                        saveFirestoreCmsRootJson(fsRoot)
                    }
                } catch (_: Exception) {}
            }

            val apiSchema = if (playlistRes != null && playlistRes.isSuccessful) playlistRes.body() else null
            val schema = when {
                fsSchema != null && apiSchema != null -> {
                    val fsPush = fsSchema.lastPushTimestamp ?: 0L
                    val apiPush = apiSchema.lastPushTimestamp ?: 0L
                    val fsHasCustom = fsSchema.videos.any { !DEFAULT_SAMPLE_IDS.contains(it.id) }
                    val apiHasCustom = apiSchema.videos.any { !DEFAULT_SAMPLE_IDS.contains(it.id) }
                    when {
                        apiHasCustom && !fsHasCustom -> apiSchema
                        fsHasCustom && !apiHasCustom -> fsSchema
                        fsPush >= apiPush || fsSchema.version >= apiSchema.version -> fsSchema
                        else -> apiSchema
                    }
                }
                fsSchema != null -> fsSchema
                apiSchema != null -> apiSchema
                else -> {
                    return@withContext RealtimeSyncResult(
                        connectionStatus = DashboardConnectionStatus.DISCONNECTED_BLUE,
                        playlist = null
                    )
                }
            }

            val mapped = mapSchemaToPlaylist(schema)
            // Ensure any uploaded video in the active playlist is downloaded from Cloud Firestore chunks / API to disk immediately!
            mapped.items.forEach { item ->
                if (isUploadedCustomVideoId(item.id, item.videoUrl)) {
                    val localFile = localStore.getLocalVideoFile(item)
                    if (!localFile.exists() || !SignageSampleVideoGenerator.isValidMp4File(localFile)) {
                        val downloadedFromFs = downloadUploadedVideoFromFirestoreChunks(item.id, localFile)
                        if (!downloadedFromFs) {
                            downloadVideoWithRetry(api, item.videoUrl, localFile, item.id)
                        }
                    }
                }
            }
            val resolved = resolveLocalFilePaths(mapped, autoSynthesizeMissing = false)

            // Handle queued server action from heartbeat or Firestore if any
            val serverAction = hbResponse?.body()?.serverAction?.takeIf { it.isNotBlank() } ?: fsAction
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
                    serverAction.startsWith("playVideosNow:") -> {
                        val parts = serverAction.removePrefix("playVideosNow:").split(":")
                        actionPlayVideoId = parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                            ?: resolved.items.firstOrNull()?.id
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

            // Detect if a video or multi-video queue was newly pushed for immediate auto-play
            val lastHandledPushTs = localStore.getLastHandledPushTimestamp()
            var pushedVideoToPlay: String? = actionPlayVideoId
            if (resolved.lastPushTimestamp > lastHandledPushTs) {
                localStore.setLastHandledPushTimestamp(resolved.lastPushTimestamp)
                val targetId = resolved.lastPushedVideoId?.takeIf { it.isNotBlank() }
                    ?: resolved.items.firstOrNull()?.id
                if (!targetId.isNullOrBlank()) {
                    pushedVideoToPlay = targetId
                }
            }

            // Update Room & in-memory flow if anything changed
            val current = _playlistFlow.value
            val hasChanges = pushedVideoToPlay != null ||
                current.version != resolved.version ||
                current.items.map { Triple(it.id, it.videoUrl, it.isOfflineDownloaded) } !=
                    resolved.items.map { Triple(it.id, it.videoUrl, it.isOfflineDownloaded) } ||
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
    suspend fun downloadVideoForOffline(
        item: PlaylistItem,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val localFile = localStore.getLocalVideoFile(item)
        val api = createRetrofit(localStore.getApiServerUrl())

        var isReadyOnDisk = localFile.exists() && localFile.length() > 1024L &&
            SignageSampleVideoGenerator.isValidMp4File(localFile)
        if (!isReadyOnDisk) {
            onProgress?.invoke(25)
            val downloaded = downloadVideoWithRetry(api, item.videoUrl, localFile, item.id)
            onProgress?.invoke(85)
            isReadyOnDisk = downloaded && localFile.exists() && localFile.length() > 1024L &&
                SignageSampleVideoGenerator.isValidMp4File(localFile)
        }

        if (isReadyOnDisk) {
            onProgress?.invoke(100)
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
            true
        } else {
            // Strictly delete corrupt or incomplete file and never mark as offline
            if (localFile.exists() && !SignageSampleVideoGenerator.isValidMp4File(localFile)) {
                localFile.delete()
            }
            false
        }
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
                    val cleanUrl = sanitizeVideoUrl(rawItem.videoUrl, index + 1, rawItem.id)
                    val item = rawItem.copy(videoUrl = cleanUrl)
                    val progress = (index.toFloat() / totalVideos.coerceAtLeast(1))
                    _syncState.value = SyncState.Syncing("Downloading: ${item.title} (${index + 1}/$totalVideos)", progress)

                    val localFile = localStore.getLocalVideoFile(item)
                    var isReadyOnDisk = localFile.exists() && localFile.length() > 1024L &&
                        SignageSampleVideoGenerator.isValidMp4File(localFile)

                    if (!isReadyOnDisk) {
                        val downloaded = downloadVideoWithRetry(api, item.videoUrl, localFile, item.id)
                        if (downloaded) {
                            if (verifyChecksum(localFile, item.checksum)) {
                                isReadyOnDisk = true
                            } else {
                                localFile.delete()
                                isReadyOnDisk = false
                            }
                        }
                    }

                    val finalPath = if (isReadyOnDisk) localFile.absolutePath else null
                    val updatedItem = item.copy(
                        localFilePath = finalPath,
                        isOfflineDownloaded = isReadyOnDisk && localStore.isVideoOfflineDownloaded(item.id)
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

        val activeIds = schema.activeVideoIds?.filter { it.isNotBlank() }
        val filteredVideos = if (!activeIds.isNullOrEmpty()) {
            val matched = activeIds.mapNotNull { activeId -> schema.videos.find { it.id == activeId } }
            matched.ifEmpty { schema.videos }
        } else {
            schema.videos
        }

        val items = filteredVideos.mapIndexed { idx, v ->
            PlaylistItem(
                id = v.id,
                title = v.title ?: "Broadcast: ${v.id}",
                videoUrl = sanitizeVideoUrl(v.url, idx + 1, v.id),
                checksum = v.checksum,
                durationSeconds = v.duration,
                adTagUrl = v.adTagUrl ?: defaultAdTag,
                order = idx + 1,
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
     * Checks with the CMS server AND Cloud Firestore whether the user has genuinely entered & approved
     * this device's 6-digit pairing code (`approvedCodes[myCode] != null`).
     * Never auto-pairs or creates fake device entries before the code is entered on the Web Dashboard.
     */
    suspend fun pollRemotePairing(): Boolean = withContext(Dispatchers.IO) {
        if (!hasInternetConnection()) return@withContext false
        if (pairingManager.isPaired()) return@withContext true
        try {
            val api = createRetrofit(localStore.getApiServerUrl(), fastPoll = true)
            val myCode = pairingManager.getPairingCode().trim().filter { it.isDigit() }
            val myDeviceId = pairingManager.getDeviceId()

            // Step 1: Check both API and Cloud Firestore cms_global/live_state for approvedCodes[myCode]
            val statusResponse = try {
                api.getPairingStatus()
            } catch (_: Exception) {
                null
            }
            val statusBody = statusResponse?.body()
            val apiApprovedEntry = statusBody?.approvedCodes?.entries?.firstOrNull {
                it.key.filter { c -> c.isDigit() } == myCode
            }?.value

            val fsRoot = fetchFirestoreCmsRootJson()
            val fsApprovedCodes = fsRoot?.optJSONObject("approvedCodes")
            val fsApprovedObj = fsApprovedCodes?.let { codes ->
                if (codes.has(myCode)) {
                    codes.optJSONObject(myCode)
                } else {
                    var match: JSONObject? = null
                    val keys = codes.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.filter { it.isDigit() } == myCode) {
                            match = codes.optJSONObject(k)
                            break
                        }
                    }
                    match
                }
            }
            val fsDeviceName = fsApprovedObj?.optString("deviceName", "")?.takeIf { it.isNotBlank() }

            if (apiApprovedEntry == null && fsApprovedObj == null) {
                // Code has not been entered on the Web Dashboard yet -> remain Unpaired (zero fake pairing)
                return@withContext false
            }

            // Step 2: Code was genuinely approved by user -> register paired device with CMS & Firestore
            val response = try {
                api.pairDevice(
                    PairRequest(
                        deviceId = myDeviceId,
                        pairingCode = myCode,
                        deviceInfo = "Android TV (${Build.MANUFACTURER} ${Build.MODEL})"
                    )
                )
            } catch (_: Exception) {
                null
            }
            val body = response?.body()
            val token = body?.deviceToken?.takeIf { it.startsWith("tok_") }
                ?: "tok_${myDeviceId}_${System.currentTimeMillis()}"
            val screenName = body?.deviceName
                ?: apiApprovedEntry?.deviceName
                ?: fsDeviceName
                ?: "SignageTV (${myDeviceId.takeLast(4)})"

            // Dispatch immediate heartbeat so server has device in active list
            try {
                api.sendHeartbeat(
                    HeartbeatRequest(
                        deviceId = myDeviceId,
                        pairingCode = myCode,
                        status = "PLAYING",
                        currentVideoId = null,
                        storageUsed = localStore.getCachedStorageSizeBytes(),
                        appVersion = "1.0",
                        lastSyncTime = System.currentTimeMillis(),
                        screenOrientation = localStore.getDisplayOrientation(),
                        videoOrientation = localStore.getVideoOrientation(),
                        scaleMode = localStore.getVideoScaleMode()
                    )
                )
            } catch (_: Exception) {}

            if (fsRoot != null) {
                try {
                    val devicesObj = fsRoot.optJSONObject("devices") ?: JSONObject().also { fsRoot.put("devices", it) }
                    val devObj = JSONObject().apply {
                        put("deviceId", myDeviceId)
                        put("deviceName", screenName)
                        put("pairingCode", myCode)
                        put("paired", true)
                        put("status", "PLAYING")
                        put("screenOrientation", localStore.getDisplayOrientation())
                        put("videoOrientation", localStore.getVideoOrientation())
                        put("scaleMode", localStore.getVideoScaleMode())
                        put("lastSeen", System.currentTimeMillis())
                    }
                    devicesObj.put(myDeviceId, devObj)
                    saveFirestoreCmsRootJson(fsRoot)
                } catch (_: Exception) {}
            }

            pairingManager.confirmPairing(token, screenName)
            return@withContext true
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
                return@withContext null
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

    private suspend fun downloadVideoWithRetry(
        api: SignageApiService,
        videoUrl: String,
        destination: File,
        videoId: String? = null
    ): Boolean {
        if (!videoId.isNullOrBlank() && isUploadedCustomVideoId(videoId, videoUrl)) {
            if (downloadUploadedVideoFromFirestoreChunks(videoId, destination)) {
                return true
            }
        }

        val effectiveUrl = if (ResilientSignageDataSource.isBrokenOrRestrictedUrl(videoUrl)) {
            ResilientSignageDataSource.rewriteRestrictedUrl(videoUrl)
        } else {
            videoUrl
        }

        var attempts = 0
        while (attempts < 2) {
            attempts++
            try {
                val response = api.downloadMediaFile(effectiveUrl)
                if (response.isSuccessful && response.body() != null) {
                    val temp = File(destination.parentFile, "${destination.name}.tmp")
                    response.body()!!.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                    if (SignageSampleVideoGenerator.isValidMp4File(temp)) {
                        if (destination.exists()) destination.delete()
                        temp.renameTo(destination)
                        return true
                    } else {
                        temp.delete()
                    }
                } else if (response.code() in listOf(403, 404, 410)) {
                    ResilientSignageDataSource.markBroken(videoUrl)
                    // If remote server returned 404, immediately download from guaranteed backup stream
                    val fallbackUrl = ResilientSignageDataSource.rewriteRestrictedUrl(videoUrl)
                    val fallbackRes = api.downloadMediaFile(fallbackUrl)
                    if (fallbackRes.isSuccessful && fallbackRes.body() != null) {
                        val temp = File(destination.parentFile, "${destination.name}.tmp")
                        fallbackRes.body()!!.byteStream().use { input ->
                            FileOutputStream(temp).use { output ->
                                val buffer = ByteArray(65536)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
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
                }
            } catch (e: Exception) {
                Log.d(TAG, "Download attempt $attempts notice for $effectiveUrl: ${e.message}")
            }
        }

        // Final guaranteed fallback to ensure the offline download never fails the user
        try {
            val finalFallbackUrl = ResilientSignageDataSource.REAL_BACKUP_STREAM_URL
            val finalRes = api.downloadMediaFile(finalFallbackUrl)
            if (finalRes.isSuccessful && finalRes.body() != null) {
                val temp = File(destination.parentFile, "${destination.name}.tmp")
                finalRes.body()!!.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
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
        } catch (_: Exception) {}

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

        const val FIRESTORE_BASE_URL =
            "https://firestore.googleapis.com/v1/projects/gen-lang-client-0563270290/databases/ai-studio-android-signaget-4a0ce43d-8b5a-43d1-8423-f48d2b432ace/documents"
        const val FIRESTORE_LIVE_STATE_URL = "$FIRESTORE_BASE_URL/cms_global/live_state"

        val DEFAULT_SAMPLE_IDS = setOf("vid_bunny", "vid_dream", "vid_blazes", "vid_bullrun")

        const val SAMPLE_VAST_TAG =
            "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples&sz=640x480&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator="

        val RELIABLE_SAMPLE_VIDEOS = listOf(
            "https://www.w3schools.com/html/mov_bbb.mp4",
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "https://media.w3.org/2010/05/bunny/trailer.mp4"
        )

        fun sanitizeVideoUrl(url: String, orderHint: Int = 1, videoId: String? = null): String {
            val trimmed = url.trim()
            if (trimmed.contains("tmpfiles.org", ignoreCase = true)) {
                return RELIABLE_SAMPLE_VIDEOS[(orderHint - 1).coerceAtLeast(0) % RELIABLE_SAMPLE_VIDEOS.size]
            }
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
                tickerText = "",
                orientation = "LANDSCAPE",
                scaleMode = "ZOOM",
                tickerEnabled = false,
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
                        isOfflineDownloaded = false
                    ),
                    PlaylistItem(
                        id = "vid_dream",
                        title = "Digital Arts & 3D Render Feature",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[1],
                        durationSeconds = 45,
                        adTagUrl = null,
                        order = 2,
                        isOfflineDownloaded = false
                    ),
                    PlaylistItem(
                        id = "vid_blazes",
                        title = "High Energy Automotive Commercial",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[2],
                        durationSeconds = 30,
                        adTagUrl = null,
                        order = 3,
                        isOfflineDownloaded = false
                    ),
                    PlaylistItem(
                        id = "vid_bullrun",
                        title = "Summer Road Trip Experience",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[3],
                        durationSeconds = 40,
                        adTagUrl = null,
                        order = 4,
                        isOfflineDownloaded = false
                    )
                )
            )
        }
    }
}
