package com.example.data.cloud

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class SignageCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createVideo_validPayload_createsAndObservesDocument() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = SignageCloudRepository(firestore)
        val videoId = "vid_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createVideo(
                videoId = videoId,
                title = "Showroom Ad",
                videoUrl = "https://www.w3schools.com/html/mov_bbb.mp4",
                durationSeconds = 30L,
                order = 1L,
                videoOrientation = "LANDSCAPE",
                isForcedPlay = false
            )
        }
        assertTrue("Expected createVideo to succeed", createResult.isSuccess)

        val emittedVideos = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeVideos(aliceUid).first { list -> list.any { it.id == videoId } }
        }
        assertTrue(emittedVideos.any { it.id == videoId && it.title == "Showroom Ad" })
    }

    @Test
    fun getVideoById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = SignageCloudRepository(firestore)
        val videoId = "vid_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createVideo(
                videoId = videoId,
                title = "Alice Private Campaign",
                videoUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = SignageCloudRepository(firestore)
        val crossUserResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getVideoById(targetUserId = aliceUid, videoId = videoId)
        }
        assertTrue("Expected cross-user read to fail", crossUserResult.isFailure)
        val fsEx = crossUserResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeVideos_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = SignageCloudRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeVideos(aliceUid).first()
            }
            fail("Expected observeVideos to throw when unauthenticated")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
