package com.example.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_videos")
data class CachedVideoEntity(
    @PrimaryKey val id: String,
    val url: String,
    val localPath: String? = null,
    val checksum: String? = null,
    val duration: Int = 30,
    val playOrder: Int = 0,
    val fileSizeBytes: Long = 0L,
    val lastPlayedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_playlists")
data class CachedPlaylistEntity(
    @PrimaryKey val id: String = "active_playlist",
    val version: Int = 1,
    val rawJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "device_logs")
data class DeviceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String
)
