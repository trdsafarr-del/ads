package com.example.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import kotlin.math.abs

object TvFrameRateHelper {

    private const val TAG = "TvFrameRateHelper"

    /**
     * Matches the Android TV display refresh rate to the video's frame rate (e.g. 24fps, 50fps, 60fps)
     * using Display.Mode and window attributes to eliminate motion judder on large screens.
     */
    fun matchDisplayRefreshRate(activity: Activity, targetFps: Float) {
        if (targetFps <= 0f) return

        val window: Window = activity.window
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

        if (display == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val supportedModes = display.supportedModes
                val currentMode = display.mode

                // Find matching mode with identical resolution but closest refresh rate
                var bestMode: Display.Mode? = null
                var minDiff = Float.MAX_VALUE

                for (mode in supportedModes) {
                    if (mode.physicalWidth == currentMode.physicalWidth &&
                        mode.physicalHeight == currentMode.physicalHeight
                    ) {
                        // Check if refresh rate is an exact integer multiple or match (e.g. 24Hz or 48Hz or 120Hz for 24fps)
                        val rate = mode.refreshRate
                        val isMultiple = (rate % targetFps < 0.5f) || (targetFps % rate < 0.5f)
                        val diff = abs(rate - targetFps)

                        if (isMultiple && diff < minDiff) {
                            minDiff = diff
                            bestMode = mode
                        }
                    }
                }

                if (bestMode != null && bestMode.modeId != currentMode.modeId) {
                    Log.i(TAG, "Switching TV Display.Mode to ${bestMode.physicalWidth}x${bestMode.physicalHeight} @ ${bestMode.refreshRate}Hz for ${targetFps}fps video")
                    val params = window.attributes
                    params.preferredDisplayModeId = bestMode.modeId
                    window.attributes = params
                }
            } else {
                // Fallback for API < 23
                val params = window.attributes
                @Suppress("DEPRECATION")
                params.preferredRefreshRate = targetFps
                window.attributes = params
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame rate matching not supported or failed: ${e.message}")
        }
    }
}
