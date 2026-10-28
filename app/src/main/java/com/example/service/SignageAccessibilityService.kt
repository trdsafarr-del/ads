package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.MainActivity
import com.example.data.local.SignageLocalStore

class SignageAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "SignageAccessibilityService connected: 24x7 Kiosk Guard Active")
        WatchdogService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        WatchdogService.ping(this)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Allow our own app and system permission/settings dialogs when admin is configuring
            if (packageName == applicationContext.packageName) return
            if (isAllowedSystemSettingsPackage(packageName) && isWithinSettingsGracePeriod()) {
                return
            }

            val localStore = SignageLocalStore(this)
            if (localStore.isKioskModeLocked() && !isWithinSettingsGracePeriod()) {
                // If an external launcher or unwanted popup steals focus during 24/7 signage,
                // bring SignageTV back to foreground after a brief check
                val now = System.currentTimeMillis()
                if (now - lastRelaunchTimestamp > 4000L && !isAllowedSystemSettingsPackage(packageName)) {
                    lastRelaunchTimestamp = now
                    Log.i(TAG, "Accessibility Guard: External window ($packageName) detected. Restoring SignageTV to foreground.")
                    try {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Accessibility Guard relaunch error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        WatchdogService.ping(this)
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "SignageAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.w(TAG, "SignageAccessibilityService destroyed")
    }

    private fun isAllowedSystemSettingsPackage(pkg: String): Boolean {
        return pkg.contains("settings", ignoreCase = true) ||
            pkg.contains("permissioncontroller", ignoreCase = true) ||
            pkg.contains("packageinstaller", ignoreCase = true) ||
            pkg.contains("systemui", ignoreCase = true) ||
            pkg.contains("android.intent.resolver", ignoreCase = true) ||
            pkg == "android"
    }

    companion object {
        private const val TAG = "SignageAccessibility"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        private var settingsGraceUntilTimestamp: Long = 0L

        @Volatile
        private var lastRelaunchTimestamp: Long = 0L

        /**
         * Grants a 90-second grace window when the operator clicks a Settings redirect button
         * in the Admin panel so they can toggle permissions comfortably.
         */
        fun allowSettingsNavigationGraceWindow(durationMs: Long = 90_000L) {
            settingsGraceUntilTimestamp = System.currentTimeMillis() + durationMs
        }

        fun isWithinSettingsGracePeriod(): Boolean {
            return System.currentTimeMillis() < settingsGraceUntilTimestamp
        }
    }
}
