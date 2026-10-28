package com.example.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.data.model.PlaylistItem
import com.example.service.WatchdogService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@OptIn(UnstableApi::class)
class SignagePlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var imaAdsLoader: ImaAdsLoader? = null
    private var playerView: PlayerView? = null
    private var hostActivity: Activity? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMediaIndex = MutableStateFlow(0)
    val currentMediaIndex: StateFlow<Int> = _currentMediaIndex.asStateFlow()

    private val _currentMediaId = MutableStateFlow("")
    val currentMediaId: StateFlow<String> = _currentMediaId.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isAdPlaying = MutableStateFlow(false)
    val isAdPlaying: StateFlow<Boolean> = _isAdPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var currentPlaylistItems: List<PlaylistItem> = emptyList()
    private var lastLoadedSignature: String = ""

    var onMediaItemChangedListener: ((currentIndex: Int, currentItem: PlaylistItem) -> Unit)? = null

    fun initialize(view: PlayerView, activity: Activity? = null) {
        this.playerView = view
        this.hostActivity = activity

        imaAdsLoader = ImaAdsLoader.Builder(context)
            .setAdErrorListener { adErrorEvent ->
                Log.d(TAG, "IMA Ad Notice: ${adErrorEvent.error?.message}")
            }
            .setAdEventListener { adEvent ->
                Log.d(TAG, "IMA Ad Event: ${adEvent.type}")
                _isAdPlaying.value = exoPlayer?.isPlayingAd == true
            }
            .build()

        val dataSourceFactory = ResilientSignageDataSource.Factory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLocalAdInsertionComponents({ imaAdsLoader }, view)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                volume = if (_isMuted.value) 0f else 1f

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        _isPlaying.value = (playbackState == Player.STATE_READY && playWhenReady)
                        _isAdPlaying.value = isPlayingAd
                        WatchdogService.ping(context)
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        _isAdPlaying.value = isPlayingAd
                        WatchdogService.ping(context)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val index = currentMediaItemIndex
                        _currentMediaIndex.value = index
                        if (index in currentPlaylistItems.indices) {
                            val item = currentPlaylistItems[index]
                            _currentTitle.value = item.title
                            _currentMediaId.value = item.id
                            onMediaItemChangedListener?.invoke(index, item)
                        }
                        _isAdPlaying.value = isPlayingAd
                        WatchdogService.ping(context)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val format = exoPlayer?.videoFormat
                        if (format != null && format.frameRate > 0f) {
                            hostActivity?.let { act ->
                                TvFrameRateHelper.matchDisplayRefreshRate(act, format.frameRate)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Recovering from playback notice (${error.errorCodeName}): ${error.message}")
                        val fallbackFile = SignageSampleVideoGenerator.getOrCreateFallbackVideo(context)
                        if (SignageSampleVideoGenerator.isValidMp4File(fallbackFile)) {
                            val fallbackItem = MediaItem.fromUri(Uri.fromFile(fallbackFile))
                            setMediaItem(fallbackItem)
                            repeatMode = Player.REPEAT_MODE_ALL
                            prepare()
                            play()
                        } else {
                            seekToNextMediaItem()
                            prepare()
                            play()
                        }
                    }
                })
            }

        this.exoPlayer = player
        view.player = player
        imaAdsLoader?.setPlayer(player)

        if (currentPlaylistItems.isNotEmpty()) {
            loadPlaylist(currentPlaylistItems, forceReload = true)
        }
    }

    /**
     * Loads playlist into ExoPlayer using ConcatenatingMediaSource with Google IMA VAST ads
     * and REPEAT_MODE_ALL for gapless looping playback. Supports jumping directly to a pushed video.
     */
    fun loadPlaylist(
        items: List<PlaylistItem>,
        startVideoId: String? = null,
        forceReload: Boolean = false
    ) {
        if (items.isEmpty()) return
        val signature = items.joinToString("|") { "${it.id}:${it.videoUrl}:${it.localFilePath ?: ""}" }
        val player = exoPlayer
        val view = playerView
        val adsLoader = imaAdsLoader

        this.currentPlaylistItems = items

        if (player == null || view == null || adsLoader == null) return

        // If only startVideoId changed and playlist items are identical, just jump to that video
        if (!forceReload && signature == lastLoadedSignature && player.mediaItemCount == items.size) {
            if (!startVideoId.isNullOrBlank()) {
                playVideoById(startVideoId)
            }
            return
        }
        lastLoadedSignature = signature

        val mediaSourceFactory = DefaultMediaSourceFactory(ResilientSignageDataSource.Factory(context))
            .setLocalAdInsertionComponents({ adsLoader }, view)

        val concatenatingMediaSource = ConcatenatingMediaSource()

        for (item in items) {
            val uri = resolveUri(item)
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(item.id)

            if (!item.adTagUrl.isNullOrBlank()) {
                val adTagUri = Uri.parse(item.adTagUrl)
                mediaItemBuilder.setAdsConfiguration(
                    MediaItem.AdsConfiguration.Builder(adTagUri).build()
                )
            }

            val mediaItem = mediaItemBuilder.build()
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            concatenatingMediaSource.addMediaSource(mediaSource)
        }

        val targetIndex = if (!startVideoId.isNullOrBlank()) {
            items.indexOfFirst { it.id == startVideoId }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }

        player.setMediaSource(concatenatingMediaSource)
        if (targetIndex > 0) {
            player.seekTo(targetIndex, 0L)
        }
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
        player.play()

        _currentMediaIndex.value = targetIndex
        val active = items.getOrNull(targetIndex) ?: items.firstOrNull()
        _currentTitle.value = active?.title ?: ""
        _currentMediaId.value = active?.id ?: ""
        Log.i(TAG, "Loaded ${items.size} videos into gapless loop (starting at index=$targetIndex, id=${active?.id})")
    }

    /**
     * Immediately seeks to and plays a specific video by ID (used when a video is pushed from CMS or Admin).
     */
    fun playVideoById(videoId: String) {
        val player = exoPlayer ?: return
        val targetIndex = currentPlaylistItems.indexOfFirst { it.id == videoId }
        if (targetIndex >= 0 && targetIndex < player.mediaItemCount) {
            player.seekTo(targetIndex, 0L)
            player.playWhenReady = true
            player.play()
            _currentMediaIndex.value = targetIndex
            val item = currentPlaylistItems[targetIndex]
            _currentTitle.value = item.title
            _currentMediaId.value = item.id
            Log.i(TAG, "Auto-playing pushed video immediately: ${item.title} (index=$targetIndex)")
        }
    }

    private fun resolveUri(item: PlaylistItem): Uri {
        if (!item.localFilePath.isNullOrBlank()) {
            val file = File(item.localFilePath)
            if (SignageSampleVideoGenerator.isValidMp4File(file)) {
                Log.d(TAG, "Playing from offline cache: ${file.absolutePath}")
                return Uri.fromFile(file)
            }
        }
        if (ResilientSignageDataSource.isKnownRestrictedUrl(item.videoUrl)) {
            val fallbackFile = SignageSampleVideoGenerator.getOrCreateFallbackVideo(context, item.id)
            if (SignageSampleVideoGenerator.isValidMp4File(fallbackFile)) {
                return Uri.fromFile(fallbackFile)
            }
        }
        return Uri.parse(item.videoUrl)
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipNext() {
        exoPlayer?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        exoPlayer?.seekToPreviousMediaItem()
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        exoPlayer?.volume = if (muted) 0f else 1f
    }

    fun toggleMute(): Boolean {
        val newMute = !_isMuted.value
        setMuted(newMute)
        return newMute
    }

    fun onPause() {
        Log.d(TAG, "Lifecycle onPause")
    }

    fun onResume() {
        Log.d(TAG, "Lifecycle onResume")
        exoPlayer?.play()
    }

    fun onStop() {
        Log.d(TAG, "Lifecycle onStop")
    }

    fun release() {
        try {
            imaAdsLoader?.setPlayer(null)
            imaAdsLoader?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing IMA: ${e.message}")
        }
        imaAdsLoader = null

        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        playerView = null
        hostActivity = null
    }

    companion object {
        private const val TAG = "SignagePlayerManager"
    }
}
