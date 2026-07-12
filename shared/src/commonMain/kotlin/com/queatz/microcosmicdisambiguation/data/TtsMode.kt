package com.queatz.microcosmicdisambiguation.data

/**
 * The text-to-speech engine the app uses to read sentences aloud.
 */
enum class TtsMode {
    /** The Qwen (Alibaba DashScope) cloud TTS, which generates audio with word boundaries. */
    Ai,

    /** The on-device Android text-to-speech engine (or the browser Web Speech API on the web). */
    System,
}
