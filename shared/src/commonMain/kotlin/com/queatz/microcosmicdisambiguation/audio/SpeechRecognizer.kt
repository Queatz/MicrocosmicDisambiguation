package com.queatz.microcosmicdisambiguation.audio

/**
 * Streams microphone audio to a speech-recognition backend and surfaces a live, growing transcript.
 *
 * Android streams raw PCM to the Qwen realtime ASR service; the browser uses the Web Speech API.
 * The UI (home dictation, mission replies, pronunciation practice) only depends on this contract, so
 * the screens are identical on every platform. All listener callbacks are delivered on the main
 * thread.
 */
interface SpeechRecognizer {

    /** True while a recognition session is active. */
    val isListening: Boolean

    /** Invoked as the transcript grows, with the full text so far. */
    var onTextChanged: ((String) -> Unit)?

    /** Invoked when a session starts (true) or ends (false). */
    var onListeningChanged: ((Boolean) -> Unit)?

    /** Invoked when recognition fails, with a human-readable message. */
    var onError: ((String) -> Unit)?

    /**
     * Invoked once a session ends normally, delivering the raw 16 kHz mono 16-bit PCM audio captured
     * along with the final transcript. Not called if the session fails or is shut down. Platforms
     * without access to the raw audio (e.g. the browser Web Speech API) deliver an empty array.
     */
    var onSessionComplete: ((audio: ByteArray, transcript: String) -> Unit)?

    /** Opens a session and begins streaming microphone audio. No effect if already listening. */
    fun start()

    /** Ends the session. The final transcript may arrive shortly after via [onTextChanged]. */
    fun stop()

    /** Releases all resources. Call when the recognizer is no longer needed. */
    fun shutdown()
}
