package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The browser has no separate mic-permission step here — the Web Speech API prompts on its own — so
 * this simply proceeds, keeping the recording flow identical to Android from the UI's perspective.
 */
@Composable
actual fun rememberMicPermission(): MicPermission = remember {
    object : MicPermission {
        override fun request(onGranted: () -> Unit, onDenied: () -> Unit) = onGranted()
    }
}
