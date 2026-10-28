package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(video: CachedVideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<CachedVideoEntity>)

    @Query("SELECT * FROM cached_videos ORDER BY playOrder ASC")
    suspend fun getAllVideos(): List<CachedVideoEntity>

    @Query("SELECT * FROM cached_videos WHERE id = :id LIMIT 1")
    suspend fun getVideoById(id: String): CachedVideoEntity?

    @Query("DELETE FROM cached_videos WHERE id NOT IN (:keepIds)")
    suspend fun deleteVideosExcept(keepIds: List<String>)

    @Query("DELETE FROM cached_videos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cached_videos")
    suspend fun clearAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM cached_playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: String = "active_playlist"): CachedPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaylist(playlist: CachedPlaylistEntity)
}

@Dao
interface DeviceLogDao {
    @Insert
    suspend fun insert(log: DeviceLogEntity)

    @Query("SELECT * FROM device_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 20): List<DeviceLogEntity>

    @Query("DELETE FROM device_logs WHERE id NOT IN (SELECT id FROM device_logs ORDER BY timestamp DESC LIMIT 100)")
    suspend fun trimOldLogs()
}
