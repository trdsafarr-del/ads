package com.example.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import com.example.R
import com.example.receiver.SignageDeviceAdminReceiver
import com.example.service.SignageAccessibilityService

object KioskPermissionHelper {

    private const val TAG = "KioskPermissionHelper"
    private const val HOME_CHOOSER_ALIAS = "com.example.HomeChooserAlias"

    /**
     * Checks if this app is currently the active Default HOME Launcher.
     */
    fun isDefaultHomeLauncher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    return true
                }
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val currentHomePackage = resolveInfo?.activityInfo?.packageName
        return currentHomePackage == context.packageName
    }

    /**
     * Creates the Android 10+ RoleManager.ROLE_HOME request intent if available.
     */
    fun createHomeRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
                ) {
                    SignageAccessibilityService.allowSettingsNavigationGraceWindow()
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                }
            } catch (e: Exception) {
                Log.w(TAG, "RoleManager ROLE_HOME intent creation failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Forces the system Home Launcher Picker / Default Home Settings to open.
     * 1. Enables temporary HomeChooserAlias to invalidate any locked default launcher preference,
     *    triggers the system HOME resolver dialog, then disables the temporary alias.
     * 2. If the system still suppresses the chooser (e.g. certain Android TV ROMs),
     *    directly opens Settings.ACTION_HOME_SETTINGS.
     */
    fun forceShowDefaultLauncherPickerOrSettings(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        try {
            val pm = context.packageManager
            val aliasComponent = ComponentName(context.packageName, HOME_CHOOSER_ALIAS)

            // Step 1: Toggle temporary HOME alias so Android resets the default launcher binding
            pm.setComponentEnabledSetting(
                aliasComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(homeIntent)

            // Disable temporary alias after 800ms so MainActivity remains the single clean option
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pm.setComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }, 800L)
        } catch (e: Exception) {
            Log.w(TAG, "Home alias trick fallback to direct Home Settings: ${e.message}")
            openDefaultHomeSettingsDirectly(context)
        }
    }

    /**
     * Directly redirects the user to Android's Default Home App Settings page.
     */
    fun openDefaultHomeSettingsDirectly(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Select 'SignageTV' as your Default Home App",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if SignageDeviceAdminReceiver is currently active in DevicePolicyManager.
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = SignageDeviceAdminReceiver.getComponentName(context)
            dpm?.isAdminActive(adminComponent) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Directly redirects the user to the system "Activate Device Admin App" screen.
     */
    fun openDeviceAdminActivationSettings(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        val adminComponent = SignageDeviceAdminReceiver.getComponentName(context)
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_description)
                )
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct DeviceAdmin intent fallback to Security Settings: ${e.message}")
            try {
                val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                Toast.makeText(context, "Enable SignageTV in Device Admin Apps", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if SignageAccessibilityService is enabled in system Accessibility Settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (SignageAccessibilityService.isRunning) return true
        val expectedComponentName = ComponentName(context, SignageAccessibilityService::class.java)
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * Directly redirects the user to Android's Accessibility Settings screen.
     */
    fun openAccessibilitySettingsDirectly(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Enable 'SignageTV' under Downloaded Apps / Accessibility Services for 24/7 Run",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility settings fallback: ${e.message}")
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if Overlay Permission (Display over other apps) is granted.
     */
    fun isOverlayPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Directly redirects to Overlay Permission Settings.
     */
    fun openOverlayPermissionSettingsDirectly(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct package overlay intent fallback: ${e.message}")
            }
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettingsDirectly(context)
    }

    /**
     * Checks if Battery Optimization is ignored (Unrestricted 24/7 background execution).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        return true
    }

    /**
     * Directly launches the system dialog or settings to disable Battery Optimization for 24x7 run.
     */
    @Suppress("BatteryLife")
    fun openBatteryOptimizationSettingsDirectly(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct battery opt request fallback: ${e.message}")
            }
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettingsDirectly(context)
    }

    fun openAppDetailsSettingsDirectly(context: Context) {
        SignageAccessibilityService.allowSettingsNavigationGraceWindow()
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
