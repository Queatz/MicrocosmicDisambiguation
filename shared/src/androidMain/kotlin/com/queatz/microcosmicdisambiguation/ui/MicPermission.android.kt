package com.queatz.microcosmicdisambiguation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android mic permission: proceeds immediately when already granted, otherwise shows the runtime
 * permission dialog and reports the outcome to the pending request's callbacks.
 */
@Composable
actual fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<() -> Unit, () -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callbacks = pending
        pending = null
        if (granted) callbacks?.first?.invoke() else callbacks?.second?.invoke()
    }
    return remember {
        object : MicPermission {
            override fun request(onGranted: () -> Unit, onDenied: () -> Unit) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    onGranted()
                } else {
                    pending = onGranted to onDenied
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
