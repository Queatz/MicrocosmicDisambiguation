package com.queatz.microcosmicdisambiguation.audio

/**
 * The readiness of a [TtsEngine] with respect to speaking the target language it was configured for.
 */
enum class SpeakerStatus {
    /** The engine has not finished initializing yet. */
    Initializing,

    /** The engine is ready and the target language voice is available. */
    Ready,

    /** The engine supports the target language, but the voice data needs to be downloaded. */
    MissingData,

    /** The selected engine does not support the target language at all. */
    NotSupported,

    /** The engine failed to initialize. */
    Failed,
}

/**
 * A text-to-speech engine that reads the studied language aloud and reports progress so the UI can
 * highlight the characters/words currently being spoken.
 *
 * Each platform provides its own implementation (Android's [android.speech.tts.TextToSpeech] or the
 * Qwen cloud TTS on device; the Web Speech API / Qwen in the browser), but all deliver the same
 * `[start, end)` character ranges through [speak]'s `onRange` callback, so the shared study screen's
 * highlighting works identically everywhere.
 */
interface TtsEngine {

    /** The current readiness of the engine. */
    val status: SpeakerStatus

    /**
     * Invoked whenever [status] changes. Setting this listener immediately delivers the current
     * [status] so late observers don't miss the initial transition.
     */
    var onStatusChanged: ((SpeakerStatus) -> Unit)?

    /**
     * Speaks [text] out loud, interrupting anything currently being spoken.
     *
     * @param onStart invoked when playback begins
     * @param onRange invoked as each chunk is spoken, with the `[start, end)` character range
     *   (into [text]) currently being read
     * @param onDone invoked when playback finishes (or fails)
     */
    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onRange: (start: Int, end: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
    )

    /** Releases any resources held by the engine. Call when the owner is destroyed. */
    fun shutdown()
}
