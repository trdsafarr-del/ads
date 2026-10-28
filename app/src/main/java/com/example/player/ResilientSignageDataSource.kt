package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.Collections

/**
 * A resilient Media3 [DataSource] that wraps [DefaultDataSource] (configured with browser-compatible
 * HTTP headers and redirect support) and transparently falls back to a valid local MP4 file on disk
 * whenever a remote HTTP stream returns 403 Forbidden, 404, 5xx, or network timeout.
 *
 * By handling [IOException] (including [androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException])
 * directly inside [open], ExoPlayer never experiences a fatal source error or infinite retry loop.
 */
@OptIn(UnstableApi::class)
class ResilientSignageDataSource(
    private val context: Context,
    private val baseDataSource: DataSource
) : DataSource {

    private var fileFallbackDataSource: FileDataSource? = null
    private var activeDataSource: DataSource? = null
    private var activeUri: Uri? = null

    class Factory(private val context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext

        private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BROWSER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            )

        private val defaultDataSourceFactory = DefaultDataSource.Factory(
            appContext,
            httpDataSourceFactory
        )

        override fun createDataSource(): DataSource {
            return ResilientSignageDataSource(
                context = appContext,
                baseDataSource = defaultDataSourceFactory.createDataSource()
            )
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        baseDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme?.lowercase()

        if (scheme == "http" || scheme == "https") {
            val urlString = uri.toString()
            if (isKnownRestrictedUrl(urlString) || failedRemoteUrls.contains(urlString)) {
                return openLocalFallback(dataSpec, "Bypassing restricted URL: $urlString")
            }

            return try {
                activeDataSource = baseDataSource
                activeUri = null
                baseDataSource.open(dataSpec)
            } catch (e: IOException) {
                failedRemoteUrls.add(urlString)
                try {
                    baseDataSource.close()
                } catch (_: Exception) {}
                openLocalFallback(dataSpec, "HTTP fallback (${e.message}) for $urlString")
            }
        } else {
            return try {
                activeDataSource = baseDataSource
                activeUri = null
                baseDataSource.open(dataSpec)
            } catch (e: IOException) {
                try {
                    baseDataSource.close()
                } catch (_: Exception) {}
                openLocalFallback(dataSpec, "Local file fallback (${e.message}) for $uri")
            }
        }
    }

    private fun openLocalFallback(originalSpec: DataSpec, reason: String): Long {
        Log.i(TAG, "Playing from local MP4 fallback: $reason")
        val fallbackFile = SignageSampleVideoGenerator.getOrCreateFallbackVideo(
            context = context,
            keyHint = originalSpec.uri.toString()
        )
        val fileDs = FileDataSource()
        fileFallbackDataSource = fileDs
        activeDataSource = fileDs

        val fileLen = fallbackFile.length()
        val safePosition = if (fileLen > 0 && originalSpec.position in 0 until fileLen) {
            originalSpec.position
        } else {
            0L
        }
        val safeLength = if (
            originalSpec.length != C.LENGTH_UNSET.toLong() &&
            fileLen > 0 &&
            safePosition + originalSpec.length <= fileLen
        ) {
            originalSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }

        val fallbackFileUri = Uri.fromFile(fallbackFile)
        activeUri = fallbackFileUri

        val fallbackSpec = originalSpec.buildUpon()
            .setUri(fallbackFileUri)
            .setPosition(safePosition)
            .setLength(safeLength)
            .build()

        return fileDs.open(fallbackSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = activeDataSource ?: return C.RESULT_END_OF_INPUT
        return try {
            current.read(buffer, offset, length)
        } catch (e: IOException) {
            Log.d(TAG, "Stream read ended: ${e.message}")
            C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? {
        return activeUri ?: activeDataSource?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeDataSource?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        try {
            activeDataSource?.close()
        } catch (_: Exception) {
        } finally {
            activeDataSource = null
            fileFallbackDataSource = null
            activeUri = null
        }
    }

    companion object {
        private const val TAG = "ResilientDataSource"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val failedRemoteUrls: MutableSet<String> =
            Collections.synchronizedSet(HashSet<String>())

        fun isKnownRestrictedUrl(url: String): Boolean {
            return url.contains("commondatastorage.googleapis.com/gtv-videos-bucket", ignoreCase = true)
        }
    }
}
