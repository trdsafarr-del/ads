package com.example.player

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun VideoLoopView(
    playerManager: SignagePlayerManager,
    scaleMode: String = "ZOOM",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val targetResizeMode = when (scaleMode.uppercase()) {
        "FIT" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        "FILL" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = false
                resizeMode = targetResizeMode
                keepScreenOn = true
                playerManager.initialize(this, ctx as? Activity)
            }
        },
        update = { view ->
            view.resizeMode = targetResizeMode
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            playerManager.release()
        }
    }
}
