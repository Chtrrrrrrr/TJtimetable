package com.ranorac.tjtimetable.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * GitHub Primer design tokens.
 *
 * The app is deliberately styled after GitHub's UI: 1px hairline borders, a
 * subtle canvas, 6dp radii, and a small set of semantic accents. Material 3's
 * colour scheme is derived from these tokens in [Theme.kt], so Material
 * components inherit the GitHub look instead of the default purple.
 *
 * Values are taken from Primer's light and dark primitives.
 */
@Immutable
data class GitHubColors(
    val canvasDefault: Color,
    val canvasSubtle: Color,
    val canvasInset: Color,
    val canvasOverlay: Color,
    val borderDefault: Color,
    val borderMuted: Color,
    val fgDefault: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val accentFg: Color,
    val accentEmphasis: Color,
    val accentSubtle: Color,
    val successFg: Color,
    val successSubtle: Color,
    val attentionFg: Color,
    val attentionSubtle: Color,
    val dangerFg: Color,
    val dangerSubtle: Color,
    val doneFg: Color,
    val doneSubtle: Color,
    val neutralSubtle: Color,
    val isDark: Boolean,
)

val LightGitHub = GitHubColors(
    canvasDefault = Color(0xFFFFFFFF),
    canvasSubtle = Color(0xFFF6F8FA),
    canvasInset = Color(0xFFF6F8FA),
    canvasOverlay = Color(0xFFFFFFFF),
    borderDefault = Color(0xFFD0D7DE),
    borderMuted = Color(0xFFD8DEE4),
    fgDefault = Color(0xFF1F2328),
    fgMuted = Color(0xFF656D76),
    fgSubtle = Color(0xFF6E7781),
    accentFg = Color(0xFF0969DA),
    accentEmphasis = Color(0xFF0969DA),
    accentSubtle = Color(0xFFDDF4FF),
    successFg = Color(0xFF1A7F37),
    successSubtle = Color(0xFFDAFBE1),
    attentionFg = Color(0xFF9A6700),
    attentionSubtle = Color(0xFFFFF8C5),
    dangerFg = Color(0xFFCF222E),
    dangerSubtle = Color(0xFFFFEBE9),
    doneFg = Color(0xFF8250DF),
    doneSubtle = Color(0xFFFBEFFF),
    neutralSubtle = Color(0xFFEFF2F5),
    isDark = false,
)

val DarkGitHub = GitHubColors(
    canvasDefault = Color(0xFF0D1117),
    canvasSubtle = Color(0xFF161B22),
    canvasInset = Color(0xFF010409),
    canvasOverlay = Color(0xFF161B22),
    borderDefault = Color(0xFF30363D),
    borderMuted = Color(0xFF21262D),
    fgDefault = Color(0xFFE6EDF3),
    fgMuted = Color(0xFF8B949E),
    fgSubtle = Color(0xFF6E7681),
    accentFg = Color(0xFF2F81F7),
    accentEmphasis = Color(0xFF1F6FEB),
    // The *Subtle tokens are badge BACKGROUNDS, so they must be translucent in dark mode.
    // GitHub's own values are 8-digit hex with a 0x26 (15%) alpha. Writing them opaque made
    // every badge the same colour as its own text — a blue-on-blue badge you cannot read.
    accentSubtle = Color(0x26388BFD),
    successFg = Color(0xFF3FB950),
    successSubtle = Color(0x262EA043),
    attentionFg = Color(0xFFD29922),
    attentionSubtle = Color(0x26BB8009),
    dangerFg = Color(0xFFF85149),
    dangerSubtle = Color(0x26DA3633),
    doneFg = Color(0xFFA371F7),
    doneSubtle = Color(0x268957E5),
    neutralSubtle = Color(0xFF21262D),
    isDark = true,
)

val LocalGitHubColors = staticCompositionLocalOf { LightGitHub }

/**
 * Course accent palette.
 *
 * Courses are coloured deterministically from their name so the same course
 * keeps the same colour across imports, and the student can override it.
 * Ten hues, each with a container/on-container pair tuned for both themes.
 */
@Immutable
data class CourseHue(
    val containerLight: Color,
    val onContainerLight: Color,
    val containerDark: Color,
    val onContainerDark: Color,
) {
    fun container(isDark: Boolean): Color = if (isDark) containerDark else containerLight

    fun onContainer(isDark: Boolean): Color = if (isDark) onContainerDark else onContainerLight
}

val CourseHues: List<CourseHue> = listOf(
    // blue
    CourseHue(Color(0xFFDDF4FF), Color(0xFF0A3069), Color(0xFF12304F), Color(0xFFA5D6FF)),
    // green
    CourseHue(Color(0xFFDAFBE1), Color(0xFF0F5323), Color(0xFF12341F), Color(0xFF9EE9B0)),
    // purple
    CourseHue(Color(0xFFFBEFFF), Color(0xFF3E1F79), Color(0xFF2B2050), Color(0xFFD8B9FF)),
    // orange / attention
    CourseHue(Color(0xFFFFF1E0), Color(0xFF7A3E00), Color(0xFF3B2A11), Color(0xFFFFC98A)),
    // red
    CourseHue(Color(0xFFFFEBE9), Color(0xFF82071E), Color(0xFF3F1D1D), Color(0xFFFFB3AD)),
    // teal
    CourseHue(Color(0xFFD8F5F1), Color(0xFF0B4F48), Color(0xFF0F3733), Color(0xFF8DE5DA)),
    // yellow
    CourseHue(Color(0xFFFFF8C5), Color(0xFF5C4400), Color(0xFF3A3111), Color(0xFFF2DC8A)),
    // pink
    CourseHue(Color(0xFFFFEFF7), Color(0xFF7D1F52), Color(0xFF3F1B32), Color(0xFFFFB0D6)),
    // indigo
    CourseHue(Color(0xFFE7ECFF), Color(0xFF22307A), Color(0xFF1E2447), Color(0xFFB4C2FF)),
    // lime / olive
    CourseHue(Color(0xFFEAF7D0), Color(0xFF3F5205), Color(0xFF2A3512), Color(0xFFCBE79A)),
)

fun CourseHue.containerFor(isDark: Boolean) = container(isDark)

fun CourseHue.onContainerFor(isDark: Boolean) = onContainer(isDark)
