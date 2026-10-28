package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlaylistResponse
import com.example.data.repository.SignageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SignageTV", appName)
  }

  @Test
  fun `default playlist has valid looping items and IMA ads`() {
    val playlist = SignageRepository.createDefaultPlaylist()
    assertTrue(playlist.items.isNotEmpty())
    assertNotNull(playlist.defaultAdTagUrl)
    val hasAdItem = playlist.items.any { !it.adTagUrl.isNullOrBlank() }
    assertTrue(hasAdItem)
  }

  @Test
  fun `json serialization test for playlist schema`() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(PlaylistResponse::class.java)

    val defaultPlaylist = SignageRepository.createDefaultPlaylist()
    val json = adapter.toJson(defaultPlaylist)
    assertNotNull(json)

    val parsed = adapter.fromJson(json)
    assertNotNull(parsed)
    assertEquals(defaultPlaylist.id, parsed?.id)
    assertEquals(defaultPlaylist.items.size, parsed?.items?.size)
  }

  @Test
  fun `signage orientation supports landscape and portrait totem modes`() {
    val portrait = com.example.ui.orientation.SignageOrientation.fromId("PORTRAIT")
    assertEquals(com.example.ui.orientation.SignageOrientation.PORTRAIT, portrait)

    val landscape = com.example.ui.orientation.SignageOrientation.fromId("LANDSCAPE")
    assertEquals(com.example.ui.orientation.SignageOrientation.LANDSCAPE, landscape)

    val reverseLandscape = com.example.ui.orientation.SignageOrientation.fromId("REVERSE_LANDSCAPE")
    assertEquals(com.example.ui.orientation.SignageOrientation.REVERSE_LANDSCAPE, reverseLandscape)

    val reversePortrait = com.example.ui.orientation.SignageOrientation.fromId("REVERSE_PORTRAIT")
    assertEquals(com.example.ui.orientation.SignageOrientation.REVERSE_PORTRAIT, reversePortrait)
  }
}

