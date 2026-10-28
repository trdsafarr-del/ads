package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SignageApplication

class BootForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BootForegroundService started with action: ${intent?.action}")

        // 1. Immediately promote to Foreground Service with required notification
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Launch MainActivity
        launchMainActivity()

        // 3. Auto-stop service after 10 seconds to save system resources once Activity is active
        handler.postDelayed({
            Log.d(TAG, "BootForegroundService auto-stop timeout reached")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 10_000L)

        return START_NOT_STICKY
    }

    private fun launchMainActivity() {
        // Prepare Activity Intent
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("LAUNCHED_FROM_BOOT_SERVICE", true)
        }

        // Check Overlay Permission (TCL / specialized Android TV builds often enforce this)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        Log.d(TAG, "System overlay permission granted: $hasOverlay")

        try {
            startActivity(activityIntent)
            Log.i(TAG, "Successfully started MainActivity from BootForegroundService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity directly from service: ${e.message}", e)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SignageApplication.CHANNEL_BOOT_SERVICE)
            .setContentTitle("Digital Signage Player Active")
            .setContentText("Auto-starting kiosk display...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            // Full-screen intent triggers immediate screen takeover on Android TV / Android 10+
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "BootForegroundService destroyed")
    }

    companion object {
        private const val TAG = "BootForegroundService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_AUTO_START_SIGNAGE = "com.example.action.AUTO_START_SIGNAGE"
    }
}
