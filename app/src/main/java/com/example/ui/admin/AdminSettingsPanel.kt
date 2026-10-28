package com.example.ui.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlaylistResponse
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
    onExitKioskApp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editingUrl by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var urlSavedMessage by remember { mutableStateOf(false) }

    val hasOverlayPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 340.dp, max = 580.dp)
            .fillMaxWidth(0.92f)
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
                            text = "Signage Control Center",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Landscape & Portrait Totem Management",
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
                // Section 1: Display Orientation & Screen Mount (Landscape vs Portrait)
                item {
                    AdminSectionCard(title = "Display Orientation (Landscape / Portrait)") {
                        Text(
                            text = "Mounting angle (Supports Vertical Totem 9:16 and Horizontal 16:9):",
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

                        // Video Aspect Ratio Scale Mode
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

                // Section 2: Auto-Start & TV Kiosk Permissions
                item {
                    AdminSectionCard(title = "Auto-Start & Kiosk Status") {
                        // Overlay Permission (TCL / Android TV)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = if (hasOverlayPermission) EmeraldGreen else AmberAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "System Overlay Permission",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = if (hasOverlayPermission) "Granted (Required for TCL/specialized Android TVs)" else "Not granted. Tap to allow overlay for robust auto-boot launch.",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            if (!hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, color = AmberAccent)
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Granted",
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Default TV Launcher Setting
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Default HOME Launcher",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Manifest registered with CATEGORY_HOME. Set as default TV launcher.",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(homeIntent)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Home Picker", fontSize = 11.sp, color = NeonCyan)
                            }
                        }

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
                                    text = "BOOT_COMPLETED receiver + Foreground Service launches app",
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

                        Spacer(modifier = Modifier.height(10.dp))

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
                                    text = if (isKioskLocked) "Locked: Back button disabled unless unlocked by Admin PIN" else "Unlocked: Back button allows navigating out",
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

                // Section 3: Cloud Sync & WorkManager
                item {
                    AdminSectionCard(title = "Cloud Sync & WorkManager") {
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
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
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
                                text = "API URL saved successfully",
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

                // Section 4: Display Options & Exit
                item {
                    AdminSectionCard(title = "Display Settings & Maintenance") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Show News & Weather Bottom Ticker",
                                color = Color.White,
                                fontSize = 13.sp
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

                        Spacer(modifier = Modifier.height(14.dp))

                        // Exit Kiosk App
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
