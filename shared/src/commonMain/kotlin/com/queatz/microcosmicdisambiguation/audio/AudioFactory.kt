package com.queatz.microcosmicdisambiguation.audio

import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.TtsMode

/**
 * Creates the platform's audio engines for the shared UI. Each platform provides its own
 * implementation (Android: on-device TTS / Qwen cloud TTS / streaming ASR / MediaPlayer; web: the
 * Web Speech API / Qwen / HTML Audio), so the screens never touch platform APIs directly.
 */
interface AudioFactory {

    /**
     * Whether the platform can prompt the user to install or adjust text-to-speech voices. Android
     * can (via the system TTS data flow); the web cannot, so the shared UI hides that action there.
     */
    val canManageVoices: Boolean

    /** Builds a [TtsEngine] for the given [mode] and [language]. */
    fun createSpeaker(mode: TtsMode, language: Language, qwenService: QwenService): TtsEngine

    /** Builds a [SpeechRecognizer] biased toward the given ASR [languageHints]. */
    fun createSpeechRecognizer(languageHints: List<String>): SpeechRecognizer

    /** Builds an [AudioClipPlayer] for replaying recordings and the learner's own audio. */
    fun createClipPlayer(): AudioClipPlayer

    /**
     * Launches the platform flow to install/adjust voice data. [missingData] distinguishes the
     * "download the missing voice" case from the "open TTS settings" case. A no-op where
     * [canManageVoices] is false.
     */
    fun manageVoices(missingData: Boolean)
}
