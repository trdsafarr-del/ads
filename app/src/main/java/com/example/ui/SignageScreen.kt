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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlaylistItem
import com.example.player.SignagePlayerManager
import com.example.player.VideoLoopView
import com.example.ui.admin.AdminPinDialog
import com.example.ui.admin.AdminSettingsPanel
import com.example.ui.components.SignageAutoHideSidebar
import com.example.ui.components.SignageHeaderBar
import com.example.ui.diagnostic.DiagnosticOverlay
import com.example.ui.pairing.DevicePairingScreen
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SignageCard
import com.example.ui.theme.SignageDarkBg
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun SignageScreen(
    viewModel: SignageViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val playlist by viewModel.playlistFlow.collectAsState()

    val playerManager = remember { SignagePlayerManager(context) }

    val isBuffering by playerManager.isBuffering.collectAsState()
    val currentMediaIndex by playerManager.currentMediaIndex.collectAsState()
    val currentMediaId by playerManager.currentMediaId.collectAsState()
    val currentTitle by playerManager.currentTitle.collectAsState()
    val isAdPlaying by playerManager.isAdPlaying.collectAsState()
    val isMuted by playerManager.isMuted.collectAsState()

    var isSidebarOpen by remember { mutableStateOf(false) }
    var sidebarInteractionTick by remember { mutableStateOf(0L) }

    // Apply OS Screen Orientation immediately whenever changed from Web Dashboard or Admin Panel
    LaunchedEffect(uiState.displayOrientation) {
        (context as? Activity)?.let { act ->
            com.example.ui.orientation.SignageOrientation.applyOrientation(act, uiState.displayOrientation)
        }
    }

    // Auto-hide the left sidebar after 5 seconds of inactivity
    LaunchedEffect(isSidebarOpen, sidebarInteractionTick) {
        if (isSidebarOpen) {
            delay(5_000L)
            isSidebarOpen = false
        }
    }

    // Sync playlist changes with player only when device is paired
    LaunchedEffect(playlist, uiState.isPaired) {
        if (uiState.isPaired && playlist.items.isNotEmpty()) {
            playerManager.loadPlaylist(
                items = playlist.items,
                startVideoId = uiState.pushedVideoIdToAutoPlay
            )
            playerManager.play()
        } else if (!uiState.isPaired) {
            playerManager.pause()
        }
    }

    // Immediately interrupt everything, close all overlays/sidebars, and play when a video is pushed/played from Dashboard or Admin
    LaunchedEffect(uiState.pushedVideoIdToAutoPlay) {
        val targetId = uiState.pushedVideoIdToAutoPlay
        if (!targetId.isNullOrBlank() && playlist.items.isNotEmpty()) {
            isSidebarOpen = false
            playerManager.forcePlayImmediately(
                items = playlist.items,
                startVideoId = targetId
            )
            viewModel.onPushedVideoAutoPlayed()
        }
    }

    // Sync remote mute state from Web Dashboard with ExoPlayer
    LaunchedEffect(uiState.isPlayerMuted) {
        playerManager.setMuted(uiState.isPlayerMuted)
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
            isSidebarOpen -> {
                isSidebarOpen = false
            }
            uiState.pendingOfflinePopupVideo != null -> {
                viewModel.dismissOfflinePopup()
            }
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
                    "Kiosk Active: Tap Top-Left Icon or Press MENU+UP+DOWN+OK for diagnostics",
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
        if (!uiState.isPaired) {
            DevicePairingScreen(
                deviceId = uiState.deviceId,
                pairingCode = uiState.pairingCode,
                ipAddress = uiState.ipAddress,
                connectionStatus = uiState.connectionStatus,
                pairingStatusMessage = uiState.pairingStatusMessage,
                isCheckingPairing = uiState.isCheckingPairing,
                onPollPairing = { viewModel.pollPairingStatus() },
                onRegenerateCode = { viewModel.regeneratePairingCode() },
                onOpenAdmin = { viewModel.onOpenAdminClicked() }
            )
        } else {
            // 1. Full-screen Video Loop View with ExoPlayer, Google IMA Ads & Per-Device Screen + Video Orientation
            VideoLoopView(
                playerManager = playerManager,
                scaleMode = uiState.videoScaleMode,
                displayOrientation = uiState.displayOrientation,
                videoOrientationDegrees = uiState.videoOrientation.degrees,
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
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
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

            // 3. Minimal Top Overlay Icons (Compact Sidebar & Admin Icons on Top-Left + Compact Status Dot on Top-Right)
            SignageHeaderBar(
                currentTitle = currentTitle,
                currentIndex = currentMediaIndex,
                totalItems = playlist.items.size,
                isAdPlaying = isAdPlaying,
                isMuted = isMuted,
                connectionStatus = uiState.connectionStatus,
                screenOrientationLabel = uiState.displayOrientation.id,
                videoOrientationDegrees = uiState.videoOrientation.degrees,
                onToggleMute = {
                    val newMuted = playerManager.toggleMute()
                    viewModel.togglePlayerMute(newMuted)
                },
                onOpenAdmin = {
                    isSidebarOpen = false
                    viewModel.onOpenAdminClicked()
                },
                onToggleSidebar = {
                    isSidebarOpen = !isSidebarOpen
                    sidebarInteractionTick = System.currentTimeMillis()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(pixelShiftModifier)
            )

            // 4. Auto-Hide Left Sidebar (All quick signage controls, playlist quick-play, orientation & Admin button)
            SignageAutoHideSidebar(
                visible = isSidebarOpen && !uiState.showAdminSettingsPanel && !uiState.showAdminPinDialog,
                currentTitle = currentTitle,
                currentIndex = currentMediaIndex,
                totalItems = playlist.items.size,
                isAdPlaying = isAdPlaying,
                isMuted = isMuted,
                connectionStatus = uiState.connectionStatus,
                displayOrientation = uiState.displayOrientation,
                videoOrientation = uiState.videoOrientation,
                playlistItems = playlist.items,
                currentVideoId = currentMediaId,
                onUserInteraction = {
                    sidebarInteractionTick = System.currentTimeMillis()
                },
                onCloseSidebar = {
                    isSidebarOpen = false
                },
                onOpenAdminPanel = {
                    isSidebarOpen = false
                    viewModel.onOpenAdminClicked()
                },
                onToggleMute = {
                    val newMuted = playerManager.toggleMute()
                    viewModel.togglePlayerMute(newMuted)
                },
                onSkipPrevious = {
                    playerManager.skipPrevious()
                },
                onTogglePlayPause = {
                    playerManager.togglePlayPause()
                },
                onSkipNext = {
                    playerManager.skipNext()
                },
                onSelectVideoToPlay = { videoId ->
                    viewModel.pushPlayVideoNow(videoId)
                },
                onChangeDisplayOrientation = { mode ->
                    viewModel.setDisplayOrientation(mode)
                },
                onChangeVideoOrientation = { vMode ->
                    viewModel.setVideoOrientation(vMode)
                },
                onTriggerSync = {
                    viewModel.triggerCloudSync()
                },
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        // 5. "Download for Offline" Popup (Automatically shown when a new video is uploaded/added!)
        val popupVideo = uiState.pendingOfflinePopupVideo
        if (popupVideo != null) {
            OfflineDownloadPopup(
                videoItem = popupVideo,
                isDownloading = uiState.isDownloadingOffline,
                progressPercent = uiState.offlineDownloadProgressPercent,
                successMessage = uiState.offlineDownloadSuccessMessage,
                errorMessage = uiState.offlineDownloadErrorMessage,
                onConfirmDownload = { viewModel.confirmDownloadVideoForOffline(popupVideo) },
                onDismiss = { viewModel.dismissOfflinePopup() },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 6. Admin PIN Dialog
        if (uiState.showAdminPinDialog) {
            AdminPinDialog(
                correctPin = viewModel.getAdminPin(),
                onPinSuccess = { viewModel.onPinAuthenticated() },
                onDismiss = { viewModel.onDismissPinDialog() }
            )
        }

        // 7. Admin Settings Slide-Over Drawer
        AnimatedVisibility(
            visible = uiState.showAdminSettingsPanel,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AdminSettingsPanel(
                playlist = playlist,
                deviceId = uiState.deviceId,
                pairingCode = uiState.pairingCode,
                isPaired = uiState.isPaired,
                pairedScreenName = uiState.pairedScreenName,
                pairedAtTimestamp = uiState.pairedAtTimestamp,
                pairingStatusMessage = uiState.pairingStatusMessage,
                onVerifyPairingNow = { viewModel.pollPairingStatus() },
                onRegeneratePairingCode = { viewModel.regeneratePairingCode() },
                onUnpairDevice = { viewModel.unpairDevice() },
                currentOrientation = uiState.displayOrientation,
                onSelectOrientation = { viewModel.setDisplayOrientation(it) },
                currentVideoOrientation = uiState.videoOrientation,
                onSelectVideoOrientation = { viewModel.setVideoOrientation(it) },
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
                onUploadLocalVideoUri = { uri, title, dur, vast ->
                    viewModel.uploadLocalVideoUri(uri, title, dur, vast)
                },
                onPushPlayVideoNow = { videoId ->
                    viewModel.pushPlayVideoNow(videoId)
                },
                onDownloadVideoOffline = { item ->
                    viewModel.promptOfflineDownloadFor(item)
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

        // 8. Hidden Diagnostic Screen Overlay
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

@Composable
private fun OfflineDownloadPopup(
    videoItem: PlaylistItem,
    isDownloading: Boolean,
    progressPercent: Int,
    successMessage: String?,
    errorMessage: String?,
    onConfirmDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(24.dp)
            .testTag("offline_download_popup"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .border(2.dp, if (errorMessage != null) CoralRed else NeonCyan, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SignageCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val iconColor = when {
                    errorMessage != null -> CoralRed
                    successMessage != null -> EmeraldGreen
                    isDownloading -> NeonCyan
                    else -> EmeraldGreen
                }
                val iconVector = when {
                    errorMessage != null -> Icons.Default.ErrorOutline
                    successMessage != null -> Icons.Default.CheckCircle
                    else -> Icons.Default.CloudDownload
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(iconColor.copy(alpha = 0.18f), CircleShape)
                        .border(1.5.dp, iconColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = NeonCyan,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = "Download status icon",
                            tint = iconColor,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val badgeText = when {
                    errorMessage != null -> "DOWNLOAD INCOMPLETE"
                    successMessage != null -> "VERIFIED OFFLINE READY"
                    isDownloading -> "DOWNLOADING: $progressPercent%"
                    else -> "OFFLINE PLAYBACK SETUP"
                }
                Box(
                    modifier = Modifier
                        .background(iconColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = iconColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val headingText = when {
                    errorMessage != null -> "Offline Download Failed"
                    successMessage != null -> "Video is Offline Ready!"
                    isDownloading -> "Downloading Video Chunks..."
                    else -> "Download for Offline Playback?"
                }
                Text(
                    text = headingText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = videoItem.title,
                    color = NeonCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = CoralRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else if (successMessage != null) {
                    Text(
                        text = successMessage,
                        color = EmeraldGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Text(
                        text = "Save this video into device storage so it runs 24/7 with zero buffering even when internet is completely disconnected.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isDownloading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { (progressPercent.coerceIn(0, 100) / 100f) },
                            color = NeonCyan,
                            trackColor = Color.DarkGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Downloading video chunks: $progressPercent%...",
                            color = NeonCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val btnColor = if (errorMessage != null) CoralRed else EmeraldGreen
                    val btnText = when {
                        isDownloading -> "Downloading ($progressPercent%)..."
                        errorMessage != null -> "Retry Download"
                        successMessage != null -> "✔ Downloaded"
                        else -> "Download for Offline"
                    }
                    Button(
                        onClick = onConfirmDownload,
                        enabled = !isDownloading && successMessage == null,
                        colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("download_for_offline_button")
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(
                                imageVector = if (errorMessage != null) Icons.Default.CloudDownload else Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = btnText,
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.7f)
                            .height(48.dp)
                            .testTag("dismiss_offline_popup_button")
                    ) {
                        Text(
                            text = if (successMessage != null) "Close" else "Later",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
