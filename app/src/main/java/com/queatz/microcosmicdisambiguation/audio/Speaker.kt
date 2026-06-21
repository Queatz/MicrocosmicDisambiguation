package com.queatz.microcosmicdisambiguation.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.queatz.microcosmicdisambiguation.data.BLANK
import com.queatz.microcosmicdisambiguation.data.Language
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The state of the [Speaker]'s underlying [TextToSpeech] engine with respect to speaking the
 * target language it was configured for.
 */
enum class SpeakerStatus {
    /** The engine has not finished initializing yet. */
    Initializing,

    /** The engine is ready and the target language voice data is installed. */
    Ready,

    /** The engine supports the target language, but the voice data needs to be downloaded. */
    MissingData,

    /** The selected engine does not support the target language at all. */
    NotSupported,

    /** The engine failed to initialize. */
    Failed,
}

/**
 * A thin wrapper around Android [TextToSpeech] configured for the given [language].
 *
 * Use [speak] to read text aloud and call [shutdown] when the owner is destroyed.
 *
 * [speak] optionally reports progress through its callbacks so the UI can highlight the
 * characters/words that are currently being spoken.
 *
 * The engine reports whether the [language] is ready to use through [status] and
 * [onStatusChanged]. When the status is [SpeakerStatus.MissingData] the caller should prompt the
 * user to download the voice data, launching [installTtsDataIntent].
 */
class Speaker(context: Context, language: Language) : TtsEngine {

    @Volatile
    private var ready = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextUtteranceId = AtomicInteger(0)
    private val callbacks = ConcurrentHashMap<String, SpeechCallbacks>()

    /**
     * The current state of the engine. Updated on the main thread; observe changes via
     * [onStatusChanged].
     */
    @Volatile
    override var status: SpeakerStatus = SpeakerStatus.Initializing
        private set

    /**
     * Invoked on the main thread whenever [status] changes. Setting this listener immediately
     * delivers the current [status] so late observers don't miss the initial transition.
     */
    override var onStatusChanged: ((SpeakerStatus) -> Unit)? = null
        set(value) {
            field = value
            val current = status
            mainHandler.post { value?.invoke(current) }
        }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { initStatus ->
        if (initStatus == TextToSpeech.SUCCESS) {
            when (tts.setLanguage(Locale.forLanguageTag(language.localeTag))) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    ready = false
                    updateStatus(SpeakerStatus.MissingData)
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    ready = false
                    updateStatus(SpeakerStatus.NotSupported)
                }
                else -> {
                    ready = true
                    updateStatus(SpeakerStatus.Ready)
                }
            }
        } else {
            ready = false
            updateStatus(SpeakerStatus.Failed)
        }
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val cb = callbacks[utteranceId] ?: return
                mainHandler.post { cb.onStart() }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val cb = callbacks[utteranceId] ?: return
                mainHandler.post { cb.onRange(start, end) }
            }

            override fun onDone(utteranceId: String?) {
                val cb = callbacks.remove(utteranceId) ?: return
                mainHandler.post { cb.onDone() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val cb = callbacks.remove(utteranceId) ?: return
                mainHandler.post { cb.onDone() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val cb = callbacks.remove(utteranceId) ?: return
                mainHandler.post { cb.onDone() }
            }
        })
    }

    private fun updateStatus(newStatus: SpeakerStatus) {
        status = newStatus
        mainHandler.post { onStatusChanged?.invoke(newStatus) }
    }

    /**
     * Speaks [text] out loud in the configured language, interrupting anything currently being spoken.
     *
     * @param onStart invoked on the main thread when playback begins
     * @param onRange invoked on the main thread as each chunk is spoken, with the
     *   `[start, end)` character range (into [text]) currently being read
     * @param onDone invoked on the main thread when playback finishes (or fails)
     */
    override fun speak(
        text: String,
        onStart: () -> Unit,
        onRange: (start: Int, end: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        // Replace each blank marker with equal-length whitespace so the engine skips over it
        // while keeping character offsets intact for the UI's progress highlighting.
        val spoken = text.replace(BLANK, " ".repeat(BLANK.length))
        if (!ready || spoken.isBlank()) {
            onDone()
            return
        }
        val id = "u${nextUtteranceId.incrementAndGet()}"
        callbacks[id] = SpeechCallbacks(onStart, onRange, onDone)
        val result = tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone()
        }
    }

    override fun shutdown() {
        onStatusChanged = null
        callbacks.clear()
        tts.stop()
        tts.shutdown()
    }

    private class SpeechCallbacks(
        val onStart: () -> Unit,
        val onRange: (Int, Int) -> Unit,
        val onDone: () -> Unit,
    )

    companion object {
        /**
         * An [Intent] that asks the active text-to-speech engine to download/install the voice
         * data. Launch this (e.g. via `startActivity`) when [status] is
         * [SpeakerStatus.MissingData] to let the user install the Simplified Chinese voice.
         */
        fun installTtsDataIntent(): Intent =
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    }
}
