package com.ranorac.tjtimetable.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Dark / light / follow-system, mirroring GitHub's own three-way setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Material 3 scheme rebuilt from GitHub Primer tokens, so M3 widgets (TopAppBar,
 * Card, FilterChip, …) come out looking like GitHub rather than Material You.
 */
private fun githubColorScheme(g: GitHubColors) = if (g.isDark) {
    darkColorScheme(
        primary = g.accentFg,
        onPrimary = Color.White,
        primaryContainer = g.accentSubtle,
        onPrimaryContainer = Color.White,
        secondary = g.fgMuted,
        onSecondary = Color.White,
        secondaryContainer = g.neutralSubtle,
        onSecondaryContainer = g.fgDefault,
        tertiary = g.doneFg,
        onTertiary = Color.White,
        tertiaryContainer = g.doneSubtle,
        onTertiaryContainer = Color.White,
        background = g.canvasDefault,
        onBackground = g.fgDefault,
        surface = g.canvasDefault,
        onSurface = g.fgDefault,
        surfaceVariant = g.canvasSubtle,
        onSurfaceVariant = g.fgMuted,
        surfaceContainer = g.canvasSubtle,
        surfaceContainerHigh = g.canvasSubtle,
        surfaceContainerLow = g.canvasDefault,
        surfaceContainerLowest = g.canvasInset,
        surfaceContainerHighest = g.neutralSubtle,
        outline = g.borderDefault,
        outlineVariant = g.borderMuted,
        error = g.dangerFg,
        onError = Color.White,
        errorContainer = g.dangerSubtle,
        onErrorContainer = Color.White,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = g.accentFg,
        onPrimary = Color.White,
        primaryContainer = g.accentSubtle,
        onPrimaryContainer = g.fgDefault,
        secondary = g.fgMuted,
        onSecondary = Color.White,
        secondaryContainer = g.neutralSubtle,
        onSecondaryContainer = g.fgDefault,
        tertiary = g.doneFg,
        onTertiary = Color.White,
        tertiaryContainer = g.doneSubtle,
        onTertiaryContainer = g.fgDefault,
        background = g.canvasDefault,
        onBackground = g.fgDefault,
        surface = g.canvasDefault,
        onSurface = g.fgDefault,
        surfaceVariant = g.canvasSubtle,
        onSurfaceVariant = g.fgMuted,
        surfaceContainer = g.canvasSubtle,
        surfaceContainerHigh = g.canvasSubtle,
        surfaceContainerLow = g.canvasDefault,
        surfaceContainerLowest = Color.White,
        surfaceContainerHighest = g.neutralSubtle,
        outline = g.borderDefault,
        outlineVariant = g.borderMuted,
        error = g.dangerFg,
        onError = Color.White,
        errorContainer = g.dangerSubtle,
        onErrorContainer = g.fgDefault,
        scrim = Color.Black,
    )
}

/**
 * @param dynamicColor is intentionally unsupported: GitHub's palette is the
 * point of the design, so Material You wallpaper colours would fight it.
 */
@Composable
fun TJTimetableTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val github = if (dark) DarkGitHub else LightGitHub

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalGitHubColors provides github) {
        MaterialTheme(
            colorScheme = githubColorScheme(github),
            typography = TJTypography,
            shapes = TJShapes,
            content = content,
        )
    }
}
