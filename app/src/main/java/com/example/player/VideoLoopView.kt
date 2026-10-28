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
import com.example.ui.orientation.SignageOrientation

@OptIn(UnstableApi::class)
@Composable
fun VideoLoopView(
    playerManager: SignagePlayerManager,
    scaleMode: String = "ZOOM",
    displayOrientation: SignageOrientation = SignageOrientation.LANDSCAPE,
    videoOrientationDegrees: Int = 0,
    modifier: Modifier = Modifier
) {
    val targetResizeMode = when (scaleMode.uppercase()) {
        "FIT" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        "FILL", "STRETCH" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // On fixed-HDMI Android TVs or fixed-aspect streaming displays where the OS window stays landscape
        // (maxWidth > maxHeight) even when PORTRAIT or REVERSE_PORTRAIT is requested, apply visual canvas rotation
        // so selecting Screen Orientation on the Web Dashboard ALWAYS rotates the output immediately.
        val isWindowLandscape = maxWidth >= maxHeight
        val screenRotationFallback = when (displayOrientation) {
            SignageOrientation.PORTRAIT -> if (isWindowLandscape) 90 else 0
            SignageOrientation.REVERSE_PORTRAIT -> if (isWindowLandscape) 270 else 0
            SignageOrientation.REVERSE_LANDSCAPE -> 180
            else -> 0
        }

        val combinedDegrees = (((videoOrientationDegrees + screenRotationFallback) % 360) + 360) % 360
        val isQuarterTurn = combinedDegrees == 90 || combinedDegrees == 270

        val playerModifier = if (isQuarterTurn) {
            Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .graphicsLayer {
                    rotationZ = combinedDegrees.toFloat()
                }
        } else if (combinedDegrees == 180) {
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
