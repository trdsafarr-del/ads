package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.db.DeviceLogEntity
import com.example.data.model.PlaylistResponse
import com.example.data.repository.SignageRepository
import com.example.data.repository.SyncState
import com.example.pairing.PairingManager
import com.example.pairing.PairingState
import com.example.sync.PlaylistSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.Collections

data class SignageUiState(
    val isKioskLocked: Boolean = true,
    val isAutoBootEnabled: Boolean = true,
    val isTickerEnabled: Boolean = true,
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
    val ipAddress: String = "127.0.0.1",
    val recentLogs: List<DeviceLogEntity> = emptyList(),
    val burnInShiftX: Float = 0f,
    val burnInShiftY: Float = 0f,
    val displayOrientation: com.example.ui.orientation.SignageOrientation = com.example.ui.orientation.SignageOrientation.LANDSCAPE,
    val videoScaleMode: String = "ZOOM"
)

class SignageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SignageRepository(application)
    private val localStore = repository.localStore
    private val pairingManager = repository.pairingManager
    private val database = repository.database

    val playlistFlow: StateFlow<PlaylistResponse> = repository.playlistFlow
    val syncState: StateFlow<SyncState> = repository.syncState

    private val _uiState = MutableStateFlow(
        SignageUiState(
            isKioskLocked = localStore.isKioskModeLocked(),
            isAutoBootEnabled = localStore.isAutoBootEnabled(),
            isTickerEnabled = localStore.isTickerEnabled(),
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
            videoScaleMode = localStore.getVideoScaleMode()
        )
    )
    val uiState: StateFlow<SignageUiState> = _uiState.asStateFlow()

    init {
        observePairing()
        loadLogs()
        startBurnInPixelShiftTimer()
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

    private fun startBurnInPixelShiftTimer() {
        viewModelScope.launch {
            // Shift static overlay pixels every 5 minutes (±3dp)
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
        viewModelScope.launch {
            repository.smartCacheEvict(currentIndex, currentPlaylist)
            val action = repository.sendDeviceHeartbeat(currentPlaylist.items.getOrNull(currentIndex)?.id)
            if (!action.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    displayOrientation = com.example.ui.orientation.SignageOrientation.fromId(localStore.getDisplayOrientation()),
                    videoScaleMode = localStore.getVideoScaleMode(),
                    cacheSizeBytes = localStore.getCachedStorageSizeBytes(),
                    lastSyncStatus = localStore.getLastSyncStatus(),
                    lastSyncTimestamp = localStore.getLastSyncTimestamp()
                )
            }
        }
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

    fun addVideoToPlaylist(title: String, videoUrl: String, durationSeconds: Int, enableVastAd: Boolean) {
        viewModelScope.launch {
            repository.addVideoToPlaylist(title, videoUrl, durationSeconds, enableVastAd)
        }
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
