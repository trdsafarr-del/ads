package com.example.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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

    var onMediaItemChangedListener: ((currentIndex: Int, currentItem: PlaylistItem) -> Unit)? = null

    fun initialize(view: PlayerView, activity: Activity? = null) {
        this.playerView = view
        this.hostActivity = activity

        // 1. Initialize Google IMA AdsLoader for VAST pre-roll/mid-roll tags
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

        // 2. Configure aggressive buffer preload (zero black screen transitions)
        // Next video starts buffering 30s ahead while current is playing
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Build ExoPlayer
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

                        // Send health heartbeat ping to Watchdog service
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
                        // Judder elimination: query video format and match TV refresh rate
                        val format = exoPlayer?.videoFormat
                        if (format != null && format.frameRate > 0f) {
                            hostActivity?.let { act ->
                                TvFrameRateHelper.matchDisplayRefreshRate(act, format.frameRate)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Recovering from playback notice (${error.errorCodeName}): ${error.message}")
                        // Switch to guaranteed local fallback MP4 so the player never enters an error loop
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
            loadPlaylist(currentPlaylistItems)
        }
    }

    /**
     * Loads playlist into ExoPlayer using ConcatenatingMediaSource with Google IMA VAST ads
     * and REPEAT_MODE_ALL for gapless looping playback.
     */
    fun loadPlaylist(items: List<PlaylistItem>) {
        this.currentPlaylistItems = items
        val player = exoPlayer ?: return
        val view = playerView ?: return
        val adsLoader = imaAdsLoader ?: return

        if (items.isEmpty()) return

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

        player.setMediaSource(concatenatingMediaSource)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true

        _currentMediaIndex.value = 0
        val first = items.firstOrNull()
        _currentTitle.value = first?.title ?: ""
        _currentMediaId.value = first?.id ?: ""
        Log.i(TAG, "Loaded ${items.size} videos into gapless ConcatenatingMediaSource loop")
    }

    /**
     * Prioritizes local cached file on disk; falls back seamlessly to local synthesized MP4
     * for restricted URLs or resilient streaming URL.
     */
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
                Log.d(TAG, "Playing restricted stream from local fallback MP4: ${fallbackFile.absolutePath}")
                return Uri.fromFile(fallbackFile)
            }
        }
        Log.d(TAG, "Playing from remote stream: ${item.videoUrl}")
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

    fun toggleMute() {
        val player = exoPlayer ?: return
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        player.volume = if (newMute) 0f else 1f
    }

    // Lifecycle events
    fun onPause() {
        // In signage TV kiosk, continue playing or pause if screen turns off
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
        Log.d(TAG, "Releasing SignagePlayerManager")
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
