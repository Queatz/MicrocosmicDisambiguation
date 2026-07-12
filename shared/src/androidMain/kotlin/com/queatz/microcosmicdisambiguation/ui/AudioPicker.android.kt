package com.queatz.microcosmicdisambiguation.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android [AudioPicker]: opens the system content picker for any audio MIME type and reads the
 * chosen file's bytes (and MIME type) via the content resolver, so a recorded or downloaded clip
 * can be enrolled as a cloned voice.
 */
@Composable
actual fun rememberAudioPicker(): AudioPicker {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((PickedAudio) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val callback = pending
        pending = null
        if (uri != null && callback != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "audio/*"
            if (bytes != null && bytes.isNotEmpty()) {
                callback(PickedAudio(bytes, mime))
            }
        }
    }
    return remember {
        object : AudioPicker {
            override fun pick(onPicked: (PickedAudio) -> Unit) {
                pending = onPicked
                launcher.launch("audio/*")
            }
        }
    }
}
