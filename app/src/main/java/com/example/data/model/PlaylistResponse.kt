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
    @Json(name = "items") val items: List<PlaylistItem> = emptyList()
)
