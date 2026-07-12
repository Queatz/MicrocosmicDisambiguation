package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.runtime.Composable

/**
 * Requests the microphone permission the UI needs for dictation and pronunciation practice.
 *
 * Android shows the runtime permission dialog; the browser has no separate permission step here
 * (the Web Speech API prompts on its own), so its implementation simply proceeds. This keeps every
 * screen's recording flow identical.
 */
interface MicPermission {
    /** Runs [onGranted] if the permission is (or becomes) granted, otherwise [onDenied]. */
    fun request(onGranted: () -> Unit, onDenied: () -> Unit = {})
}

/** Remembers a platform [MicPermission] bound to the current UI host. */
@Composable
expect fun rememberMicPermission(): MicPermission
