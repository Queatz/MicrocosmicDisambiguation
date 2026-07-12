package com.queatz.microcosmicdisambiguation.ui.text

import androidx.compose.runtime.Composable

/**
 * Ensures every [Script] present in [text] can actually render before showing [content], fetching
 * and registering a matching fallback font the first time a new script is encountered.
 *
 * Android already renders every script through the OS font stack (see the investigation notes on
 * why Burmese didn't render on the web but did on Android), so this is a no-op there. Compose Web
 * (Skiko) ships no OS-level font fallback, so its `actual` implementation scans [text], and for any
 * script not yet loaded, downloads a "Noto Sans" fallback (see [Script.fontUrl]) and preloads it
 * into the active `FontFamily.Resolver` — generically, for whatever script shows up, not just the
 * ones the app happens to know about today.
 */
@Composable
expect fun WithScriptFallback(text: String, content: @Composable () -> Unit)
