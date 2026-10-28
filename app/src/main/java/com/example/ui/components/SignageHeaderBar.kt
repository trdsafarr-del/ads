package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlaylistItem
import com.example.data.repository.DashboardConnectionStatus
import com.example.ui.orientation.SignageOrientation
import com.example.ui.orientation.VideoOrientation
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SignageCard
import com.example.ui.theme.SignageDarkBg
import com.example.ui.theme.SignageSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal, unobtrusive top overlay icons during full-screen video playback:
 * - Top-Left: Sleek compact circular icon button to open the Auto-Hide Left Sidebar + compact Admin icon.
 * - Top-Right: Sleek compact circular status dot icon (Green = Connected, Red = Offline, Blue = Disconnected)
 *   without any bulky text box blocking the video.
 */
@Composable
fun SignageHeaderBar(
    currentTitle: String,
    currentIndex: Int,
    totalItems: Int,
    isAdPlaying: Boolean,
    isMuted: Boolean,
    connectionStatus: DashboardConnectionStatus,
    screenOrientationLabel: String = "LANDSCAPE",
    videoOrientationDegrees: Int = 0,
    onToggleMute: () -> Unit,
    onOpenAdmin: () -> Unit,
    onToggleSidebar: () -> Unit = onOpenAdmin,
    modifier: Modifier = Modifier
) {
    val (dotColor, statusDescription) = when (connectionStatus) {
        DashboardConnectionStatus.CONNECTED_GREEN -> Pair(EmeraldGreen, "Connected to Dashboard")
        DashboardConnectionStatus.OFFLINE_RED -> Pair(CoralRed, "Offline Mode")
        DashboardConnectionStatus.DISCONNECTED_BLUE -> Pair(ElectricBlue, "Internet On, Dashboard Disconnected")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("signage_header_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP-LEFT: Compact circular icon buttons (Sidebar Menu + Direct Admin Icon, no bulky text)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onToggleSidebar,
                modifier = Modifier
                    .size(48.dp)
                    .background(SignageDarkBg.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, NeonCyan.copy(alpha = 0.45f), CircleShape)
                    .testTag("sidebar_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Controls Sidebar",
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenAdmin,
                modifier = Modifier
                    .size(48.dp)
                    .background(SignageDarkBg.copy(alpha = 0.45f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .testTag("admin_panel_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = "Open Admin Panel",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // TOP-RIGHT: Compact Circular Connection Status Dot Icon (Green / Red / Blue)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onToggleSidebar() }
                .testTag("connection_status_indicator"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(SignageDarkBg.copy(alpha = 0.65f), CircleShape)
                    .border(1.5.dp, dotColor.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor, CircleShape)
                        .testTag("connection_status_dot")
                )
            }
        }
    }
}

/**
 * Auto-Hide Left Sidebar housing all quick signage controls, playlist quick-play,
 * Screen & Video Orientation toggles, audio mute, and Admin Panel launcher.
 * Automatically hides after 5 seconds of inactivity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignageAutoHideSidebar(
    visible: Boolean,
    currentTitle: String,
    currentIndex: Int,
    totalItems: Int,
    isAdPlaying: Boolean,
    isMuted: Boolean,
    connectionStatus: DashboardConnectionStatus,
    displayOrientation: SignageOrientation,
    videoOrientation: VideoOrientation,
    playlistItems: List<PlaylistItem>,
    currentVideoId: String,
    onUserInteraction: () -> Unit,
    onCloseSidebar: () -> Unit,
    onOpenAdminPanel: () -> Unit,
    onToggleMute: () -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectVideoToPlay: (String) -> Unit,
    onChangeDisplayOrientation: (SignageOrientation) -> Unit,
    onChangeVideoOrientation: (VideoOrientation) -> Unit,
    onTriggerSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEE, MMM dd", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTimeString = timeFormat.format(now)
            currentDateString = dateFormat.format(now)
            delay(1000L)
        }
    }

    val (dotColor, statusLabel) = when (connectionStatus) {
        DashboardConnectionStatus.CONNECTED_GREEN -> Pair(EmeraldGreen, "Connected")
        DashboardConnectionStatus.OFFLINE_RED -> Pair(CoralRed, "Offline")
        DashboardConnectionStatus.DISCONNECTED_BLUE -> Pair(ElectricBlue, "Disconnected")
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(330.dp)
                .background(SignageDarkBg.copy(alpha = 0.94f))
                .border(
                    width = 1.dp,
                    color = NeonCyan.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                )
                .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                .clickable(enabled = true, onClick = { onUserInteraction() })
                .padding(16.dp)
                .testTag("auto_hide_sidebar")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Sidebar Header: Title, Clock & Close Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(dotColor, CircleShape)
                            )
                            Text(
                                text = "SignageTV • $statusLabel",
                                color = dotColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(
                            text = "$currentTimeString  •  $currentDateString",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onCloseSidebar,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .testTag("close_sidebar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Sidebar",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 2. Full Admin & Pairing Settings Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonCyan.copy(alpha = 0.18f))
                        .border(1.dp, NeonCyan.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        .clickable {
                            onCloseSidebar()
                            onOpenAdminPanel()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("sidebar_open_admin_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = "Admin Settings",
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Full Admin & Pairing Settings",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // 3. Now Playing & Media Transport Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SignageCard, RoundedCornerShape(12.dp))
                        .border(1.dp, SignageSurfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val badgeColor = if (isAdPlaying) AmberAccent else EmeraldGreen
                        val badgeText = if (isAdPlaying) "VAST AD" else "ON AIR"
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Text(
                            text = "Video ${currentIndex + 1}/${totalItems.coerceAtLeast(1)}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = currentTitle.ifBlank { "Signage Broadcast Loop" },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onSkipPrevious()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .testTag("sidebar_prev_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Video",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onTogglePlayPause()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(EmeraldGreen.copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, EmeraldGreen, CircleShape)
                                .testTag("sidebar_play_pause_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play / Pause",
                                tint = EmeraldGreen
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onSkipNext()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .testTag("sidebar_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next Video",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onToggleMute()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (isMuted) CoralRed.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                                    CircleShape
                                )
                                .testTag("mute_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                contentDescription = if (isMuted) "Unmute Audio" else "Mute Audio",
                                tint = if (isMuted) CoralRed else Color.White
                            )
                        }
                    }
                }

                // 4. Quick Play Playlist (Tap any video to Play Immediately)
                if (playlistItems.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SignageCard, RoundedCornerShape(12.dp))
                            .border(1.dp, SignageSurfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "QUICK PLAY VIDEOS (${playlistItems.size})",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        playlistItems.forEachIndexed { idx, item ->
                            val isPlayingThis = item.id == currentVideoId || (currentVideoId.isBlank() && idx == currentIndex)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isPlayingThis) EmeraldGreen.copy(alpha = 0.16f)
                                        else Color.White.copy(alpha = 0.04f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isPlayingThis) EmeraldGreen.copy(alpha = 0.6f)
                                        else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        onCloseSidebar()
                                        onSelectVideoToPlay(item.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("sidebar_play_video_${item.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${idx + 1}. ${item.title}",
                                    color = if (isPlayingThis) EmeraldGreen else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPlayingThis) "PLAYING" else "▶ PLAY",
                                    color = if (isPlayingThis) EmeraldGreen else NeonCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                // 5. Screen Orientation & Video Orientation Quick Switch
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SignageCard, RoundedCornerShape(12.dp))
                        .border(1.dp, SignageSurfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SCREEN ORIENTATION: ${displayOrientation.shortLabel}",
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SignageOrientation.entries.forEach { mode ->
                            val selected = displayOrientation == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) NeonCyan.copy(alpha = 0.22f)
                                        else Color.White.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) NeonCyan else Color.White.copy(alpha = 0.15f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        onUserInteraction()
                                        onChangeDisplayOrientation(mode)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = mode.shortLabel,
                                    color = if (selected) NeonCyan else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "VIDEO ORIENTATION: ${videoOrientation.degrees}°",
                        color = EmeraldGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VideoOrientation.entries.forEach { vMode ->
                            val selected = videoOrientation == vMode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) EmeraldGreen.copy(alpha = 0.22f)
                                        else Color.White.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) EmeraldGreen else Color.White.copy(alpha = 0.15f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        onUserInteraction()
                                        onChangeVideoOrientation(vMode)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${vMode.degrees}°",
                                    color = if (selected) EmeraldGreen else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 6. Cloud Sync Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElectricBlue.copy(alpha = 0.2f))
                        .border(1.dp, ElectricBlue.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .clickable {
                            onUserInteraction()
                            onTriggerSync()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("sidebar_sync_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Cloud Sync Now",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Sync with Web Dashboard Now",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Auto-hides after 5 seconds of inactivity",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
