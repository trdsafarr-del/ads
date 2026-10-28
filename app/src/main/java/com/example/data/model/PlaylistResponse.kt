package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlaylistResponse(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "version") val version: Int = 1,
    @Json(name = "syncIntervalMinutes") val syncIntervalMinutes: Long = 15,
    @Json(name = "defaultAdTagUrl") val defaultAdTagUrl: String? = null,
    @Json(name = "tickerText") val tickerText: String? = null,
    @Json(name = "orientation") val orientation: String? = null,
    @Json(name = "scaleMode") val scaleMode: String? = null,
    @Json(name = "tickerEnabled") val tickerEnabled: Boolean? = null,
    @Json(name = "isMuted") val isMuted: Boolean? = null,
    @Json(name = "kioskLocked") val kioskLocked: Boolean? = null,
    @Json(name = "autoBootEnabled") val autoBootEnabled: Boolean? = null,
    @Json(name = "lastPushedVideoId") val lastPushedVideoId: String? = null,
    @Json(name = "lastPushTimestamp") val lastPushTimestamp: Long = 0L,
    @Json(name = "offlineDownloadRequestedVideoId") val offlineDownloadRequestedVideoId: String? = null,
    @Json(name = "items") val items: List<PlaylistItem> = emptyList()
)
