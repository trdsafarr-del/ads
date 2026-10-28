package com.example.data.cloud

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class SignageCloudVideo(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val videoUrl: String = "",
    val durationSeconds: Long = 30L,
    val order: Long = 1L,
    val videoOrientation: String = "AUTO",
    val isForcedPlay: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SignageCloudDevice(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val name: String = "",
    val pairCode: String = "",
    val status: String = "PLAYING",
    val screenOrientation: String = "LANDSCAPE",
    val videoOrientation: String = "AUTO",
    val currentVideoTitle: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SignageCloudConfig(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orientation: String = "LANDSCAPE",
    val videoOrientation: String = "AUTO",
    val scaleMode: String = "ZOOM",
    val pushedVideoId: String = "",
    val pushedVideoTimestamp: Long = 0L,
    val version: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
