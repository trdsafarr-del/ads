package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VideoItem(
    @Json(name = "id") val id: String,
    @Json(name = "url") val url: String,
    @Json(name = "order") val order: Int = 0,
    @Json(name = "duration") val duration: Int = 30,
    @Json(name = "checksum") val checksum: String? = null,
    @Json(name = "localFilePath") val localFilePath: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "adTagUrl") val adTagUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class AdItem(
    @Json(name = "vastTag") val vastTag: String,
    @Json(name = "frequency") val frequency: Int = 1
)

@JsonClass(generateAdapter = true)
data class ScheduleItem(
    @Json(name = "startTime") val startTime: String = "00:00",
    @Json(name = "endTime") val endTime: String = "23:59",
    @Json(name = "videoIds") val videoIds: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PlaylistSchema(
    @Json(name = "version") val version: Int = 1,
    @Json(name = "videos") val videos: List<VideoItem> = emptyList(),
    @Json(name = "ads") val ads: List<AdItem> = emptyList(),
    @Json(name = "schedule") val schedule: List<ScheduleItem> = emptyList(),
    @Json(name = "tickerText") val tickerText: String? = null,
    @Json(name = "orientation") val orientation: String? = null,
    @Json(name = "videoOrientation") val videoOrientation: Int? = null,
    @Json(name = "scaleMode") val scaleMode: String? = null,
    @Json(name = "tickerEnabled") val tickerEnabled: Boolean? = null,
    @Json(name = "isMuted") val isMuted: Boolean? = null,
    @Json(name = "kioskLocked") val kioskLocked: Boolean? = null,
    @Json(name = "autoBootEnabled") val autoBootEnabled: Boolean? = null,
    @Json(name = "lastPushedVideoId") val lastPushedVideoId: String? = null,
    @Json(name = "lastPushTimestamp") val lastPushTimestamp: Long? = null,
    @Json(name = "offlineDownloadRequestedVideoId") val offlineDownloadRequestedVideoId: String? = null
)
