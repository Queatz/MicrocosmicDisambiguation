package com.queatz.microcosmicdisambiguation.ui.text

import androidx.compose.runtime.Composable

/** Android already renders every script via the OS font stack, so there is nothing to preload. */
@Composable
actual fun WithScriptFallback(text: String, content: @Composable () -> Unit) {
    content()
}
