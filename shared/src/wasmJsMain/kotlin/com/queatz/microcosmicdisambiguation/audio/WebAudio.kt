package com.queatz.microcosmicdisambiguation.audio

import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.TtsMode

/**
 * The web [AudioFactory]. Text-to-speech uses the browser's Web Speech API; clips play through an
 * HTML `Audio` element. Streaming speech recognition and voice-data management have no portable web
 * equivalent here, so those degrade gracefully — keeping the shared UI identical, just without
 * dictation in the browser.
 */
class WebAudioFactory : AudioFactory {

    // The browser has no voice-data install flow to launch, so the shared UI hides that action.
    override val canManageVoices: Boolean = false

    override fun createSpeaker(
        mode: TtsMode,
        language: Language,
        qwenService: QwenService,
    ): TtsEngine = WebTtsEngine()

    override fun createSpeechRecognizer(languageHints: List<String>): SpeechRecognizer =
        WebSpeechRecognizer()

    override fun createClipPlayer(): AudioClipPlayer = WebAudioClipPlayer()

    override fun manageVoices(missingData: Boolean) {
        // Nothing to launch on the web.
    }
}

/**
 * A [TtsEngine] backed by the browser Web Speech API (`speechSynthesis`). It speaks the whole
 * utterance; per-character timing isn't exposed portably, so `onRange` isn't emitted (the study
 * screen still plays audio, just without synced highlighting on the web).
 */
class WebTtsEngine : TtsEngine {

    override val status: SpeakerStatus = SpeakerStatus.Ready

    override var onStatusChanged: ((SpeakerStatus) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(SpeakerStatus.Ready)
        }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onRange: (start: Int, end: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        onStart()
        runCatching { jsSpeak(text) }
        onDone()
    }

    override fun shutdown() {
        runCatching { jsCancelSpeech() }
    }
}

/**
 * A placeholder [SpeechRecognizer] for the web. The Web Speech recognition API isn't wired up in
 * this build, so dictation is a no-op in the browser; the mic controls simply do nothing rather than
 * changing the shared UI.
 */
class WebSpeechRecognizer : SpeechRecognizer {
    override val isListening: Boolean = false
    override var onTextChanged: ((String) -> Unit)? = null
    override var onListeningChanged: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onSessionComplete: ((audio: ByteArray, transcript: String) -> Unit)? = null

    override fun start() {}
    override fun stop() {}
    override fun shutdown() {}
}

/** An [AudioClipPlayer] backed by an HTML `Audio` element playing `data:`/blob URLs. */
class WebAudioClipPlayer : AudioClipPlayer {

    override fun playPcm(pcm: ByteArray, onComplete: () -> Unit) {
        runCatching { jsPlayAudio("data:audio/wav;base64,${Wav.pcmToWav(pcm).toBase64()}") }
        onComplete()
    }

    override fun playRef(ref: String) {
        runCatching { jsPlayAudio(ref) }
    }

    override fun stop() {}

    override fun release() {}
}

private fun jsSpeak(text: String) {
    js(
        "(function(){ if (typeof speechSynthesis !== 'undefined') { speechSynthesis.cancel(); speechSynthesis.speak(new SpeechSynthesisUtterance(text)); } })()",
    )
}

private fun jsCancelSpeech() {
    js("(function(){ if (typeof speechSynthesis !== 'undefined') { speechSynthesis.cancel(); } })()")
}

private fun jsPlayAudio(url: String) {
    js("(function(){ var a = new Audio(url); a.play(); })()")
}

private const val BASE64_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** A minimal, dependency-free Base64 encoder used to build `data:` URLs for audio playback. */
internal fun ByteArray.toBase64(): String {
    val sb = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= size) {
        val n = ((this[i].toInt() and 0xFF) shl 16) or
            ((this[i + 1].toInt() and 0xFF) shl 8) or
            (this[i + 2].toInt() and 0xFF)
        sb.append(BASE64_CHARS[(n ushr 18) and 0x3F])
        sb.append(BASE64_CHARS[(n ushr 12) and 0x3F])
        sb.append(BASE64_CHARS[(n ushr 6) and 0x3F])
        sb.append(BASE64_CHARS[n and 0x3F])
        i += 3
    }
    when (size - i) {
        1 -> {
            val n = (this[i].toInt() and 0xFF) shl 16
            sb.append(BASE64_CHARS[(n ushr 18) and 0x3F])
            sb.append(BASE64_CHARS[(n ushr 12) and 0x3F])
            sb.append("==")
        }
        2 -> {
            val n = ((this[i].toInt() and 0xFF) shl 16) or ((this[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64_CHARS[(n ushr 18) and 0x3F])
            sb.append(BASE64_CHARS[(n ushr 12) and 0x3F])
            sb.append(BASE64_CHARS[(n ushr 6) and 0x3F])
            sb.append("=")
        }
    }
    return sb.toString()
}
