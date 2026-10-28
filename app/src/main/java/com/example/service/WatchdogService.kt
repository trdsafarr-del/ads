package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SignageApplication
import com.example.data.local.db.DeviceLogEntity
import com.example.data.local.db.SignageDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val restartTimestamps = mutableListOf<Long>()
    private var isCrashLoopPaused = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkPlayerHealth()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService started")

        val notification = buildWatchdogNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start 10-second monitoring cycle
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, PING_INTERVAL_MS)

        return START_STICKY
    }

    private fun checkPlayerHealth() {
        val now = System.currentTimeMillis()
        val timeSinceLastPing = now - lastHeartbeatTimestamp

        if (timeSinceLastPing > UNRESPONSIVE_THRESHOLD_MS) {
            Log.w(TAG, "Player unresponsive for ${timeSinceLastPing / 1000}s! Evaluating restart...")

            // Clean restart timestamps older than 60s
            restartTimestamps.removeAll { now - it > 60_000L }

            if (restartTimestamps.size >= 3) {
                if (!isCrashLoopPaused) {
                    isCrashLoopPaused = true
                    val msg = "Crash loop detected: 3+ restarts in 60s. Enforcing 60s cool-down safety window."
                    Log.e(TAG, msg)
                    logIncident("ERROR", msg)

                    handler.postDelayed({
                        isCrashLoopPaused = false
                        lastHeartbeatTimestamp = System.currentTimeMillis() // reset timer after safety wait
                    }, 60_000L)
                }
                return
            }

            // Perform auto-recovery restart
            restartTimestamps.add(now)
            val msg = "Watchdog automatically restarting player (Restart #${restartTimestamps.size} in 60s)"
            Log.i(TAG, msg)
            logIncident("WARN", msg)

            restartMainActivity()
        }
    }

    private fun restartMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("LAUNCHED_FROM_WATCHDOG", true)
            }
            startActivity(intent)
            lastHeartbeatTimestamp = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog failed to restart MainActivity: ${e.message}", e)
        }
    }

    private fun logIncident(level: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SignageDatabase.getInstance(applicationContext).deviceLogDao().insert(
                    DeviceLogEntity(
                        level = level,
                        tag = TAG,
                        message = message
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun buildWatchdogNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SignageApplication.CHANNEL_BOOT_SERVICE)
            .setContentTitle("Digital Signage Health Monitor")
            .setContentText("Watchdog active: 24/7 crash recovery & auto-restart")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdogRunnable)
        Log.d(TAG, "WatchdogService destroyed")
    }

    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 2002
        private const val PING_INTERVAL_MS = 10_000L // Check every 10 seconds
        private const val UNRESPONSIVE_THRESHOLD_MS = 25_000L // 25s without heartbeat

        @Volatile
        private var lastHeartbeatTimestamp: Long = System.currentTimeMillis()

        /**
         * Called by MainActivity / SignagePlayerManager every 5-10s to signal healthy playback.
         */
        fun ping(context: Context) {
            lastHeartbeatTimestamp = System.currentTimeMillis()
        }

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting WatchdogService: ${e.message}")
            }
        }
    }
}
