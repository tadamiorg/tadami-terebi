package com.sf.tadami.terebi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.sf.tadami.terebi.player.TvThemeColors

private val DefaultColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2A30),
    onSurfaceVariant = Color(0xFFC6C5D0),
)

@Composable
fun TadamiTerebiTheme(
    colors: TvThemeColors? = null,
    content: @Composable () -> Unit,
) {
    val scheme = if (colors != null) {
        darkColorScheme(
            primary = Color(colors.primary),
            onPrimary = Color(colors.onPrimary),
            secondary = Color(colors.secondary),
            onSecondary = Color(colors.onSecondary),
            background = Color(colors.background),
            onBackground = Color(colors.onBackground),
            surface = Color(colors.surface),
            onSurface = Color(colors.onSurface),
            surfaceVariant = Color(colors.surfaceVariant),
            onSurfaceVariant = Color(colors.onSurfaceVariant),
        )
    } else {
        DefaultColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
