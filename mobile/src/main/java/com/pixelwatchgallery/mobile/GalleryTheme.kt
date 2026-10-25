package com.pixelwatchgallery.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GalleryColors = darkColorScheme(
    primary = Color(0xFFB2D9BD), onPrimary = Color(0xFF183A25),
    primaryContainer = Color(0xFF294A35), onPrimaryContainer = Color(0xFFD4EFDC),
    secondary = Color(0xFFBBC8BE), onSecondary = Color(0xFF26332B),
    secondaryContainer = Color(0xFF303E35), onSecondaryContainer = Color(0xFFD7E6DB),
    tertiary = Color(0xFFE3C48E), onTertiary = Color(0xFF412E08),
    background = Color(0xFF101312), onBackground = Color(0xFFE4E8E3),
    surface = Color(0xFF101312), onSurface = Color(0xFFE4E8E3),
    surfaceVariant = Color(0xFF414943), onSurfaceVariant = Color(0xFFBEC8BF),
    surfaceContainerLowest = Color(0xFF0B0F0D), surfaceContainerLow = Color(0xFF181D19),
    surfaceContainer = Color(0xFF1D231F), surfaceContainerHigh = Color(0xFF272D28),
    surfaceContainerHighest = Color(0xFF323832),
    outline = Color(0xFF89958B), outlineVariant = Color(0xFF414943),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF5A2423), onErrorContainer = Color(0xFFFFDAD5)
)

@Composable
internal fun GalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GalleryColors, content = content)
}
