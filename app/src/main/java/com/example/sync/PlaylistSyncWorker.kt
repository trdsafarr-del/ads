package com.example.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.repository.SignageRepository
import java.util.concurrent.TimeUnit

class PlaylistSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting periodic digital signage sync & heartbeat")
        val repository = SignageRepository(applicationContext)

        return try {
            // 1. Transmit device status heartbeat to CMS server
            repository.sendDeviceHeartbeat()

            // 2. Fetch new playlist, verify checksums, download to cache & update Room
            val result = repository.syncPlaylist()
            if (result.isSuccess) {
                Log.i(TAG, "Signage sync completed successfully: ${result.getOrNull()?.items?.size} items")
                Result.success()
            } else {
                Log.w(TAG, "Signage sync failed, will retry: ${result.exceptionOrNull()?.message}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in PlaylistSyncWorker: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "PlaylistSyncWorker"
        const val PERIODIC_WORK_NAME = "periodic_signage_playlist_sync"
        const val IMMEDIATE_WORK_NAME = "immediate_signage_playlist_sync"

        fun enqueuePeriodicSync(context: Context, intervalMinutes: Long = 15) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicRequest = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
                    intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest
                )
                Log.i(TAG, "Enqueued periodic sync every $intervalMinutes minutes")
            } catch (e: Exception) {
                Log.w(TAG, "Failed enqueuing WorkManager task: ${e.message}")
            }
        }

        fun triggerImmediateSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val immediateRequest = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    immediateRequest
                )
                Log.i(TAG, "Enqueued immediate sync request")
            } catch (e: Exception) {
                Log.w(TAG, "Failed triggering immediate sync: ${e.message}")
            }
        }

        fun cancelSync(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            } catch (_: Exception) {}
        }
    }
}
