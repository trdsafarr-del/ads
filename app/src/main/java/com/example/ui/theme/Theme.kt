package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SignageColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = SignageSurfaceVariant,
    onPrimaryContainer = NeonCyan,
    secondary = AmberAccent,
    onSecondary = Color.Black,
    secondaryContainer = SignageCard,
    onSecondaryContainer = AmberAccent,
    tertiary = ElectricBlue,
    onTertiary = Color.White,
    background = SignageDarkBg,
    onBackground = TextPrimary,
    surface = SignageSurface,
    onSurface = TextPrimary,
    surfaceVariant = SignageSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = CoralRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SignageColorScheme,
        typography = Typography,
        content = content
    )
}
