package com.example.player

import android.app.Activity
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R

@OptIn(UnstableApi::class)
@Composable
fun VideoLoopView(
    playerManager: SignagePlayerManager,
    scaleMode: String = "ZOOM",
    videoOrientationDegrees: Int = 0,
    modifier: Modifier = Modifier
) {
    val targetResizeMode = when (scaleMode.uppercase()) {
        "FIT" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        "FILL", "STRETCH" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    val normalizedDegrees = ((videoOrientationDegrees % 360) + 360) % 360
    val isQuarterTurn = normalizedDegrees == 90 || normalizedDegrees == 270

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val playerModifier = if (isQuarterTurn) {
            Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .graphicsLayer {
                    rotationZ = normalizedDegrees.toFloat()
                }
        } else if (normalizedDegrees == 180) {
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = 180f
                }
        } else {
            Modifier.fillMaxSize()
        }

        AndroidView(
            modifier = playerModifier,
            factory = { ctx ->
                val playerView = LayoutInflater.from(ctx)
                    .inflate(R.layout.signage_player_view, null, false) as PlayerView
                playerView.apply {
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
    }

    DisposableEffect(Unit) {
        onDispose {
            playerManager.release()
        }
    }
}
