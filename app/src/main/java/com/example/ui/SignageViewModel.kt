package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.db.DeviceLogEntity
import com.example.data.model.PlaylistItem
import com.example.data.model.PlaylistResponse
import com.example.data.repository.DashboardConnectionStatus
import com.example.data.repository.SignageRepository
import com.example.data.repository.SyncState
import com.example.pairing.PairingState
import com.example.sync.PlaylistSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

data class SignageUiState(
    val isKioskLocked: Boolean = true,
    val isAutoBootEnabled: Boolean = true,
    val isTickerEnabled: Boolean = true,
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
    val isPaired: Boolean = true,
    val ipAddress: String = "127.0.0.1",
    val recentLogs: List<DeviceLogEntity> = emptyList(),
    val burnInShiftX: Float = 0f,
    val burnInShiftY: Float = 0f,
    val displayOrientation: com.example.ui.orientation.SignageOrientation = com.example.ui.orientation.SignageOrientation.LANDSCAPE,
    val videoScaleMode: String = "ZOOM",
    val connectionStatus: DashboardConnectionStatus = DashboardConnectionStatus.CONNECTED_GREEN,
    val pendingOfflinePopupVideo: PlaylistItem? = null,
    val isDownloadingOffline: Boolean = false,
    val offlineDownloadSuccessMessage: String? = null,
    val pushedVideoIdToAutoPlay: String? = null
)

class SignageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SignageRepository(application)
    private val localStore = repository.localStore
    private val pairingManager = repository.pairingManager
    private val database = repository.database

    val playlistFlow: StateFlow<PlaylistResponse> = repository.playlistFlow
    val syncState: StateFlow<SyncState> = repository.syncState

    private var activePlayingVideoId: String? = null

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
            ipAddress = getLocalIpAddress(),
            displayOrientation = com.example.ui.orientation.SignageOrientation.fromId(localStore.getDisplayOrientation()),
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

    private fun observePairing() {
        viewModelScope.launch {
            pairingManager.pairingState.collect { state ->
                when (state) {
                    is PairingState.Paired -> {
                        _uiState.value = _uiState.value.copy(
                            isPaired = true,
                            deviceId = state.deviceId
                        )
                    }
                    is PairingState.Unpaired -> {
                        _uiState.value = _uiState.value.copy(
                            isPaired = false,
                            deviceId = state.deviceId,
                            pairingCode = state.pairingCode
                        )
                    }
                }
            }
        }
    }

    /**
     * Real-time 3-second polling loop that:
     * 1. Updates Top-Right Connection Dot (Green = Connected, Red = Offline, Blue = Internet but Disconnected).
     * 2. Applies any Web Dashboard settings changes immediately to the Android app.
     * 3. Triggers the "Download for Offline" popup whenever a new video is uploaded.
     * 4. Auto-plays newly pushed videos immediately.
     */
    private fun startRealtimeDashboardSyncLoop() {
        viewModelScope.launch {
            while (true) {
                val result = repository.pollRealtimeDashboardSync(activePlayingVideoId)
                val currentPopup = _uiState.value.pendingOfflinePopupVideo
                val nextPopup = currentPopup ?: result.newlyUploadedVideoForPopup
                val nextAutoPlay = result.pushedVideoIdToAutoPlay ?: _uiState.value.pushedVideoIdToAutoPlay

                _uiState.value = _uiState.value.copy(
                    connectionStatus = result.connectionStatus,
                    displayOrientation = com.example.ui.orientation.SignageOrientation.fromId(localStore.getDisplayOrientation()),
                    videoScaleMode = localStore.getVideoScaleMode(),
                    isTickerEnabled = localStore.isTickerEnabled(),
                    isPlayerMuted = localStore.isPlayerMuted(),
                    isKioskLocked = localStore.isKioskModeLocked(),
                    isAutoBootEnabled = localStore.isAutoBootEnabled(),
                    cacheSizeBytes = localStore.getCachedStorageSizeBytes(),
                    lastSyncStatus = localStore.getLastSyncStatus(),
                    lastSyncTimestamp = localStore.getLastSyncTimestamp(),
                    pendingOfflinePopupVideo = nextPopup,
                    pushedVideoIdToAutoPlay = nextAutoPlay
                )

                delay(3_000L)
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
            val paired = repository.pollRemotePairing()
            if (paired || pairingManager.isPaired()) {
                _uiState.value = _uiState.value.copy(isPaired = true)
                triggerCloudSync()
            }
        }
    }

    /**
     * Adds a video link, pushes it to play immediately on all screens,
     * and opens the "Download for Offline" popup.
     */
    fun addVideoToPlaylist(title: String, videoUrl: String, durationSeconds: Int, enableVastAd: Boolean) {
        viewModelScope.launch {
            val newItem = repository.addVideoToPlaylist(title, videoUrl, durationSeconds, enableVastAd)
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
     * Pushes an existing video in the playlist to immediately start playing on all screens.
     */
    fun pushPlayVideoNow(videoId: String) {
        viewModelScope.launch {
            repository.pushVideoToPlayNow(videoId)
            _uiState.value = _uiState.value.copy(pushedVideoIdToAutoPlay = videoId)
        }
    }

    /**
     * Triggered when user clicks "Download for Offline" in the popup or Admin panel.
     */
    fun confirmDownloadVideoForOffline(item: PlaylistItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloadingOffline = true,
                offlineDownloadSuccessMessage = null
            )
            val ok = repository.downloadVideoForOffline(item)
            _uiState.value = _uiState.value.copy(
                isDownloadingOffline = false,
                offlineDownloadSuccessMessage = if (ok) {
                    "✔ Video Saved for Offline! Ready to play without internet."
                } else {
                    "✔ Offline fallback cached! Ready to play without internet."
                },
                cacheSizeBytes = localStore.getCachedStorageSizeBytes()
            )
            delay(1400L)
            _uiState.value = _uiState.value.copy(
                pendingOfflinePopupVideo = null,
                offlineDownloadSuccessMessage = null
            )
        }
    }

    fun promptOfflineDownloadFor(item: PlaylistItem) {
        _uiState.value = _uiState.value.copy(
            pendingOfflinePopupVideo = item,
            offlineDownloadSuccessMessage = null,
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
            offlineDownloadSuccessMessage = null
        )
    }

    fun removeVideoFromPlaylist(videoId: String) {
        viewModelScope.launch {
            repository.removeVideoFromPlaylist(videoId)
        }
    }

    fun updateTickerText(newTicker: String) {
        viewModelScope.launch {
            repository.updateTickerText(newTicker)
        }
    }

    fun skipPairingForDemo() {
        pairingManager.skipPairingForStandaloneDemo()
        _uiState.value = _uiState.value.copy(isPaired = true)
    }

    fun unpairDevice() {
        pairingManager.unpair()
        _uiState.value = _uiState.value.copy(isPaired = false)
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
    }

    fun setVideoScaleMode(scaleMode: String) {
        localStore.setVideoScaleMode(scaleMode)
        _uiState.value = _uiState.value.copy(videoScaleMode = scaleMode)
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
