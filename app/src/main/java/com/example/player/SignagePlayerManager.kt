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
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.data.model.PlaylistItem
import com.example.service.WatchdogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class SignagePlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var hostActivity: Activity? = null

    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bufferingWatchdogJob: Job? = null

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

        val dataSourceFactory = ResilientSignageDataSource.Factory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_500,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
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
                        val isBuf = (playbackState == Player.STATE_BUFFERING)
                        _isBuffering.value = isBuf
                        _isPlaying.value = (playbackState == Player.STATE_READY && playWhenReady)
                        _isAdPlaying.value = isPlayingAd
                        WatchdogService.ping(context)

                        bufferingWatchdogJob?.cancel()
                        if (isBuf) {
                            bufferingWatchdogJob = playerScope.launch {
                                delay(3500L)
                                if (_isBuffering.value && exoPlayer != null) {
                                    Log.w(TAG, "Buffering timeout exceeded (3.5s). Rescuing playback immediately...")
                                    recoverStalledPlayback()
                                }
                            }
                        }
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
                        Log.w(TAG, "Playback error noticed (${error.errorCodeName}): ${error.message}. Auto-recovering...")
                        recoverStalledPlayback()
                    }
                })
            }

        this.exoPlayer = player
        view.player = player

        if (currentPlaylistItems.isNotEmpty()) {
            loadPlaylist(currentPlaylistItems, forceReload = true)
        }
    }

    /**
     * Loads playlist into ExoPlayer using ConcatenatingMediaSource
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

        this.currentPlaylistItems = items

        if (player == null || view == null) return

        // If only startVideoId changed and playlist items are identical, just jump to that video
        if (!forceReload && signature == lastLoadedSignature && player.mediaItemCount == items.size) {
            if (!startVideoId.isNullOrBlank()) {
                playVideoById(startVideoId)
            }
            return
        }
        lastLoadedSignature = signature

        val mediaSourceFactory = DefaultMediaSourceFactory(ResilientSignageDataSource.Factory(context))
        val concatenatingMediaSource = ConcatenatingMediaSource()

        for (item in items) {
            val uri = resolveUri(item)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(item.id)
                .build()
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
     * Immediately interrupts whatever is currently playing (including VAST ads or previous streams)
     * and starts playing the requested video(s) right away at 0ms.
     */
    fun forcePlayImmediately(items: List<PlaylistItem>, startVideoId: String?) {
        val player = exoPlayer ?: return
        if (items.isEmpty()) return

        lastLoadedSignature = ""
        currentPlaylistItems = items
        _isAdPlaying.value = false

        val directFactory = DefaultMediaSourceFactory(ResilientSignageDataSource.Factory(context))
        val concatenatingMediaSource = ConcatenatingMediaSource()

        for (item in items) {
            val uri = resolveUri(item)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(item.id)
                .build()
            val mediaSource = directFactory.createMediaSource(mediaItem)
            concatenatingMediaSource.addMediaSource(mediaSource)
        }

        val targetIndex = if (!startVideoId.isNullOrBlank()) {
            items.indexOfFirst { it.id == startVideoId }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }

        player.stop()
        player.clearMediaItems()
        player.setMediaSource(concatenatingMediaSource)
        if (targetIndex > 0) {
            player.seekTo(targetIndex, 0L)
        } else {
            player.seekTo(0, 0L)
        }
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
        player.play()

        lastLoadedSignature = items.joinToString("|") {
            "${it.id}:${it.localFilePath ?: it.videoUrl}:${it.adTagUrl ?: ""}"
        }
        _currentMediaIndex.value = targetIndex
        val active = items.getOrNull(targetIndex) ?: items.firstOrNull()
        _currentTitle.value = active?.title ?: ""
        _currentMediaId.value = active?.id ?: ""
        Log.i(TAG, "Force-playing ${items.size} videos immediately (starting at index=$targetIndex, id=${active?.id})")
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
        } else if (currentPlaylistItems.isNotEmpty()) {
            forcePlayImmediately(currentPlaylistItems, videoId)
        }
    }

    fun recoverStalledPlayback() {
        val player = exoPlayer ?: return
        val count = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        val currentItem = currentPlaylistItems.getOrNull(currentIndex)
        if (currentItem != null) {
            ResilientSignageDataSource.markBroken(currentItem.videoUrl)
        }
        Log.w(TAG, "Executing recoverStalledPlayback (itemCount=$count, currentIndex=$currentIndex, item=${currentItem?.title})")
        if (count > 1) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            val fallbackUri = Uri.parse(ResilientSignageDataSource.REAL_BACKUP_STREAM_URL)
            val fallbackItem = MediaItem.Builder()
                .setUri(fallbackUri)
                .setMediaId(currentItem?.id ?: "fallback_signage")
                .build()
            player.setMediaItem(fallbackItem)
            player.prepare()
            player.play()
        }
        _isBuffering.value = false
    }

    private fun resolveUri(item: PlaylistItem): Uri {
        if (!item.localFilePath.isNullOrBlank()) {
            val file = File(item.localFilePath)
            if (file.exists() && file.length() > 1024L && SignageSampleVideoGenerator.isValidMp4File(file)) {
                Log.d(TAG, "Playing from offline cache: ${file.absolutePath}")
                return Uri.fromFile(file)
            }
        }
        if (ResilientSignageDataSource.isBrokenOrRestrictedUrl(item.videoUrl)) {
            val rewritten = ResilientSignageDataSource.rewriteRestrictedUrl(item.videoUrl)
            return Uri.parse(rewritten)
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
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
        try {
            playerScope.cancel()
        } catch (_: Exception) {}
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
