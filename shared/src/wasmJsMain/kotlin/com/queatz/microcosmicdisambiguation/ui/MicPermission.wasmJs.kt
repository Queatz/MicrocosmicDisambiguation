package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The browser has no separate mic-permission step here — `getUserMedia` prompts on its own once the
 * Qwen speech recognizer actually starts capturing — so this simply proceeds, keeping the recording
 * flow identical to Android from the UI's perspective.
 */
@Composable
actual fun rememberMicPermission(): MicPermission = remember {
    object : MicPermission {
        override fun request(onGranted: () -> Unit, onDenied: () -> Unit) = onGranted()
    }
}
