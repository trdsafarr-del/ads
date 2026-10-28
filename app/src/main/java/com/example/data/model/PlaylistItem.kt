package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlaylistItem(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "videoUrl") val videoUrl: String,
    @Json(name = "checksum") val checksum: String? = null,
    @Json(name = "durationSeconds") val durationSeconds: Int = 30,
    @Json(name = "adTagUrl") val adTagUrl: String? = null,
    @Json(name = "order") val order: Int = 0,
    @Json(name = "localFilePath") val localFilePath: String? = null,
    @Json(name = "isOfflineDownloaded") val isOfflineDownloaded: Boolean = false
)
