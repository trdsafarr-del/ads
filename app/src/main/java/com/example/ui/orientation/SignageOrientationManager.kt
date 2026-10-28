package com.example.ui.orientation

import android.app.Activity
import android.content.pm.ActivityInfo

enum class SignageOrientation(
    val id: String,
    val title: String,
    val description: String,
    val activityOrientation: Int
) {
    LANDSCAPE(
        id = "LANDSCAPE",
        title = "Landscape (Horizontal)",
        description = "Standard 16:9 TV & Wall Displays",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    ),
    PORTRAIT(
        id = "PORTRAIT",
        title = "Portrait (Vertical)",
        description = "9:16 Totems, Menu Boards & Kiosk Pillars",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ),
    REVERSE_LANDSCAPE(
        id = "REVERSE_LANDSCAPE",
        title = "Reverse Landscape",
        description = "180° Upside-down Ceiling Mounts",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    ),
    REVERSE_PORTRAIT(
        id = "REVERSE_PORTRAIT",
        title = "Reverse Portrait",
        description = "270° Inverted Vertical Displays",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    ),
    AUTO(
        id = "AUTO",
        title = "Auto / Full Sensor",
        description = "Follows Device Gyroscope / Hardware Sensor",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    );

    companion object {
        fun fromId(id: String): SignageOrientation {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LANDSCAPE
        }

        fun applyOrientation(activity: Activity, orientation: SignageOrientation) {
            try {
                activity.requestedOrientation = orientation.activityOrientation
            } catch (_: Exception) {}
        }
    }
}
