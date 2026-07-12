package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.runtime.Composable

/** An audio file the learner picked to clone a voice from: its raw [bytes] and best-guess [mimeType]. */
class PickedAudio(val bytes: ByteArray, val mimeType: String)

/**
 * Lets the learner pick an existing audio file to clone a voice from. Android opens the system file
 * chooser (`GetContent`); the web opens a hidden `<input type="file">`. Kept behind this contract so
 * the clone-a-voice dialog stays platform-agnostic, mirroring [MicPermission].
 */
interface AudioPicker {
    /** Opens the platform file chooser, invoking [onPicked] with the chosen audio (nothing if cancelled). */
    fun pick(onPicked: (PickedAudio) -> Unit)
}

/** Remembers a platform [AudioPicker] bound to the current UI host. */
@Composable
expect fun rememberAudioPicker(): AudioPicker
