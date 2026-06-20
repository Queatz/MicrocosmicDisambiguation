package com.queatz.microcosmicdisambiguation.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * A persisted voice recording captured during a [com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer]
 * dictation session.
 *
 * The raw audio is written to device storage as a WAV file (see [filePath]) alongside the
 * [transcription] the recognizer produced for it, so both can be reviewed later from the settings
 * screen.
 */
@Entity
data class RecordingEntity(
    @Id var id: Long = 0,
    var filePath: String = "",
    var transcription: String = "",
    var createdAt: Long = 0,
    var durationMs: Long = 0,
)
