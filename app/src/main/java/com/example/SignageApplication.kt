package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.example.sync.PlaylistSyncWorker

class SignageApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SignageApplication started")
        createNotificationChannels()

        // Schedule periodic cloud sync via WorkManager
        try {
            PlaylistSyncWorker.enqueuePeriodicSync(this, 15)
        } catch (e: Exception) {
            Log.w(TAG, "Deferred WorkManager initialization: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Boot & Kiosk High Priority Channel
            val bootChannel = NotificationChannel(
                CHANNEL_BOOT_SERVICE,
                "Signage Auto-Start & Display Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps digital signage playback active and ensures auto-launch on TV power-on"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            // Sync Channel
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC_SERVICE,
                "Signage Content Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Periodic playlist and video assets sync in the background"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(bootChannel)
            notificationManager.createNotificationChannel(syncChannel)
        }
    }

    companion object {
        private const val TAG = "SignageApplication"
        const val CHANNEL_BOOT_SERVICE = "signage_boot_channel"
        const val CHANNEL_SYNC_SERVICE = "signage_sync_channel"
    }
}
