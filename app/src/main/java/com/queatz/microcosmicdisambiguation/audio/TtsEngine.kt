package com.queatz.microcosmicdisambiguation.audio

/**
 * A text-to-speech engine that reads Mandarin Chinese aloud and reports progress so the UI can
 * highlight the characters/words currently being spoken.
 *
 * Two implementations exist:
 * - [Speaker], backed by the on-device Android [android.speech.tts.TextToSpeech] engine, and
 * - [QwenSpeaker], backed by the Qwen (Alibaba DashScope) cloud TTS service.
 *
 * Both deliver the same `[start, end)` character ranges through [speak]'s `onRange` callback so the
 * study screen's highlighting works identically regardless of the selected engine.
 */
interface TtsEngine {

    /** The current readiness of the engine with respect to speaking Simplified Chinese. */
    val status: SpeakerStatus

    /**
     * Invoked on the main thread whenever [status] changes. Setting this listener immediately
     * delivers the current [status] so late observers don't miss the initial transition.
     */
    var onStatusChanged: ((SpeakerStatus) -> Unit)?

    /**
     * Speaks [text] out loud in Mandarin, interrupting anything currently being spoken.
     *
     * @param onStart invoked on the main thread when playback begins
     * @param onRange invoked on the main thread as each chunk is spoken, with the
     *   `[start, end)` character range (into [text]) currently being read
     * @param onDone invoked on the main thread when playback finishes (or fails)
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
