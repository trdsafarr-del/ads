package com.example.data.cloud

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Real-time Cloud Firestore repository for SignageTV.
 * Connects to the provisioned Firestore database using R.string.firestore_database_id
 * and enforces Google Sign-In authentication isolation across videos, devices, and broadcast config.
 */
class SignageCloudRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Real-time snapshot stream of the authenticated user's signage videos.
     */
    fun observeVideos(userId: String = requireUserId()): Flow<List<SignageCloudVideo>> = flow {
        val path = "users/$userId/videos"
        emitAll(
            db.collection("users").document(userId).collection("videos")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            SignageCloudVideo::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }.sortedBy { it.order }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Real-time snapshot stream of the authenticated user's paired Android TV devices.
     */
    fun observeDevices(userId: String = requireUserId()): Flow<List<SignageCloudDevice>> = flow {
        val path = "users/$userId/devices"
        emitAll(
            db.collection("users").document(userId).collection("devices")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(
                            SignageCloudDevice::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Real-time snapshot stream of the authenticated user's live broadcast configuration.
     */
    fun observeConfig(userId: String = requireUserId()): Flow<SignageCloudConfig?> = flow {
        val path = "users/$userId/config/live_broadcast"
        emitAll(
            db.collection("users").document(userId).collection("config").document("live_broadcast")
                .snapshots()
                .map { doc ->
                    if (doc.exists()) {
                        doc.toObject(
                            SignageCloudConfig::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    if (error is Exception) {
                        handleFirestoreError(error, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createVideo(
        videoId: String,
        title: String,
        videoUrl: String,
        durationSeconds: Long = 30L,
        order: Long = 1L,
        videoOrientation: String = "AUTO",
        isForcedPlay: Boolean = false
    ): Result<String> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("videos").document(videoId)
        val payload = mapOf(
            "userId" to uid,
            "title" to title.take(200).ifBlank { "Signage Video" },
            "videoUrl" to videoUrl.take(2048),
            "durationSeconds" to durationSeconds.coerceIn(1L, 86400L),
            "order" to order.coerceIn(0L, 10000L),
            "videoOrientation" to videoOrientation.uppercase().takeIf {
                it == "AUTO" || it == "LANDSCAPE" || it == "PORTRAIT"
            }!!,
            "isForcedPlay" to isForcedPlay,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserVideos(): Result<List<SignageCloudVideo>> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("videos")
        return try {
            val snap = colRef.whereEqualTo("userId", uid).get().await()
            val list = snap.documents.mapNotNull {
                it.toObject(SignageCloudVideo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }.sortedBy { it.order }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path)
            Result.failure(e)
        }
    }

    suspend fun getVideoById(targetUserId: String, videoId: String): Result<SignageCloudVideo?> {
        val docRef = db.collection("users").document(targetUserId).collection("videos").document(videoId)
        return try {
            val snap = docRef.get().await()
            Result.success(
                snap.toObject(SignageCloudVideo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteVideo(videoId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("videos").document(videoId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertDeviceHeartbeat(
        deviceId: String,
        name: String,
        pairCode: String,
        status: String,
        screenOrientation: String,
        videoOrientation: String,
        currentVideoTitle: String
    ): Result<Unit> {
        val uid = requireUserId()
        val cleanDeviceId = deviceId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128).ifBlank { "tv_default" }
        val docRef = db.collection("users").document(uid).collection("devices").document(cleanDeviceId)
        return try {
            val existingSnap = docRef.get().await()
            val validScreen = screenOrientation.uppercase().takeIf {
                it == "LANDSCAPE" || it == "PORTRAIT" || it == "REVERSE_LANDSCAPE" || it == "REVERSE_PORTRAIT"
            } ?: "LANDSCAPE"
            val validVideo = videoOrientation.uppercase().takeIf {
                it == "AUTO" || it == "LANDSCAPE" || it == "PORTRAIT"
            } ?: "AUTO"

            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "deviceId" to cleanDeviceId,
                    "name" to name.take(120).ifBlank { "SignageTV" },
                    "pairCode" to pairCode.take(16).padEnd(4, '0'),
                    "status" to status.take(64).ifBlank { "PLAYING" },
                    "screenOrientation" to validScreen,
                    "videoOrientation" to validVideo,
                    "currentVideoTitle" to currentVideoTitle.take(200),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "name" to name.take(120).ifBlank { "SignageTV" },
                    "pairCode" to pairCode.take(16).padEnd(4, '0'),
                    "status" to status.take(64).ifBlank { "PLAYING" },
                    "screenOrientation" to validScreen,
                    "videoOrientation" to validVideo,
                    "currentVideoTitle" to currentVideoTitle.take(200),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertBroadcastConfig(
        orientation: String,
        videoOrientation: String,
        scaleMode: String,
        pushedVideoId: String,
        pushedVideoTimestamp: Long,
        version: Long
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("config").document("live_broadcast")
        return try {
            val existingSnap = docRef.get().await()
            val validDisplay = orientation.uppercase().takeIf {
                it == "LANDSCAPE" || it == "PORTRAIT" || it == "REVERSE_LANDSCAPE" || it == "REVERSE_PORTRAIT"
            } ?: "LANDSCAPE"
            val validVideo = videoOrientation.uppercase().takeIf {
                it == "AUTO" || it == "LANDSCAPE" || it == "PORTRAIT"
            } ?: "AUTO"
            val validScale = scaleMode.uppercase().takeIf {
                it == "ZOOM" || it == "FIT" || it == "FILL"
            } ?: "ZOOM"

            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "orientation" to validDisplay,
                    "videoOrientation" to validVideo,
                    "scaleMode" to validScale,
                    "pushedVideoId" to pushedVideoId.take(128),
                    "pushedVideoTimestamp" to pushedVideoTimestamp.coerceAtLeast(0L),
                    "version" to version.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "orientation" to validDisplay,
                    "videoOrientation" to validVideo,
                    "scaleMode" to validScale,
                    "pushedVideoId" to pushedVideoId.take(128),
                    "pushedVideoTimestamp" to pushedVideoTimestamp.coerceAtLeast(0L),
                    "version" to version.coerceAtLeast(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }
}
