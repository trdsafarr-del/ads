package com.example.ui.orientation

import android.app.Activity
import android.content.pm.ActivityInfo

enum class SignageOrientation(
    val id: String,
    val title: String,
    val shortLabel: String,
    val description: String,
    val activityOrientation: Int,
    val fallbackRotationDegrees: Int
) {
    LANDSCAPE(
        id = "LANDSCAPE",
        title = "Landscape (0° Horizontal)",
        shortLabel = "Landscape",
        description = "Standard 16:9 TV & Wall Displays",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        fallbackRotationDegrees = 0
    ),
    PORTRAIT(
        id = "PORTRAIT",
        title = "Portrait (90° Vertical)",
        shortLabel = "Portrait",
        description = "9:16 Totems, Menu Boards & Kiosk Pillars",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        fallbackRotationDegrees = 90
    ),
    REVERSE_LANDSCAPE(
        id = "REVERSE_LANDSCAPE",
        title = "Reverse Landscape (180°)",
        shortLabel = "Rev Landscape",
        description = "180° Upside-down Ceiling Mounts",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        fallbackRotationDegrees = 180
    ),
    REVERSE_PORTRAIT(
        id = "REVERSE_PORTRAIT",
        title = "Reverse Portrait (270°)",
        shortLabel = "Rev Portrait",
        description = "270° Inverted Vertical Displays",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        fallbackRotationDegrees = 270
    ),
    AUTO(
        id = "AUTO",
        title = "Auto / Full Sensor",
        shortLabel = "Auto",
        description = "Follows Device Gyroscope / Hardware Sensor",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        fallbackRotationDegrees = 0
    );

    companion object {
        fun fromId(id: String): SignageOrientation {
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: LANDSCAPE
        }

        fun applyOrientation(activity: Activity, orientation: SignageOrientation) {
            try {
                if (activity.requestedOrientation != orientation.activityOrientation) {
                    activity.requestedOrientation = orientation.activityOrientation
                }
            } catch (_: Exception) {}
        }
    }
}

enum class VideoOrientation(
    val degrees: Int,
    val title: String,
    val shortLabel: String,
    val description: String
) {
    NORMAL_0(
        degrees = 0,
        title = "0° Normal (Landscape Video)",
        shortLabel = "0° Normal",
        description = "Standard horizontal video playback (0°)"
    ),
    ROTATE_90(
        degrees = 90,
        title = "90° Rotate Right (Portrait CW)",
        shortLabel = "90° Right",
        description = "Rotates video 90° clockwise for vertical TVs"
    ),
    ROTATE_180(
        degrees = 180,
        title = "180° Upside-Down (Inverted)",
        shortLabel = "180° Flip",
        description = "Flips video 180° for inverted/ceiling mounts"
    ),
    ROTATE_270(
        degrees = 270,
        title = "270° Rotate Left (Portrait CCW)",
        shortLabel = "270° Left",
        description = "Rotates video 270° counter-clockwise for vertical TVs"
    );

    companion object {
        fun fromDegrees(degrees: Int): VideoOrientation {
            val normalized = ((degrees % 360) + 360) % 360
            return entries.firstOrNull { it.degrees == normalized } ?: NORMAL_0
        }
    }
}
