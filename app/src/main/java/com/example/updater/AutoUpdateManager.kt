package com.example.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.MainActivity
import com.example.data.api.SignageApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

class AutoUpdateManager(private val context: Context) {

    suspend fun downloadAndInstallApk(
        api: SignageApiService,
        apkUrl: String,
        targetVersionCode: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val destinationFile = File(context.cacheDir, "update_v$targetVersionCode.apk")
        try {
            Log.i(TAG, "Downloading silent APK update from $apkUrl")
            val response = api.downloadMediaFile(apkUrl)
            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "Failed downloading update APK: HTTP ${response.code()}")
                return@withContext false
            }

            response.body()!!.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!destinationFile.exists() || destinationFile.length() <= 0) {
                Log.w(TAG, "Downloaded APK is invalid or 0 bytes")
                return@withContext false
            }

            Log.i(TAG, "APK download complete (${destinationFile.length()} bytes). Scheduling installation.")
            installApkSilently(destinationFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Silent auto-update failed: ${e.message}", e)
            false
        }
    }

    /**
     * Uses Android PackageInstaller API to commit silent background installation.
     */
    private fun installApkSilently(apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            apkFile.inputStream().use { input ->
                session.openWrite("signage_apk_install", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("UPDATED_SUCCESSFULLY", true)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, callbackIntent, flags)

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "PackageInstaller session committed for silent update")
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking PackageInstaller: ${e.message}", e)
        }
    }

    /**
     * Checks if current hour is within idle maintenance window (default 3:00 AM - 4:00 AM)
     */
    fun isIdleMaintenanceWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour == 3
    }

    companion object {
        private const val TAG = "AutoUpdateManager"
    }
}
