package com.example.data.api

import com.example.data.model.PlaylistSchema
import com.example.data.model.VideoItem
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

@JsonClass(generateAdapter = true)
data class HeartbeatRequest(
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "pairingCode") val pairingCode: String? = null,
    @Json(name = "status") val status: String,
    @Json(name = "currentVideoId") val currentVideoId: String?,
    @Json(name = "storageUsed") val storageUsed: Long,
    @Json(name = "appVersion") val appVersion: String,
    @Json(name = "lastSyncTime") val lastSyncTime: Long,
    @Json(name = "screenOrientation") val screenOrientation: String? = null,
    @Json(name = "videoOrientation") val videoOrientation: Int? = null,
    @Json(name = "scaleMode") val scaleMode: String? = null
)

@JsonClass(generateAdapter = true)
data class HeartbeatResponse(
    @Json(name = "acknowledged") val acknowledged: Boolean = true,
    @Json(name = "serverAction") val serverAction: String? = null,
    @Json(name = "playlistVersion") val playlistVersion: Int? = null,
    @Json(name = "lastPushedVideoId") val lastPushedVideoId: String? = null,
    @Json(name = "lastPushTimestamp") val lastPushTimestamp: Long? = null
)

@JsonClass(generateAdapter = true)
data class PushSingleVideoRequest(
    @Json(name = "action") val action: String = "addSingleVideo",
    @Json(name = "video") val video: VideoItem,
    @Json(name = "lastPushedVideoId") val lastPushedVideoId: String? = null
)

@JsonClass(generateAdapter = true)
data class PushPlaylistSettingsRequest(
    @Json(name = "deviceId") val deviceId: String? = null,
    @Json(name = "videos") val videos: List<VideoItem>? = null,
    @Json(name = "orientation") val orientation: String? = null,
    @Json(name = "videoOrientation") val videoOrientation: Int? = null,
    @Json(name = "scaleMode") val scaleMode: String? = null,
    @Json(name = "tickerText") val tickerText: String? = null,
    @Json(name = "tickerEnabled") val tickerEnabled: Boolean? = null,
    @Json(name = "isMuted") val isMuted: Boolean? = null,
    @Json(name = "kioskLocked") val kioskLocked: Boolean? = null,
    @Json(name = "lastPushedVideoId") val lastPushedVideoId: String? = null
)

@JsonClass(generateAdapter = true)
data class PushPlaylistResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "playlist") val playlist: PlaylistSchema? = null
)

@JsonClass(generateAdapter = true)
data class UploadVideoRequest(
    @Json(name = "id") val id: String,
    @Json(name = "filename") val filename: String,
    @Json(name = "base64Data") val base64Data: String
)

@JsonClass(generateAdapter = true)
data class UploadVideoResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "videoId") val videoId: String? = null,
    @Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class PairRequest(
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "pairingCode") val pairingCode: String,
    @Json(name = "deviceInfo") val deviceInfo: String
)

@JsonClass(generateAdapter = true)
data class PairResponse(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "deviceToken") val deviceToken: String? = null,
    @Json(name = "organizationId") val organizationId: String? = null,
    @Json(name = "deviceName") val deviceName: String? = null
)

@JsonClass(generateAdapter = true)
data class ApprovedCodeEntry(
    @Json(name = "pairingCode") val pairingCode: String? = null,
    @Json(name = "deviceName") val deviceName: String? = null,
    @Json(name = "approvedAt") val approvedAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class PairingStatusResponse(
    @Json(name = "autoApprovePairing") val autoApprovePairing: Boolean = false,
    @Json(name = "approvedCodes") val approvedCodes: Map<String, ApprovedCodeEntry> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class UnpairDeviceRequest(
    @Json(name = "action") val action: String = "unpairDevice",
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "pairingCode") val pairingCode: String
)

@JsonClass(generateAdapter = true)
data class ApkUpdateResponse(
    @Json(name = "updateAvailable") val updateAvailable: Boolean = false,
    @Json(name = "versionCode") val versionCode: Int = 1,
    @Json(name = "versionName") val versionName: String = "1.0",
    @Json(name = "apkUrl") val apkUrl: String? = null
)

interface SignageApiService {
    @GET("api/playlist")
    suspend fun fetchPlaylist(
        @Query("deviceId") deviceId: String? = null
    ): Response<PlaylistSchema>

    @POST("api/playlist")
    suspend fun pushSingleVideo(
        @Body request: PushSingleVideoRequest
    ): Response<PushPlaylistResponse>

    @POST("api/playlist")
    suspend fun pushPlaylistSettings(
        @Body request: PushPlaylistSettingsRequest
    ): Response<PushPlaylistResponse>

    @POST("api/upload")
    suspend fun uploadVideoBase64(
        @Body request: UploadVideoRequest
    ): Response<UploadVideoResponse>

    @POST("api/devices/heartbeat")
    suspend fun sendHeartbeat(
        @Body heartbeat: HeartbeatRequest
    ): Response<HeartbeatResponse>

    @GET("api/devices/pair")
    suspend fun getPairingStatus(): Response<PairingStatusResponse>

    @POST("api/devices/pair")
    suspend fun pairDevice(
        @Body pairRequest: PairRequest
    ): Response<PairResponse>

    @POST("api/devices/pair")
    suspend fun unpairDeviceOnServer(
        @Body request: UnpairDeviceRequest
    ): Response<PairResponse>

    @GET("api/updates/check")
    suspend fun checkForUpdates(
        @Query("versionCode") currentVersionCode: Int
    ): Response<ApkUpdateResponse>

    @Streaming
    @GET
    suspend fun downloadMediaFile(@Url fileUrl: String): Response<ResponseBody>
}
