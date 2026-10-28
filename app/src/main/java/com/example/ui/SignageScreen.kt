package com.example.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.player.SignagePlayerManager
import com.example.player.VideoLoopView
import com.example.ui.admin.AdminPinDialog
import com.example.ui.admin.AdminSettingsPanel
import com.example.ui.components.SignageHeaderBar
import com.example.ui.components.SignageTicker
import com.example.ui.diagnostic.DiagnosticOverlay
import com.example.ui.pairing.DevicePairingScreen
import com.example.ui.theme.NeonCyan

@Composable
fun SignageScreen(
    viewModel: SignageViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val playlist by viewModel.playlistFlow.collectAsState()

    val playerManager = remember { SignagePlayerManager(context) }

    val isPlaying by playerManager.isPlaying.collectAsState()
    val isBuffering by playerManager.isBuffering.collectAsState()
    val currentMediaIndex by playerManager.currentMediaIndex.collectAsState()
    val currentMediaId by playerManager.currentMediaId.collectAsState()
    val currentTitle by playerManager.currentTitle.collectAsState()
    val isAdPlaying by playerManager.isAdPlaying.collectAsState()
    val isMuted by playerManager.isMuted.collectAsState()

    // Sync playlist changes with player
    LaunchedEffect(playlist) {
        if (playlist.items.isNotEmpty()) {
            playerManager.loadPlaylist(playlist.items)
        }
    }

    // Smart cache eviction & heartbeat trigger on media transition
    LaunchedEffect(Unit) {
        playerManager.onMediaItemChangedListener = { idx, _ ->
            viewModel.onMediaItemAdvanced(idx, playlist)
        }
    }

    // Kiosk Mode Back Press Interceptor
    BackHandler(enabled = true) {
        when {
            uiState.showDiagnosticOverlay -> {
                viewModel.toggleDiagnosticOverlay(false)
            }
            uiState.showAdminSettingsPanel -> {
                viewModel.onCloseAdminPanel()
            }
            uiState.showAdminPinDialog -> {
                viewModel.onDismissPinDialog()
            }
            uiState.isKioskLocked -> {
                Toast.makeText(
                    context,
                    "Kiosk Active: Hold MENU or Press MENU+UP+DOWN+OK for diagnostics",
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> {
                (context as? Activity)?.finish()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("signage_root_screen")
    ) {
        // If device is not paired yet, display the 6-digit Device Pairing Screen
        if (!uiState.isPaired) {
            DevicePairingScreen(
                deviceId = uiState.deviceId,
                pairingCode = uiState.pairingCode,
                ipAddress = uiState.ipAddress,
                onPollPairing = { viewModel.pollPairingStatus() },
                onSkipToStandaloneDemo = { viewModel.skipPairingForDemo() },
                onOpenAdmin = { viewModel.onOpenAdminClicked() }
            )
        } else {
            // 1. Full-screen Video Loop View with ExoPlayer & Google IMA Ads
            VideoLoopView(
                playerManager = playerManager,
                scaleMode = uiState.videoScaleMode,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Buffering Indicator
            AnimatedVisibility(
                visible = isBuffering,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = NeonCyan,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                }
            }

            // Burn-in pixel shifting offset for static overlays
            val pixelShiftModifier = Modifier.offset {
                IntOffset(
                    (uiState.burnInShiftX * density).toInt(),
                    (uiState.burnInShiftY * density).toInt()
                )
            }

            // 3. Top Signage Overlay Header Bar (with pixel shift)
            SignageHeaderBar(
                currentTitle = currentTitle,
                currentIndex = currentMediaIndex,
                totalItems = playlist.items.size,
                isAdPlaying = isAdPlaying,
                isMuted = isMuted,
                onToggleMute = { playerManager.toggleMute() },
                onOpenAdmin = { viewModel.onOpenAdminClicked() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(pixelShiftModifier)
            )

            // 4. Bottom News Ticker (with pixel shift)
            if (uiState.isTickerEnabled) {
                val tickerText = playlist.tickerText ?: "★ OmniSign Digital TV Signage System Active ★"
                SignageTicker(
                    text = tickerText,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(pixelShiftModifier)
                )
            }
        }

        // 5. Admin PIN Dialog
        if (uiState.showAdminPinDialog) {
            AdminPinDialog(
                correctPin = viewModel.getAdminPin(),
                onPinSuccess = { viewModel.onPinAuthenticated() },
                onDismiss = { viewModel.onDismissPinDialog() }
            )
        }

        // 6. Admin Settings Slide-Over Drawer
        AnimatedVisibility(
            visible = uiState.showAdminSettingsPanel,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AdminSettingsPanel(
                playlist = playlist,
                currentOrientation = uiState.displayOrientation,
                onSelectOrientation = { viewModel.setDisplayOrientation(it) },
                currentScaleMode = uiState.videoScaleMode,
                onSelectScaleMode = { viewModel.setVideoScaleMode(it) },
                isKioskLocked = uiState.isKioskLocked,
                onToggleKioskLock = { viewModel.toggleKioskLock(it) },
                isAutoBootEnabled = uiState.isAutoBootEnabled,
                onToggleAutoBoot = { viewModel.toggleAutoBoot(it) },
                isTickerEnabled = uiState.isTickerEnabled,
                onToggleTicker = { viewModel.toggleTicker(it) },
                apiServerUrl = uiState.apiServerUrl,
                onSaveApiUrl = { viewModel.saveApiUrl(it) },
                onTriggerSync = { viewModel.triggerCloudSync() },
                lastSyncTime = uiState.lastSyncTimestamp,
                lastSyncStatus = uiState.lastSyncStatus,
                cacheSizeBytes = uiState.cacheSizeBytes,
                onClearCache = { viewModel.clearLocalCache() },
                onAddVideoToPlaylist = { title, url, dur, vast ->
                    viewModel.addVideoToPlaylist(title, url, dur, vast)
                },
                onRemoveVideoFromPlaylist = { videoId ->
                    viewModel.removeVideoFromPlaylist(videoId)
                },
                onUpdateTickerText = { ticker ->
                    viewModel.updateTickerText(ticker)
                },
                onExitKioskApp = {
                    (context as? Activity)?.finish()
                },
                onClose = { viewModel.onCloseAdminPanel() }
            )
        }

        // 7. Hidden Diagnostic Screen Overlay
        if (uiState.showDiagnosticOverlay) {
            DiagnosticOverlay(
                deviceId = uiState.deviceId,
                ipAddress = uiState.ipAddress,
                lastSyncTime = uiState.lastSyncTimestamp,
                lastSyncStatus = uiState.lastSyncStatus,
                cacheSizeBytes = uiState.cacheSizeBytes,
                appVersion = "1.0",
                currentVideoId = currentMediaId,
                recentLogs = uiState.recentLogs,
                onTriggerSync = { viewModel.triggerCloudSync() },
                onDismiss = { viewModel.toggleDiagnosticOverlay(false) }
            )
        }
    }
}
