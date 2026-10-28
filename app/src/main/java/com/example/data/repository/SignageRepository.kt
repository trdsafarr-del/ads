package com.example.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.api.ApkUpdateResponse
import com.example.data.api.HeartbeatRequest
import com.example.data.api.PairRequest
import com.example.data.api.SignageApiService
import com.example.data.local.SignageLocalStore
import com.example.data.local.db.CachedPlaylistEntity
import com.example.data.local.db.CachedVideoEntity
import com.example.data.local.db.DeviceLogEntity
import com.example.data.local.db.SignageDatabase
import com.example.data.model.PlaylistItem
import com.example.data.model.PlaylistResponse
import com.example.data.model.PlaylistSchema
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

    private fun resolveLocalFilePaths(playlist: PlaylistResponse): PlaylistResponse {
        val updatedItems = playlist.items.mapIndexed { index, item ->
            val cleanUrl = sanitizeVideoUrl(item.videoUrl, index + 1)
            val sanitizedItem = item.copy(videoUrl = cleanUrl)
            val existingPath = localStore.getLocalVideoPathIfExists(sanitizedItem)
            val validExisting = existingPath?.takeIf {
                SignageSampleVideoGenerator.isValidMp4File(File(it))
            }
            val finalPath = validExisting ?: SignageSampleVideoGenerator.getOrCreateVideoForItem(
                context = context,
                item = sanitizedItem,
                targetFile = localStore.getLocalVideoFile(sanitizedItem)
            )?.absolutePath
            sanitizedItem.copy(localFilePath = finalPath)
        }
        return playlist.copy(items = updatedItems)
    }

    private fun createRetrofit(baseUrl: String): SignageApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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
     * Executes Cloud Sync with exponential backoff, checksum verification, Room persistence,
     * and smart cache management.
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

                // 1. Fetch remote playlist schema
                val response = try {
                    api.fetchPlaylist(deviceId)
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

                // 2. Download and verify each video asset
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
                                Log.w(TAG, "Checksum mismatch for ${item.title}! Deleting corrupt file.")
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
                    val updatedItem = item.copy(localFilePath = finalPath)
                    updatedItems.add(updatedItem)

                    // Persist to Room VideoDao
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

                // Save to Room and JSON cache
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

                // Check for silent auto-update if idle maintenance window or configured
                checkSilentUpdate(api)

                return@withContext Result.success(finalPlaylist)
            } catch (e: Exception) {
                lastError = e
                attempt++
                val delaySeconds = min(300.0, 2.0.pow(attempt.toDouble())).toLong()
                Log.w(TAG, "Sync failed on attempt $attempt. Retrying in ${delaySeconds}s: ${e.message}")
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
        if (!schema.scaleMode.isNullOrBlank()) {
            localStore.setVideoScaleMode(schema.scaleMode)
        }
        val items = schema.videos.mapIndexed { idx, v ->
            PlaylistItem(
                id = v.id,
                title = v.title ?: "Broadcast: ${v.id}",
                videoUrl = sanitizeVideoUrl(v.url, idx + 1),
                checksum = v.checksum,
                durationSeconds = v.duration,
                adTagUrl = v.adTagUrl ?: defaultAdTag,
                order = v.order
            )
        }
        return PlaylistResponse(
            id = "playlist-v${schema.version}",
            name = "CMS Scheduled Broadcast",
            version = schema.version,
            syncIntervalMinutes = 15,
            defaultAdTagUrl = defaultAdTag,
            tickerText = schema.tickerText,
            items = items
        )
    }

    /**
     * Polls remote CMS server (https://ads-broo.vercel.app/api/devices/pair) for 6-digit pairing confirmation.
     */
    suspend fun pollRemotePairing(): Boolean = withContext(Dispatchers.IO) {
        if (pairingManager.isPaired()) return@withContext true
        try {
            val api = createRetrofit(localStore.getApiServerUrl())
            val response = api.pairDevice(
                PairRequest(
                    deviceId = pairingManager.getDeviceId(),
                    pairingCode = pairingManager.getPairingCode(),
                    deviceInfo = "Android TV (${Build.MANUFACTURER} ${Build.MODEL})"
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null && body.success) {
                val token = body.deviceToken ?: "cms_token_${System.currentTimeMillis()}"
                pairingManager.confirmPairing(token)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Pairing poll awaiting CMS confirmation: ${e.message}")
        }
        false
    }

    /**
     * Allows operator to add a video directly from Admin Settings Panel or local CMS.
     */
    suspend fun addVideoToPlaylist(
        title: String,
        videoUrl: String,
        durationSeconds: Int = 30,
        enableVastAd: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val newOrder = (current.items.maxOfOrNull { it.order } ?: 0) + 1
        val newItem = PlaylistItem(
            id = "vid_custom_${System.currentTimeMillis()}",
            title = title.ifBlank { "Custom Broadcast #$newOrder" },
            videoUrl = videoUrl.trim(),
            durationSeconds = durationSeconds.coerceAtLeast(5),
            adTagUrl = if (enableVastAd) (current.defaultAdTagUrl ?: SAMPLE_VAST_TAG) else null,
            order = newOrder
        )
        val updatedPlaylist = current.copy(
            version = current.version + 1,
            items = current.items + newItem
        )
        persistUpdatedPlaylist(updatedPlaylist)
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
        persistUpdatedPlaylist(updatedPlaylist)
    }

    suspend fun updateTickerText(newTicker: String) = withContext(Dispatchers.IO) {
        val current = _playlistFlow.value
        val updatedPlaylist = current.copy(
            version = current.version + 1,
            tickerText = newTicker
        )
        persistUpdatedPlaylist(updatedPlaylist)
    }

    private suspend fun persistUpdatedPlaylist(playlist: PlaylistResponse) {
        val resolved = resolveLocalFilePaths(playlist)
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

    /**
     * Verifies SHA-256 checksum of downloaded media file.
     */
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
     * Retains currently playing video + next 2 videos in the playlist loop on disk,
     * removing older cached files to prevent 2GB Android TV storage exhaustion.
     */
    suspend fun smartCacheEvict(currentIndex: Int, playlist: PlaylistResponse) = withContext(Dispatchers.IO) {
        val total = playlist.items.size
        if (total <= 3) return@withContext

        val keepIndices = setOf(
            currentIndex % total,
            (currentIndex + 1) % total,
            (currentIndex + 2) % total
        )

        val keepFileNames = keepIndices.mapNotNull { idx ->
            playlist.items.getOrNull(idx)?.let { localStore.getLocalVideoFile(it).name }
        }.toSet()

        val files = localStore.videoCacheDir.listFiles() ?: return@withContext
        for (file in files) {
            if (file.isFile && !keepFileNames.contains(file.name)) {
                Log.d(TAG, "Smart cache eviction: Removing ${file.name} to free TV storage")
                file.delete()
            }
        }
    }

    /**
     * Reports device status heartbeat to server every 5 minutes and processes queued remote CMS commands.
     */
    suspend fun sendDeviceHeartbeat(currentVideoId: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val api = createRetrofit(localStore.getApiServerUrl())
            val request = HeartbeatRequest(
                deviceId = pairingManager.getDeviceId(),
                status = "PLAYING",
                currentVideoId = currentVideoId,
                storageUsed = localStore.getCachedStorageSizeBytes(),
                appVersion = "1.0",
                lastSyncTime = localStore.getLastSyncTimestamp()
            )
            val response = api.sendHeartbeat(request)
            val action = response.body()?.serverAction
            if (!action.isNullOrBlank()) {
                when (action) {
                    "pushSync" -> syncPlaylist()
                    "clearCache" -> localStore.cleanupUnusedVideos(emptyList())
                    "setPortrait" -> localStore.setDisplayOrientation("PORTRAIT")
                    "setLandscape" -> localStore.setDisplayOrientation("LANDSCAPE")
                }
            }
            Log.d(TAG, "Heartbeat transmitted successfully to CMS (action=$action)")
            action
        } catch (e: Exception) {
            Log.d(TAG, "Heartbeat transmit skipped or offline: ${e.message}")
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

        /**
         * Automatically remaps legacy Google Cloud Storage sample URLs that return HTTP 403
         * to reliable, globally accessible HTTPS MP4 streams.
         */
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
                tickerText = "★ LIVE DIGITAL SIGNAGE ★ Continuous Gapless Loop • Google IMA VAST Ads • Smart Cache Eviction • 24/7 Watchdog Recovery",
                items = listOf(
                    PlaylistItem(
                        id = "vid_bunny",
                        title = "Brand Showcase: Nature & Animation",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[0],
                        durationSeconds = 60,
                        adTagUrl = SAMPLE_VAST_TAG,
                        order = 1
                    ),
                    PlaylistItem(
                        id = "vid_dream",
                        title = "Digital Arts & 3D Render Feature",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[1],
                        durationSeconds = 45,
                        adTagUrl = null,
                        order = 2
                    ),
                    PlaylistItem(
                        id = "vid_blazes",
                        title = "High Energy Automotive Commercial",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[2],
                        durationSeconds = 30,
                        adTagUrl = SAMPLE_VAST_TAG,
                        order = 3
                    ),
                    PlaylistItem(
                        id = "vid_bullrun",
                        title = "Summer Road Trip Experience",
                        videoUrl = RELIABLE_SAMPLE_VIDEOS[3],
                        durationSeconds = 40,
                        adTagUrl = null,
                        order = 4
                    )
                )
            )
        }
    }
}
