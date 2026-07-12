package com.queatz.microcosmicdisambiguation.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** The browser has no wallpaper-derived color scheme, so the app uses its built-in schemes. */
@Composable
actual fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme? = null
