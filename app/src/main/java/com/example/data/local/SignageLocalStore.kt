package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.PlaylistItem
import com.example.data.model.PlaylistResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SignageLocalStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("signage_settings", Context.MODE_PRIVATE)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val playlistAdapter = moshi.adapter(PlaylistResponse::class.java)

    val videoCacheDir: File
        get() = File(context.filesDir, "signage_videos").apply {
            if (!exists()) mkdirs()
        }

    fun getApiServerUrl(): String {
        return prefs.getString(KEY_API_SERVER_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
    }

    fun setApiServerUrl(url: String) {
        val normalized = if (!url.endsWith("/")) "$url/" else url
        prefs.edit().putString(KEY_API_SERVER_URL, normalized).apply()
    }

    fun isKioskModeLocked(): Boolean {
        return prefs.getBoolean(KEY_KIOSK_LOCKED, true)
    }

    fun setKioskModeLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_KIOSK_LOCKED, locked).apply()
    }

    fun isAutoBootEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_BOOT_ENABLED, true)
    }

    fun setAutoBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BOOT_ENABLED, enabled).apply()
    }

    fun getLastSyncTimestamp(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    fun getLastSyncStatus(): String {
        return prefs.getString(KEY_LAST_SYNC_STATUS, "Ready") ?: "Ready"
    }

    fun setLastSyncStatus(status: String) {
        prefs.edit().putString(KEY_LAST_SYNC_STATUS, status).apply()
    }

    fun getAdminPin(): String {
        return prefs.getString(KEY_ADMIN_PIN, DEFAULT_ADMIN_PIN) ?: DEFAULT_ADMIN_PIN
    }

    fun setAdminPin(pin: String) {
        prefs.edit().putString(KEY_ADMIN_PIN, pin).apply()
    }

    fun isTickerEnabled(): Boolean {
        return prefs.getBoolean(KEY_TICKER_ENABLED, true)
    }

    fun setTickerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TICKER_ENABLED, enabled).apply()
    }

    fun isStartupDelayEnabled(): Boolean {
        return prefs.getBoolean(KEY_STARTUP_DELAY, false)
    }

    fun setStartupDelayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STARTUP_DELAY, enabled).apply()
    }

    fun isBurnInProtectionEnabled(): Boolean {
        return prefs.getBoolean(KEY_BURN_IN_PROTECTION, true)
    }

    fun setBurnInProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BURN_IN_PROTECTION, enabled).apply()
    }

    suspend fun saveCachedPlaylist(playlist: PlaylistResponse) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, CACHED_PLAYLIST_FILE)
            val json = playlistAdapter.toJson(playlist)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun loadCachedPlaylist(): PlaylistResponse? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, CACHED_PLAYLIST_FILE)
            if (file.exists()) {
                val json = file.readText()
                playlistAdapter.fromJson(json)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getLocalVideoFile(item: PlaylistItem): File {
        val sanitizedId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val extension = item.videoUrl.substringAfterLast('.', "mp4")
            .substringBefore('?')
            .ifEmpty { "mp4" }
        return File(videoCacheDir, "$sanitizedId.$extension")
    }

    fun getLocalVideoPathIfExists(item: PlaylistItem): String? {
        val file = getLocalVideoFile(item)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun cleanupUnusedVideos(activeItems: List<PlaylistItem>) {
        val activeFilenames = activeItems.map { getLocalVideoFile(it).name }.toSet()
        val existingFiles = videoCacheDir.listFiles() ?: return
        for (file in existingFiles) {
            if (file.isFile && !activeFilenames.contains(file.name)) {
                file.delete()
            }
        }
    }

    fun getCachedStorageSizeBytes(): Long {
        return videoCacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    companion object {
        private const val KEY_API_SERVER_URL = "api_server_url"
        private const val KEY_KIOSK_LOCKED = "kiosk_locked"
        private const val KEY_AUTO_BOOT_ENABLED = "auto_boot_enabled"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_TICKER_ENABLED = "ticker_enabled"
        private const val KEY_STARTUP_DELAY = "startup_delay"
        private const val KEY_BURN_IN_PROTECTION = "burn_in_protection"

        const val DEFAULT_API_URL = "https://mock-signage.example.com/"
        const val DEFAULT_ADMIN_PIN = "1234"
        private const val CACHED_PLAYLIST_FILE = "cached_signage_playlist.json"
    }
}
