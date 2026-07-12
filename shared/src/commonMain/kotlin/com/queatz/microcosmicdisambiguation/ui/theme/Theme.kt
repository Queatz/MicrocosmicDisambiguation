package com.queatz.microcosmicdisambiguation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * Returns the platform's dynamic (wallpaper-derived) color scheme when available, or `null` when
 * the platform has no such concept. Android returns Material You colors on Android 12+; the web has
 * no dynamic scheme and returns `null`, so the app falls back to the built-in light/dark schemes.
 */
@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?

/**
 * The single, shared app theme. It renders identically on every platform: it prefers the platform
 * dynamic color scheme (Android 12+) and otherwise uses the app's built-in light/dark schemes.
 */
@Composable
fun MicrocosmicDisambiguationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = dynamicColorSchemeOrNull(darkTheme)
        ?: if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
