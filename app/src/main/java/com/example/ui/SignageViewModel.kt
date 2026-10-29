package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.cloud.SignageCloudRepository
import com.example.data.local.db.DeviceLogEntity
import com.example.data.model.PlaylistItem
import com.example.data.model.PlaylistResponse
import com.example.data.repository.DashboardConnectionStatus
import com.example.data.repository.SignageRepository
import com.example.data.repository.SyncState
import com.example.pairing.PairingState
import com.example.service.WatchdogService
import com.example.sync.PlaylistSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections


data class SignageUiState(
    val isKioskLocked: Boolean = true,
    val isAutoBootEnabled: Boolean = true,
    val isTickerEnabled: Boolean = false,
    val isPlayerMuted: Boolean = false,
    val isStartupDelayEnabled: Boolean = false,
    val isBurnInProtectionEnabled: Boolean = true,
    val showAdminPinDialog: Boolean = false,
    val showAdminSettingsPanel: Boolean = false,
    val showDiagnosticOverlay: Boolean = false,
    val cacheSizeBytes: Long = 0L,
    val lastSyncStatus: String = "Ready",
    val lastSyncTimestamp: Long = 0L,
    val apiServerUrl: String = "",
    val deviceId: String = "",
    val pairingCode: String = "",
    val isPaired: Boolean = false,
    val pairedScreenName: String = "",
    val pairedAtTimestamp: Long = 0L,
    val pairingStatusMessage: String = "Enter 6-digit code on Web Dashboard to pair",
    val isCheckingPairing: Boolean = false,
    val ipAddress: String = "127.0.0.1",
    val recentLogs: List<DeviceLogEntity> = emptyList(),
    val burnInShiftX: Float = 0f,
    val burnInShiftY: Float = 0f,
    val displayOrientation: com.example.ui.orientation.SignageOrientation = com.example.ui.orientation.SignageOrientation.LANDSCAPE,
    val videoOrientation: com.example.ui.orientation.VideoOrientation = com.example.ui.orientation.VideoOrientation.NORMAL_0,
    val videoScaleMode: String = "ZOOM",
    val connectionStatus: DashboardConnectionStatus = DashboardConnectionStatus.CONNECTED_GREEN,
    val pendingOfflinePopupVideo: PlaylistItem? = null,
    val isDownloadingOffline: Boolean = false,
    val offlineDownloadSuccessMessage: String? = null,
    val offlineDownloadErrorMessage: String? = null,
    val offlineDownloadProgressPercent: Int = 0,
    val pushedVideoIdToAutoPlay: String? = null
)

class SignageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SignageRepository(application)
    private val cloudRepository = SignageCloudRepository(application.applicationContext)
    private val localStore = repository.localStore
    private val pairingManager = repository.pairingManager
    private val database = repository.database

    val playlistFlow: StateFlow<PlaylistResponse> = repository.playlistFlow
    val syncState: StateFlow<SyncState> = repository.syncState

    private var activePlayingVideoId: String? = null
    private var boundFirestoreUserId: String? = null
    private var firestoreListenerJob: Job? = null


    private val _uiState = MutableStateFlow(
        SignageUiState(
            isKioskLocked = localStore.isKioskModeLocked(),
            isAutoBootEnabled = localStore.isAutoBootEnabled(),
            isTickerEnabled = localStore.isTickerEnabled(),
            isPlayerMuted = localStore.isPlayerMuted(),
            isStartupDelayEnabled = localStore.isStartupDelayEnabled(),
            isBurnInProtectionEnabled = localStore.isBurnInProtectionEnabled(),
            cacheSizeBytes = localStore.getCachedStorageSizeBytes(),
            lastSyncStatus = localStore.getLastSyncStatus(),
            lastSyncTimestamp = localStore.getLastSyncTimestamp(),
            apiServerUrl = localStore.getApiServerUrl(),
            deviceId = pairingManager.getDeviceId(),
            pairingCode = pairingManager.getPairingCode(),
            isPaired = pairingManager.isPaired(),
            pairedScreenName = pairingManager.getPairedScreenName(),
            pairedAtTimestamp = pairingManager.getPairedTimestamp(),
            pairingStatusMessage = if (pairingManager.isPaired()) {
                "✔ Paired with CMS (${pairingManager.getPairedScreenName()})"
            } else {
                "Waiting for 6-digit code ${pairingManager.getPairingCode()} on Web Dashboard..."
            },
            ipAddress = getLocalIpAddress(),
            displayOrientation = com.example.ui.orientation.SignageOrientation.fromId(localStore.getDisplayOrientation()),
            videoOrientation = com.example.ui.orientation.VideoOrientation.fromDegrees(localStore.getVideoOrientation()),
            videoScaleMode = localStore.getVideoScaleMode(),
            connectionStatus = if (repository.hasInternetConnection()) {
                DashboardConnectionStatus.CONNECTED_GREEN
            } else {
                DashboardConnectionStatus.OFFLINE_RED
            }
        )
    )
    val uiState: StateFlow<SignageUiState> = _uiState.asStateFlow()

    init {
        observePairing()
        loadLogs()
        startBurnInPixelShiftTimer()
        startRealtimeDashboardSyncLoop()
    }

    /**
     * Binds real-time Cloud Firestore snapshot listeners for the authenticated Google user.
     */
    fun bindAuthenticatedFirestoreUser(userId: String) {
        if (userId.isBlank() || boundFirestoreUserId == userId) return
        boundFirestoreUserId = userId
        firestoreListenerJob?.cancel()
        firestoreListenerJob = viewModelScope.launch(Dispatchers.IO) {
            // Seed initial playlist & device heartbeat into Firestore for this user
            try {
                val currentPlaylist = playlistFlow.value
                currentPlaylist.items.forEach { item ->
                    val safeDocId = item.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
                    cloudRepository.createVideo(
                        videoId = safeDocId,
                        title = item.title,
                        videoUrl = item.videoUrl,
                        durationSeconds = item.durationSeconds.toLong(),
                        order = item.order.toLong(),
                        videoOrientation = "AUTO",
                        isForcedPlay = false
                    )
                }
                cloudRepository.upsertDeviceHeartbeat(
                    deviceId = pairingManager.getDeviceId(),
                    name = pairingManager.getPairedScreenName().ifBlank { "SignageTV (${pairingManager.getPairingCode()})" },
                    pairCode = pairingManager.getPairingCode(),
                    status = "PLAYING",
                    screenOrientation = _uiState.value.displayOrientation.id,
                    videoOrientation = when (_uiState.value.videoOrientation.degrees) {
                        90, 270 -> "PORTRAIT"
                        180 -> "LANDSCAPE"
                        else -> "AUTO"
                    },
                    currentVideoTitle = playlistFlow.value.items.firstOrNull()?.title ?: ""
                )
            } catch (_: Exception) {}

            // Listen to real-time broadcast config updates in Cloud Firestore
            launch {
                cloudRepository.observeConfig(userId)
                    .catch { /* logged by handleFirestoreError */ }
                    .collect { config ->
                        if (config != null) {
                            val newDisplay = com.example.ui.orientation.SignageOrientation.fromId(config.orientation)
                            val newVideoDegrees = when (config.videoOrientation.uppercase()) {
                                "PORTRAIT" -> 90
                                "LANDSCAPE" -> 0
                                else -> 0
                            }
                            localStore.setDisplayOrientation(newDisplay.id)
                            localStore.setVideoOrientation(newVideoDegrees)
                            localStore.setVideoScaleMode(config.scaleMode)
                            _uiState.value = _uiState.value.copy(
                                displayOrientation = newDisplay,
                                videoOrientation = com.example.ui.orientation.VideoOrientation.fromDegrees(newVideoDegrees),
                                videoScaleMode = config.scaleMode,
                                pushedVideoIdToAutoPlay = config.pushedVideoId.takeIf { it.isNotBlank() } ?: _uiState.value.pushedVideoIdToAutoPlay
                            )
                        }
                    }
            }
        }
    }


    private fun observePairing() {
        viewModelScope.launch {
            pairingManager.pairingState.collect { state ->
                when (state) {
                    is PairingState.Paired -> {
                        _uiState.value = _uiState.value.copy(
                            isPaired = true,
                            deviceId = state.deviceId,
                            pairingCode = state.pairingCode,
                            pairedScreenName = state.screenName,
                            pairedAtTimestamp = state.pairedAtTimestamp,
                            pairingStatusMessage = "✔ Paired with CMS (${state.screenName})"
                        )
                    }
                    is PairingState.Unpaired -> {
                        _uiState.value = _uiState.value.copy(
                            isPaired = false,
                            deviceId = state.deviceId,
                            pairingCode = state.pairingCode,
                            pairedScreenName = "",
                            pairedAtTimestamp = 0L,
                            pairingStatusMessage = "Waiting for code ${state.pairingCode} on Web Dashboard..."
                        )
                    }
                }
            }
        }
    }

    /**
     * Live background sync loop (runs when internet is ON):
     * 1. Checks internet connectivity first. If offline -> Red dot (no network traffic).
     * 2. If online -> runs every 2.5 seconds in background (Dispatchers.IO):
     *    - Checks 6-digit pairing confirmation when unpaired.
     *    - Syncs Web Dashboard settings, playlist, offline download requests, and auto-play pushes when paired.
     *    - When a remote Play command arrives, automatically dismisses any open panel/dialog so the video plays full-screen immediately.
     */
    private fun startRealtimeDashboardSyncLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (!repository.hasInternetConnection()) {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = DashboardConnectionStatus.OFFLINE_RED
                    )
                } else {
                    WatchdogService.notifyUiSynced()
                    val wasPairedBefore = _uiState.value.isPaired
                    val result = repository.pollRealtimeDashboardSync(activePlayingVideoId)
                    val isPairedNow = pairingManager.isPaired()

                    val isForcePlayTriggered = !result.pushedVideoIdToAutoPlay.isNullOrBlank()
                    val currentPopup = _uiState.value.pendingOfflinePopupVideo
                    val nextPopup = if (isForcePlayTriggered && result.newlyUploadedVideoForPopup == null) {
                        null
                    } else {
                        currentPopup ?: result.newlyUploadedVideoForPopup
                    }
                    val nextAutoPlay = result.pushedVideoIdToAutoPlay ?: _uiState.value.pushedVideoIdToAutoPlay

                    _uiState.value = _uiState.value.copy(
                        connectionStatus = result.connectionStatus,
                        isPaired = isPairedNow,
                        deviceId = pairingManager.getDeviceId(),
                        pairingCode = pairingManager.getPairingCode(),
                        pairedScreenName = pairingManager.getPairedScreenName(),
                        pairedAtTimestamp = pairingManager.getPairedTimestamp(),
                        displayOrientation = com.example.ui.orientation.SignageOrientation.fromId(localStore.getDisplayOrientation()),
                        videoOrientation = com.example.ui.orientation.VideoOrientation.fromDegrees(localStore.getVideoOrientation()),
                        videoScaleMode = localStore.getVideoScaleMode(),
                        isTickerEnabled = localStore.isTickerEnabled(),
                        isPlayerMuted = localStore.isPlayerMuted(),
                        isKioskLocked = localStore.isKioskModeLocked(),
                        isAutoBootEnabled = localStore.isAutoBootEnabled(),
                        cacheSizeBytes = localStore.getCachedStorageSizeBytes(),
                        lastSyncStatus = localStore.getLastSyncStatus(),
                        lastSyncTimestamp = localStore.getLastSyncTimestamp(),
                        showAdminSettingsPanel = _uiState.value.showAdminSettingsPanel,
                        showAdminPinDialog = _uiState.value.showAdminPinDialog,
                        showDiagnosticOverlay = _uiState.value.showDiagnosticOverlay,
                        pendingOfflinePopupVideo = nextPopup,
                        pushedVideoIdToAutoPlay = nextAutoPlay
                    )

                    if (!wasPairedBefore && isPairedNow) {
                        triggerCloudSync()
                    }
                }

                // Live sync every 2.5 seconds in background for instant Play response
                delay(2_500L)
            }
        }
    }

    private fun startBurnInPixelShiftTimer() {
        viewModelScope.launch {
            var step = 0
            val shifts = listOf(
                Pair(0f, 0f),
                Pair(3f, -2f),
                Pair(-2f, 3f),
                Pair(2f, 2f),
                Pair(-3f, -2f)
            )
            while (true) {
                delay(5 * 60 * 1000L)
                if (localStore.isBurnInProtectionEnabled()) {
                    step = (step + 1) % shifts.size
                    val (sx, sy) = shifts[step]
                    _uiState.value = _uiState.value.copy(burnInShiftX = sx, burnInShiftY = sy)
                }
            }
        }
    }

    fun onMediaItemAdvanced(currentIndex: Int, currentPlaylist: PlaylistResponse) {
        val currentId = currentPlaylist.items.getOrNull(currentIndex)?.id
        activePlayingVideoId = currentId
        viewModelScope.launch {
            repository.smartCacheEvict(currentIndex, currentPlaylist)
        }
    }

    fun onPushedVideoAutoPlayed() {
        _uiState.value = _uiState.value.copy(pushedVideoIdToAutoPlay = null)
    }

    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = database.deviceLogDao().getRecentLogs(20)
            _uiState.value = _uiState.value.copy(recentLogs = logs)
        }
    }

    fun pollPairingStatus() {
        viewModelScope.launch {
            if (!repository.hasInternetConnection()) {
                _uiState.value = _uiState.value.copy(
                    connectionStatus = DashboardConnectionStatus.OFFLINE_RED,
                    pairingStatusMessage = "Offline: Connect to internet to verify 6-digit code ${pairingManager.getPairingCode()}"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isCheckingPairing = true,
                pairingStatusMessage = "Checking CMS for 6-digit code ${pairingManager.getPairingCode()}..."
            )
            val paired = repository.pollRemotePairing()
            if (paired || pairingManager.isPaired()) {
                _uiState.value = _uiState.value.copy(
                    isCheckingPairing = false,
                    isPaired = true,
                    pairedScreenName = pairingManager.getPairedScreenName(),
                    pairedAtTimestamp = pairingManager.getPairedTimestamp(),
                    pairingStatusMessage = "✔ Paired with CMS (${pairingManager.getPairedScreenName()})"
                )
                triggerCloudSync()
            } else {
                _uiState.value = _uiState.value.copy(
                    isCheckingPairing = false,
                    isPaired = false,
                    pairingStatusMessage = "Awaiting approval: Enter code ${pairingManager.getPairingCode()} on Web Dashboard & click 'Pair TV Now'"
                )
            }
        }
    }

    fun regeneratePairingCode() {
        val newCode = pairingManager.regeneratePairingCode()
        _uiState.value = _uiState.value.copy(
            isPaired = false,
            pairingCode = newCode,
            pairedScreenName = "",
            pairedAtTimestamp = 0L,
            pairingStatusMessage = "New 6-digit code generated: $newCode — Enter on Web Dashboard"
        )
    }

    fun unpairDevice() {
        viewModelScope.launch {
            val newCode = repository.unpairDeviceFromCms()
            _uiState.value = _uiState.value.copy(
                isPaired = false,
                pairingCode = newCode,
                pairedScreenName = "",
                pairedAtTimestamp = 0L,
                pairingStatusMessage = "Device Unpaired. Enter new 6-digit code $newCode on Web Dashboard to pair."
            )
        }
    }

    /**
     * Adds a video link, pushes it to play immediately on all screens,
     * and opens the "Download for Offline" popup.
     */
    fun addVideoToPlaylist(title: String, videoUrl: String, durationSeconds: Int, enableVastAd: Boolean) {
        viewModelScope.launch {
            val newItem = repository.addVideoToPlaylist(title, videoUrl, durationSeconds, enableVastAd)
            if (!boundFirestoreUserId.isNullOrBlank()) {
                try {
                    val safeDocId = newItem.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
                    cloudRepository.createVideo(
                        videoId = safeDocId,
                        title = newItem.title,
                        videoUrl = newItem.videoUrl,
                        durationSeconds = newItem.durationSeconds.toLong(),
                        order = newItem.order.toLong(),
                        videoOrientation = "AUTO",
                        isForcedPlay = true
                    )
                } catch (_: Exception) {}
            }
            _uiState.value = _uiState.value.copy(
                pushedVideoIdToAutoPlay = newItem.id,
                pendingOfflinePopupVideo = newItem,
                offlineDownloadSuccessMessage = null,
                cacheSizeBytes = localStore.getCachedStorageSizeBytes()
            )
        }
    }


    /**
     * Uploads a local video file picked from the Android device, pushes it to play immediately,
     * and opens the "Download for Offline" popup.
     */
    fun uploadLocalVideoUri(uri: Uri, title: String, durationSeconds: Int, enableVastAd: Boolean) {
        viewModelScope.launch {
            val newItem = repository.importLocalVideoUri(uri, title, durationSeconds, enableVastAd)
            if (newItem != null) {
                _uiState.value = _uiState.value.copy(
                    pushedVideoIdToAutoPlay = newItem.id,
                    pendingOfflinePopupVideo = newItem,
                    offlineDownloadSuccessMessage = null,
                    cacheSizeBytes = localStore.getCachedStorageSizeBytes()
                )
            }
        }
    }

    /**
     * Pushes an existing video in the playlist to immediately start playing on all screens,
     * dismissing any open panels or overlays so playback is unobstructed.
     */
    fun pushPlayVideoNow(videoId: String) {
        viewModelScope.launch {
            repository.pushVideoToPlayNow(videoId)
            if (!boundFirestoreUserId.isNullOrBlank()) {
                try {
                    cloudRepository.upsertBroadcastConfig(
                        orientation = _uiState.value.displayOrientation.id,
                        videoOrientation = when (_uiState.value.videoOrientation.degrees) {
                            90, 270 -> "PORTRAIT"
                            else -> "AUTO"
                        },
                        scaleMode = _uiState.value.videoScaleMode,
                        pushedVideoId = videoId,
                        pushedVideoTimestamp = System.currentTimeMillis(),
                        version = (playlistFlow.value.version + 1).toLong()
                    )
                } catch (_: Exception) {}
            }
            _uiState.value = _uiState.value.copy(
                pushedVideoIdToAutoPlay = videoId,
                showAdminSettingsPanel = false,
                showAdminPinDialog = false,
                showDiagnosticOverlay = false,
                pendingOfflinePopupVideo = null
            )
        }
    }


    /**
     * Triggered when user clicks "Download for Offline" in the popup or Admin panel.
     */
    fun confirmDownloadVideoForOffline(item: PlaylistItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloadingOffline = true,
                offlineDownloadSuccessMessage = null,
                offlineDownloadErrorMessage = null,
                offlineDownloadProgressPercent = 10
            )
            val ok = repository.downloadVideoForOffline(item) { pct ->
                _uiState.value = _uiState.value.copy(offlineDownloadProgressPercent = pct)
            }
            if (ok) {
                _uiState.value = _uiState.value.copy(
                    isDownloadingOffline = false,
                    offlineDownloadProgressPercent = 100,
                    offlineDownloadSuccessMessage = "✔ Video Saved for Offline! Ready to play without internet.",
                    offlineDownloadErrorMessage = null,
                    cacheSizeBytes = localStore.getCachedStorageSizeBytes()
                )
                delay(1500L)
                _uiState.value = _uiState.value.copy(
                    pendingOfflinePopupVideo = null,
                    offlineDownloadSuccessMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isDownloadingOffline = false,
                    offlineDownloadProgressPercent = 0,
                    offlineDownloadSuccessMessage = null,
                    offlineDownloadErrorMessage = "✖ Download failed or incomplete. Video is NOT saved offline. Please check connection and retry."
                )
            }
        }
    }

    fun promptOfflineDownloadFor(item: PlaylistItem) {
        _uiState.value = _uiState.value.copy(
            pendingOfflinePopupVideo = item,
            offlineDownloadSuccessMessage = null,
            offlineDownloadErrorMessage = null,
            offlineDownloadProgressPercent = 0,
            isDownloadingOffline = false
        )
    }

    fun dismissOfflinePopup() {
        val current = _uiState.value.pendingOfflinePopupVideo
        if (current != null) {
            localStore.markOfflinePopupShown(current.id)
        }
        _uiState.value = _uiState.value.copy(
            pendingOfflinePopupVideo = null,
            isDownloadingOffline = false,
            offlineDownloadSuccessMessage = null,
            offlineDownloadErrorMessage = null,
            offlineDownloadProgressPercent = 0
        )
    }

    fun removeVideoFromPlaylist(videoId: String) {
        viewModelScope.launch {
            repository.removeVideoFromPlaylist(videoId)
            if (!boundFirestoreUserId.isNullOrBlank()) {
                try {
                    val safeDocId = videoId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
                    cloudRepository.deleteVideo(safeDocId)
                } catch (_: Exception) {}
            }
        }
    }


    fun updateTickerText(newTicker: String) {
        viewModelScope.launch {
            repository.updateTickerText(newTicker)
        }
    }

    fun getAdminPin(): String = localStore.getAdminPin()

    fun onOpenAdminClicked() {
        _uiState.value = _uiState.value.copy(showAdminPinDialog = true)
    }

    fun onDismissPinDialog() {
        _uiState.value = _uiState.value.copy(showAdminPinDialog = false)
    }

    fun onPinAuthenticated() {
        loadLogs()
        _uiState.value = _uiState.value.copy(
            showAdminPinDialog = false,
            showAdminSettingsPanel = true,
            cacheSizeBytes = localStore.getCachedStorageSizeBytes()
        )
    }

    fun onCloseAdminPanel() {
        _uiState.value = _uiState.value.copy(showAdminSettingsPanel = false)
    }

    fun toggleDiagnosticOverlay(show: Boolean? = null) {
        val current = _uiState.value.showDiagnosticOverlay
        val target = show ?: !current
        if (target) loadLogs()
        _uiState.value = _uiState.value.copy(showDiagnosticOverlay = target)
    }

    fun toggleKioskLock(locked: Boolean) {
        localStore.setKioskModeLocked(locked)
        _uiState.value = _uiState.value.copy(isKioskLocked = locked)
    }

    fun toggleAutoBoot(enabled: Boolean) {
        localStore.setAutoBootEnabled(enabled)
        _uiState.value = _uiState.value.copy(isAutoBootEnabled = enabled)
    }

    fun togglePlayerMute(muted: Boolean) {
        localStore.setPlayerMuted(muted)
        _uiState.value = _uiState.value.copy(isPlayerMuted = muted)
    }

    fun toggleStartupDelay(enabled: Boolean) {
        localStore.setStartupDelayEnabled(enabled)
        _uiState.value = _uiState.value.copy(isStartupDelayEnabled = enabled)
    }

    fun toggleBurnInProtection(enabled: Boolean) {
        localStore.setBurnInProtectionEnabled(enabled)
        _uiState.value = _uiState.value.copy(isBurnInProtectionEnabled = enabled)
    }

    fun toggleTicker(enabled: Boolean) {
        localStore.setTickerEnabled(enabled)
        _uiState.value = _uiState.value.copy(isTickerEnabled = enabled)
    }

    fun setDisplayOrientation(orientation: com.example.ui.orientation.SignageOrientation) {
        localStore.setDisplayOrientation(orientation.id)
        _uiState.value = _uiState.value.copy(displayOrientation = orientation)
        viewModelScope.launch {
            repository.pushDeviceOrientationSettings(screenOrientation = orientation.id)
            if (!boundFirestoreUserId.isNullOrBlank()) {
                try {
                    cloudRepository.upsertBroadcastConfig(
                        orientation = orientation.id,
                        videoOrientation = when (_uiState.value.videoOrientation.degrees) {
                            90, 270 -> "PORTRAIT"
                            else -> "AUTO"
                        },
                        scaleMode = _uiState.value.videoScaleMode,
                        pushedVideoId = _uiState.value.pushedVideoIdToAutoPlay ?: "",
                        pushedVideoTimestamp = System.currentTimeMillis(),
                        version = (playlistFlow.value.version + 1).toLong()
                    )
                } catch (_: Exception) {}
            }
        }
    }


    fun setVideoOrientation(videoOrientation: com.example.ui.orientation.VideoOrientation) {
        localStore.setVideoOrientation(videoOrientation.degrees)
        _uiState.value = _uiState.value.copy(videoOrientation = videoOrientation)
        viewModelScope.launch {
            repository.pushDeviceOrientationSettings(videoOrientation = videoOrientation.degrees)
        }
    }

    fun setVideoScaleMode(scaleMode: String) {
        localStore.setVideoScaleMode(scaleMode)
        _uiState.value = _uiState.value.copy(videoScaleMode = scaleMode)
        viewModelScope.launch {
            repository.pushDeviceOrientationSettings(scaleMode = scaleMode)
        }
    }

    fun saveApiUrl(url: String) {
        localStore.setApiServerUrl(url)
        _uiState.value = _uiState.value.copy(apiServerUrl = localStore.getApiServerUrl())
    }

    fun triggerCloudSync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(lastSyncStatus = "Syncing...")
            val result = repository.syncPlaylist()
            val now = System.currentTimeMillis()
            val status = if (result.isSuccess) {
                "Synced successfully (${result.getOrNull()?.items?.size ?: 0} items)"
            } else {
                "Sync failed: ${result.exceptionOrNull()?.message}"
            }
            loadLogs()
            _uiState.value = _uiState.value.copy(
                lastSyncStatus = status,
                lastSyncTimestamp = now,
                cacheSizeBytes = localStore.getCachedStorageSizeBytes()
            )
        }
        PlaylistSyncWorker.triggerImmediateSync(getApplication())
    }

    fun clearLocalCache() {
        localStore.cleanupUnusedVideos(emptyList())
        _uiState.value = _uiState.value.copy(
            cacheSizeBytes = localStore.getCachedStorageSizeBytes()
        )
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress != null && addr.hostAddress!!.contains('.')) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
