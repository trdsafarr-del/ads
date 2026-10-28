package com.example.ui.admin

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.SignageLocalStore
import com.example.data.model.PlaylistResponse
import com.example.kiosk.KioskPermissionHelper
import com.example.ui.orientation.SignageOrientation
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SignageCard
import com.example.ui.theme.SignageDarkBg
import com.example.ui.theme.SignageSurface
import com.example.ui.theme.SignageSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminSettingsPanel(
    playlist: PlaylistResponse,
    currentOrientation: SignageOrientation,
    onSelectOrientation: (SignageOrientation) -> Unit,
    currentScaleMode: String,
    onSelectScaleMode: (String) -> Unit,
    isKioskLocked: Boolean,
    onToggleKioskLock: (Boolean) -> Unit,
    isAutoBootEnabled: Boolean,
    onToggleAutoBoot: (Boolean) -> Unit,
    isTickerEnabled: Boolean,
    onToggleTicker: (Boolean) -> Unit,
    apiServerUrl: String,
    onSaveApiUrl: (String) -> Unit,
    onTriggerSync: () -> Unit,
    lastSyncTime: Long,
    lastSyncStatus: String,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    onAddVideoToPlaylist: (title: String, url: String, durationSec: Int, enableVast: Boolean) -> Unit = { _, _, _, _ -> },
    onRemoveVideoFromPlaylist: (videoId: String) -> Unit = {},
    onUpdateTickerText: (String) -> Unit = {},
    onExitKioskApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var editingUrl by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var urlSavedMessage by remember { mutableStateOf(false) }

    // In-app playlist & ticker editor states
    var newVideoTitle by remember { mutableStateOf("") }
    var newVideoUrl by remember { mutableStateOf("") }
    var newVideoEnableVast by remember { mutableStateOf(true) }
    var editingTicker by remember(playlist.tickerText) {
        mutableStateOf(playlist.tickerText ?: "★ LIVE DIGITAL SIGNAGE ★")
    }

    // Refresh counter triggered whenever user returns from Android Settings
    var permissionRefreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isDefaultLauncher = remember(permissionRefreshTrigger) {
        KioskPermissionHelper.isDefaultHomeLauncher(context)
    }
    val isDeviceAdmin = remember(permissionRefreshTrigger) {
        KioskPermissionHelper.isDeviceAdminActive(context)
    }
    val isAccessibilityEnabled = remember(permissionRefreshTrigger) {
        KioskPermissionHelper.isAccessibilityServiceEnabled(context)
    }
    val hasOverlayPermission = remember(permissionRefreshTrigger) {
        KioskPermissionHelper.isOverlayPermissionGranted(context)
    }
    val isBatteryUnrestricted = remember(permissionRefreshTrigger) {
        KioskPermissionHelper.isIgnoringBatteryOptimizations(context)
    }

    // RoleManager HOME role request launcher (Android 10+)
    val homeRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permissionRefreshTrigger++
        // If still not default launcher after RoleManager result (e.g., OEM TV ignored RoleManager),
        // force the Home Chooser dialog or open Default Home Settings directly.
        if (!KioskPermissionHelper.isDefaultHomeLauncher(context)) {
            KioskPermissionHelper.forceShowDefaultLauncherPickerOrSettings(context)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 350.dp, max = 600.dp)
            .fillMaxWidth(0.94f)
            .background(SignageDarkBg.copy(alpha = 0.98f))
            .border(1.dp, SignageSurfaceVariant)
            .padding(20.dp)
            .testTag("admin_settings_panel")
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Admin Settings",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Signage 24x7 Control Center",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Default Launcher • Device Admin • Accessibility • Cloud CMS",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("close_admin_panel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // SECTION 1: 24x7 KIOSK PERMISSIONS & DIRECT SETTINGS REDIRECTS
                item {
                    AdminSectionCard(title = "24x7 Kiosk Permissions & Default Launcher Setup") {
                        Text(
                            text = "Tap any button below to directly open Android Settings or set Default Launcher for uninterrupted 24x7 operation:",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // 1. Default HOME Launcher
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SignageSurface, RoundedCornerShape(10.dp))
                                .border(
                                    1.dp,
                                    if (isDefaultLauncher) EmeraldGreen.copy(alpha = 0.5f) else NeonCyan.copy(alpha = 0.5f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = null,
                                        tint = if (isDefaultLauncher) EmeraldGreen else NeonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Default HOME Launcher",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (isDefaultLauncher)
                                                "ACTIVE: SignageTV is set as your Default Home Launcher!"
                                            else
                                                "Not Default yet. Click 'Set Default Launcher' or 'Home Settings' to select SignageTV.",
                                            color = if (isDefaultLauncher) EmeraldGreen else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val roleIntent = KioskPermissionHelper.createHomeRoleIntent(context)
                                        if (roleIntent != null) {
                                            try {
                                                homeRoleLauncher.launch(roleIntent)
                                            } catch (_: Exception) {
                                                KioskPermissionHelper.forceShowDefaultLauncherPickerOrSettings(context)
                                            }
                                        } else {
                                            KioskPermissionHelper.forceShowDefaultLauncherPickerOrSettings(context)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("set_default_launcher_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Set Default Launcher",
                                        fontSize = 11.sp,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        KioskPermissionHelper.openDefaultHomeSettingsDirectly(context)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("open_home_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Open Home Settings",
                                        fontSize = 11.sp,
                                        color = NeonCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 2. Device Admin App (24x7 Kiosk Lock & Uninstall Protection)
                        PermissionRedirectRow(
                            icon = Icons.Default.Security,
                            title = "Device Admin App (24x7 Lock)",
                            subtitle = if (isDeviceAdmin)
                                "Enabled: Device Admin active for 24x7 kiosk protection"
                            else
                                "Tap to activate Device Admin privilege in Android Settings",
                            isGranted = isDeviceAdmin,
                            buttonText = if (isDeviceAdmin) "Admin Settings" else "Enable Admin",
                            testTag = "enable_device_admin_button",
                            onClick = {
                                KioskPermissionHelper.openDeviceAdminActivationSettings(context)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. Accessibility Service (24x7 Auto-Recovery Guard)
                        PermissionRedirectRow(
                            icon = Icons.Default.AccessibilityNew,
                            title = "Accessibility Service (24x7 Guard)",
                            subtitle = if (isAccessibilityEnabled)
                                "Active: Auto-recovers SignageTV to foreground 24x7"
                            else
                                "Tap to open Accessibility Settings & enable SignageTV",
                            isGranted = isAccessibilityEnabled,
                            buttonText = if (isAccessibilityEnabled) "Configured" else "Open Accessibility",
                            testTag = "open_accessibility_settings_button",
                            onClick = {
                                KioskPermissionHelper.openAccessibilitySettingsDirectly(context)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 4. System Overlay Permission (Appear on Top for Android 10+ Boot)
                        PermissionRedirectRow(
                            icon = Icons.Default.Layers,
                            title = "System Overlay (Display Over Apps)",
                            subtitle = if (hasOverlayPermission)
                                "Granted: Allows instant auto-start on Android 10+ / TCL TVs"
                            else
                                "Tap to open Overlay Permission Settings directly",
                            isGranted = hasOverlayPermission,
                            buttonText = if (hasOverlayPermission) "Overlay Settings" else "Grant Overlay",
                            testTag = "open_overlay_settings_button",
                            onClick = {
                                KioskPermissionHelper.openOverlayPermissionSettingsDirectly(context)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 5. Unrestricted Battery (24x7 Doze Exemption)
                        PermissionRedirectRow(
                            icon = Icons.Default.BatteryChargingFull,
                            title = "24x7 Unrestricted Battery",
                            subtitle = if (isBatteryUnrestricted)
                                "Active: OS will not kill SignageTV in background"
                            else
                                "Tap to disable battery optimization for 24x7 playback",
                            isGranted = isBatteryUnrestricted,
                            buttonText = if (isBatteryUnrestricted) "Unrestricted" else "Allow 24x7",
                            testTag = "allow_battery_button",
                            onClick = {
                                KioskPermissionHelper.openBatteryOptimizationSettingsDirectly(context)
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = SignageSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Auto-boot toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Start on Boot / Power-On",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "BOOT_COMPLETED + Foreground Service + Watchdog",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isAutoBootEnabled,
                                onCheckedChange = onToggleAutoBoot,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = NeonCyan
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Kiosk Mode Back Button Lock
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kiosk Back Button Lock",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isKioskLocked) "Locked: Back button disabled in Kiosk mode" else "Unlocked: Back button allows exiting",
                                    color = if (isKioskLocked) EmeraldGreen else AmberAccent,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isKioskLocked,
                                onCheckedChange = onToggleKioskLock,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = NeonCyan
                                )
                            )
                        }
                    }
                }

                // SECTION 2: DISPLAY ORIENTATION (LANDSCAPE & PORTRAIT)
                item {
                    AdminSectionCard(title = "Display Orientation (Landscape / Portrait)") {
                        Text(
                            text = "Select screen rotation (Supports Horizontal 16:9 TV & Vertical 9:16 Totem):",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SignageOrientation.entries.forEach { orientation ->
                                val isSelected = (orientation == currentOrientation)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) NeonCyan.copy(alpha = 0.15f) else SignageSurface,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) NeonCyan else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onSelectOrientation(orientation) }
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (orientation == SignageOrientation.PORTRAIT || orientation == SignageOrientation.REVERSE_PORTRAIT)
                                                        Icons.Default.StayCurrentPortrait
                                                    else Icons.Default.StayCurrentLandscape,
                                                    contentDescription = null,
                                                    tint = if (isSelected) NeonCyan else TextSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = orientation.title,
                                                    color = if (isSelected) NeonCyan else Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                )
                                            }
                                            Text(
                                                text = orientation.description,
                                                color = TextMuted,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                                            )
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Selected",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Video Scaling on Vertical/Horizontal Screen:",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("ZOOM", "Fill & Crop", "No black bars"),
                                Triple("FIT", "Fit Center", "Preserves 16:9/9:16"),
                                Triple("FILL", "Stretch", "Fills 100%")
                            ).forEach { (mode, label, desc) ->
                                val isSelected = currentScaleMode.equals(mode, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (isSelected) ElectricBlue.copy(alpha = 0.25f) else SignageSurface,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) ElectricBlue else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onSelectScaleMode(mode) }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) ElectricBlue else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(text = desc, color = TextMuted, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // SECTION 3: VERCEL CLOUD CMS DASHBOARD & SYNC (https://ads-broo.vercel.app/)
                item {
                    AdminSectionCard(title = "Vercel Cloud CMS & Remote Sync (ads-broo.vercel.app)") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ElectricBlue.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(1.dp, ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Web Dashboard Connected",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = SignageLocalStore.DEFAULT_API_URL,
                                        color = NeonCyan,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        val browserIntent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(SignageLocalStore.DEFAULT_API_URL)
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(browserIntent)
                                    } catch (_: Exception) {}
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Dashboard", fontSize = 11.sp, color = NeonCyan)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Backend REST API Endpoint",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = editingUrl,
                                onValueChange = { editingUrl = it; urlSavedMessage = false },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SignageSurface,
                                    unfocusedContainerColor = SignageSurface,
                                    focusedIndicatorColor = NeonCyan,
                                    unfocusedIndicatorColor = SignageSurfaceVariant
                                ),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onSaveApiUrl(editingUrl)
                                    urlSavedMessage = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) {
                                Text("Save", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (urlSavedMessage) {
                            Text(
                                text = "API URL saved & synced with https://ads-broo.vercel.app/",
                                color = EmeraldGreen,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Sync Status & Trigger
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Status: $lastSyncStatus",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                val syncDateStr = if (lastSyncTime > 0) {
                                    SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTime))
                                } else "Never"
                                Text(
                                    text = "Last synced: $syncDateStr",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = onTriggerSync,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier.testTag("trigger_sync_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync Now", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Local Storage Cache Info
                        val cacheMb = cacheSizeBytes / (1024f * 1024f)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Local Video Cache",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Storage used: ${"%.1f".format(cacheMb)} MB",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            OutlinedButton(
                                onClick = onClearCache,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = CoralRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Purge", fontSize = 11.sp, color = CoralRed)
                            }
                        }
                    }
                }

                // SECTION 4: QUICK PLAYLIST & TICKER MANAGER
                item {
                    AdminSectionCard(title = "Playlist & Live Ticker Manager (${playlist.items.size} Videos)") {
                        Text(
                            text = "Add MP4 Video to Loop:",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = newVideoTitle,
                            onValueChange = { newVideoTitle = it },
                            placeholder = { Text("Campaign Title (e.g., Holiday Promo)", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SignageSurface,
                                unfocusedContainerColor = SignageSurface,
                                focusedIndicatorColor = NeonCyan,
                                unfocusedIndicatorColor = SignageSurfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = newVideoUrl,
                            onValueChange = { newVideoUrl = it },
                            placeholder = { Text("Direct MP4 URL (https://.../video.mp4)", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SignageSurface,
                                unfocusedContainerColor = SignageSurface,
                                focusedIndicatorColor = NeonCyan,
                                unfocusedIndicatorColor = SignageSurfaceVariant
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = newVideoEnableVast,
                                    onCheckedChange = { newVideoEnableVast = it },
                                    colors = CheckboxDefaults.colors(checkedColor = NeonCyan, checkmarkColor = Color.Black)
                                )
                                Text("Enable VAST Ad Pre-roll", color = TextSecondary, fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    if (newVideoUrl.isNotBlank()) {
                                        onAddVideoToPlaylist(newVideoTitle, newVideoUrl, 30, newVideoEnableVast)
                                        newVideoTitle = ""
                                        newVideoUrl = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Video", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Current playlist items list
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            playlist.items.forEachIndexed { idx, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SignageSurface, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VideoLibrary,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "${idx + 1}. ${item.title}",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (item.localFilePath != null) "Cached Offline • ${item.durationSeconds}s" else "Streaming • ${item.durationSeconds}s",
                                                color = if (item.localFilePath != null) EmeraldGreen else TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    if (playlist.items.size > 1) {
                                        IconButton(
                                            onClick = { onRemoveVideoFromPlaylist(item.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove Video",
                                                tint = CoralRed,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = SignageSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Ticker editor
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Show Bottom News Ticker",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(
                                checked = isTickerEnabled,
                                onCheckedChange = onToggleTicker,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = NeonCyan
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = editingTicker,
                                onValueChange = { editingTicker = it },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SignageSurface,
                                    unfocusedContainerColor = SignageSurface,
                                    focusedIndicatorColor = NeonCyan,
                                    unfocusedIndicatorColor = SignageSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onUpdateTickerText(editingTicker) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) {
                                Text("Update", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // SECTION 5: EXIT KIOSK
                item {
                    AdminSectionCard(title = "Maintenance & Exit") {
                        Button(
                            onClick = onExitKioskApp,
                            colors = ButtonDefaults.buttonColors(containerColor = CoralRed.copy(alpha = 0.85f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("exit_kiosk_button")
                        ) {
                            Icon(imageVector = Icons.Default.ExitToApp, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exit Signage Player to Android Home", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRedirectRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    buttonText: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SignageSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) EmeraldGreen else AmberAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isGranted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = EmeraldGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    color = if (isGranted) EmeraldGreen.copy(alpha = 0.9f) else TextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.testTag(testTag)
        ) {
            Text(
                text = buttonText,
                fontSize = 11.sp,
                color = if (isGranted) EmeraldGreen else NeonCyan,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AdminSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SignageCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                color = NeonCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
