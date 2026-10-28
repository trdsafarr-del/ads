package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.local.SignageLocalStore
import com.example.service.BootForegroundService
import com.example.service.WatchdogService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "BootReceiver received action: $action")

        val localStore = SignageLocalStore(context)
        if (!localStore.isAutoBootEnabled()) {
            Log.d(TAG, "Auto-boot is disabled in preferences; skipping auto-launch")
            return
        }

        val isBootEvent = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> true
            else -> false
        }

        if (isBootEvent) {
            val useDelay = localStore.isStartupDelayEnabled()
            val delayMs = if (useDelay) 15_000L else 0L

            Log.i(TAG, "Triggering digital signage auto-launch (Startup delay: ${delayMs / 1000}s)")

            Handler(Looper.getMainLooper()).postDelayed({
                launchSignagePipeline(context)
            }, delayMs)
        }
    }

    private fun launchSignagePipeline(context: Context) {
        // 1. Start Watchdog Service for 24/7 crash recovery and process monitoring
        WatchdogService.start(context)

        // 2. Start BootForegroundService (bypasses Android 10+ background activity restrictions)
        val serviceIntent = Intent(context, BootForegroundService::class.java).apply {
            action = BootForegroundService.ACTION_AUTO_START_SIGNAGE
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BootForegroundService: ${e.message}", e)
        }

        // 3. Direct launch attempt for Android TV default HOME launcher or overlay permitted devices
        try {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("LAUNCHED_FROM_BOOT", true)
            }
            context.startActivity(activityIntent)
            Log.i(TAG, "Direct MainActivity launch invoked from BootReceiver")
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch deferred to foreground service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
