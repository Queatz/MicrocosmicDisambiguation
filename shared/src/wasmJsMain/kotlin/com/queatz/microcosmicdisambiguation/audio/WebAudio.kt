package com.queatz.microcosmicdisambiguation.audio

import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.BLANK
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.TtsMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The web [AudioFactory]. Text-to-speech uses the browser's Web Speech API (`speechSynthesis`);
 * speech recognition streams the microphone to the same Qwen (Alibaba DashScope) realtime ASR
 * service the Android app uses, so dictation behaves identically across platforms. Clips play
 * through an HTML `Audio` element; voice-data management has no portable web equivalent, so that
 * action degrades gracefully — keeping the shared UI identical.
 */
class WebAudioFactory : AudioFactory {

    // The browser has no voice-data install flow to launch, so the shared UI hides that action.
    override val canManageVoices: Boolean = false

    override fun createSpeaker(
        mode: TtsMode,
        language: Language,
        qwenService: QwenService,
    ): TtsEngine = when (mode) {
        TtsMode.Ai -> WebQwenTtsEngine(qwenService, language)
        TtsMode.System -> WebTtsEngine()
    }

    override fun createSpeechRecognizer(languageHints: List<String>): SpeechRecognizer =
        WebSpeechRecognizer(languageHints)

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
 * A [TtsEngine] backed by the Qwen (Alibaba DashScope) cloud TTS service, used on the web when the
 * learner selects the AI voice in settings. The audio is synthesized remotely and returned as a
 * single WAV with no per-word timing, so — exactly like the Android `QwenSpeaker` — this engine
 * measures the clip's duration (from the WAV header) and advances the highlight proportionally
 * across the speakable units, reproducing the same `onRange` stream the study screen expects.
 *
 * Everything runs on the browser's single main thread, so the clip is fetched in a [MainScope]
 * coroutine and played through an HTML `Audio` element. A short lead-in of silence is prepended so
 * the opening of the speech is never clipped before playback ramps up.
 */
class WebQwenTtsEngine(
    private val qwen: QwenService,
    private val language: Language,
) : TtsEngine {

    private val scope = MainScope()

    /** The HTML `Audio` element currently playing, so it can be stopped when speaking again. */
    private var audio: JsAny? = null
    private var speakJob: Job? = null
    private var rangeJob: Job? = null

    // The cloud engine needs no on-device voice data, so it's ready as soon as it exists.
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
        stopPlayback()

        // The units the listener will actually hear, mapped back to their offsets in [text] so the
        // emitted ranges line up with what the study screen renders (including the blank marker).
        val speakable = speakableRanges(text, language.characterSegmented)
        // Text sent for synthesis: drop the blank markers so the model doesn't read underscores.
        val synthesisText = text.replace(BLANK, " ")
        if (synthesisText.isBlank()) {
            onDone()
            return
        }

        speakJob = scope.launch {
            val padded = try {
                prependSilence(qwen.synthesizeSpeech(synthesisText, language), LEAD_SILENCE_MS)
            } catch (t: Throwable) {
                onDone()
                return@launch
            }
            if (!isActive) return@launch

            val durationMs = wavDurationMs(padded)
            val url = "data:audio/wav;base64,${padded.toBase64()}"
            var done = false
            val finish = {
                if (!done) {
                    done = true
                    cancelRangeJob()
                    audio = null
                    onDone()
                }
            }

            onStart()
            audio = runCatching { jsPlayWav(url) { finish() } }.getOrNull()
            if (audio == null) {
                finish()
                return@launch
            }
            scheduleRanges(durationMs, speakable, onRange)
        }
    }

    /**
     * Advances [onRange] through the speakable units proportionally to the wall-clock time elapsed
     * since playback started, using the [durationMs] parsed from the WAV header. The clip begins
     * with [LEAD_SILENCE_MS] of padding, so elapsed time is mapped onto the remaining speech window
     * and the highlight never runs ahead of the audio during the silent lead-in.
     */
    private fun scheduleRanges(
        durationMs: Long,
        speakable: List<IntRange>,
        onRange: (start: Int, end: Int) -> Unit,
    ) {
        if (speakable.isEmpty() || durationMs <= 0) return
        val leadMs = LEAD_SILENCE_MS.coerceIn(0, durationMs)
        val speechDurationMs = (durationMs - leadMs).coerceAtLeast(1L)
        rangeJob = scope.launch {
            val startTime = jsNow().toLong()
            var lastIndex = -1
            while (isActive) {
                val elapsed = jsNow().toLong() - startTime
                val elapsedSpeech = (elapsed - leadMs).coerceAtLeast(0L)
                val index = (elapsedSpeech * speakable.size / speechDurationMs)
                    .toInt()
                    .coerceIn(0, speakable.size - 1)
                if (index != lastIndex) {
                    lastIndex = index
                    val range = speakable[index]
                    onRange(range.first, range.last + 1)
                }
                if (elapsed >= durationMs) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPlayback() {
        speakJob?.cancel()
        speakJob = null
        cancelRangeJob()
        runCatching { jsStopWav(audio) }
        audio = null
    }

    private fun cancelRangeJob() {
        rangeJob?.cancel()
        rangeJob = null
    }

    override fun shutdown() {
        onStatusChanged = null
        stopPlayback()
        scope.cancel()
    }

    private companion object {
        /** How often (ms) the highlight is advanced against the elapsed-time clock (~30fps). */
        private const val POLL_INTERVAL_MS = 33L
    }
}

/**
 * A [SpeechRecognizer] that streams microphone audio to the Qwen (Alibaba DashScope) realtime ASR
 * service — the same [QwenService.REALTIME_ASR_MODEL] model and `session.*`/`input_audio_buffer.*`
 * WebSocket protocol the Android app's `QwenSpeechRecognizer` uses — so dictation behaves
 * identically across platforms, including the model's wide language coverage and access to the raw
 * captured audio for [onSessionComplete] (unlike the browser's own `SpeechRecognition` API, which
 * exposes neither).
 *
 * Browsers can't set a custom `Authorization` header on a `WebSocket` handshake, so the API key
 * rides along as the `api_key` query parameter — the browser-compatible credential channel this
 * gateway accepts (its OpenAI-style handshake does not honor the `openai-insecure-api-key.<key>`
 * subprotocol convention, which it rejects with HTTP 401).
 *
 * Microphone capture uses `getUserMedia` plus a `ScriptProcessorNode` that resamples the mic's
 * native sample rate down to the 16 kHz mono 16-bit PCM the model expects, mirroring Android's
 * `AudioRecord`-based capture. Everything runs on the browser's single main thread, so callbacks are
 * dispatched directly.
 */
class WebSpeechRecognizer(
    /** ASR language codes (e.g. "en", "zh") biasing recognition toward a language the model knows. */
    private val languageHints: List<String> = emptyList(),
    private val apiKey: String = QwenService.QWEN_API_KEY,
) : SpeechRecognizer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var listening = false

    /** The underlying JS `WebSocket` instance while a session is active. */
    private var socket: JsAny? = null

    /** The mic-capture handle (stream/context/nodes) returned by [jsStartMic] while listening. */
    private var micHandle: JsAny? = null

    /** Transcripts the server has already finalized (via `...completed`), joined together. */
    private var finalizedText = ""

    /** The in-progress transcript the server is still refining. */
    private var partialText = ""

    /** The raw 16 kHz mono 16-bit PCM frames captured during the session, in order. */
    private val audioChunks = mutableListOf<ByteArray>()

    /** Guards against delivering [onSessionComplete] more than once per session. */
    private var completed = false

    /** True while tearing down, so the normal end-of-session completion is suppressed. */
    private var shuttingDown = false

    override var onTextChanged: ((String) -> Unit)? = null
    override var onListeningChanged: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onSessionComplete: ((audio: ByteArray, transcript: String) -> Unit)? = null

    override val isListening: Boolean
        get() = listening

    override fun start() {
        if (listening) return

        finalizedText = ""
        partialText = ""
        completed = false
        shuttingDown = false
        audioChunks.clear()
        // The session is accepted the moment start() is called, mirroring Android; if the socket or
        // the microphone fails to come up, fail() flips this back and reports the error.
        listening = true
        onListeningChanged?.invoke(true)

        val opened = runCatching {
            jsOpenAsrSocket(
                url = "${QwenService.REALTIME_ENDPOINT}?model=${QwenService.REALTIME_ASR_MODEL}",
                apiKey = apiKey,
                onOpen = { jsSendText(socket, sessionUpdateMessage()) },
                onMessage = { text -> handleEvent(text.toString()) },
                onClose = { finishLocally() },
                onError = { fail("Speech recognition failed") },
            )
        }.getOrNull()

        if (opened == null) {
            fail("Speech recognition failed")
            return
        }
        socket = opened
    }

    override fun stop() {
        if (!listening) return
        stopMicCapture()
        jsSendText(socket, simpleMessage("input_audio_buffer.commit"))
        jsSendText(socket, simpleMessage("session.finish"))
        // The session is over from the user's perspective the moment they tap stop; the final
        // transcript (and onSessionComplete) still arrive once the server closes the socket.
        listening = false
        onListeningChanged?.invoke(false)
    }

    private fun handleEvent(message: String) {
        val event = runCatching {
            json.decodeFromString(RealtimeAsrEvent.serializer(), message)
        }.getOrNull() ?: return

        when (event.type) {
            "session.created" -> startMicCapture()
            "conversation.item.input_audio_transcription.text" -> {
                // `text` is the confirmed transcript so far; `stash` is the tentative tail.
                partialText = event.text.orEmpty() + event.stash.orEmpty()
                onTextChanged?.invoke(finalizedText + partialText)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                finalizedText += event.transcript.orEmpty()
                partialText = ""
                onTextChanged?.invoke(finalizedText)
            }
            "session.finished" -> {
                jsCloseSocket(socket)
                finishLocally()
            }
            "error" -> {
                val reason = event.error?.message ?: "Speech recognition failed"
                jsCloseSocket(socket)
                fail(reason)
            }
        }
    }

    private fun startMicCapture() {
        jsStartMic(
            onChunk = { base64 -> onAudioChunk(base64.toString()) },
            onReady = { handle -> micHandle = handle },
            onError = { code -> fail(micErrorMessage(code.toString())) },
        )
    }

    private fun onAudioChunk(base64: String) {
        if (!listening) return
        audioChunks.add(base64.fromBase64())
        jsSendText(socket, appendAudioMessage(base64))
    }

    private fun stopMicCapture() {
        jsStopMic(micHandle)
        micHandle = null
    }

    /** Handles the socket closing (normally or otherwise), delivering the recording exactly once. */
    private fun finishLocally() {
        socket = null
        stopMicCapture()
        if (listening) {
            listening = false
            onListeningChanged?.invoke(false)
        }
        if (shuttingDown) return
        deliverRecording()
    }

    /** Hands the captured audio and final transcript to [onSessionComplete] exactly once. */
    private fun deliverRecording() {
        if (completed) return
        completed = true
        val audio = audioChunks.flattenBytes()
        val transcript = finalizedText.ifBlank { partialText }
        if (audio.isEmpty() || transcript.isBlank()) return
        onSessionComplete?.invoke(audio, transcript)
    }

    private fun fail(message: String) {
        stopMicCapture()
        jsCloseSocket(socket)
        socket = null
        val wasListening = listening
        listening = false
        // A failed session shouldn't produce a saved recording.
        completed = true
        if (wasListening) {
            onListeningChanged?.invoke(false)
        }
        onError?.invoke(message)
    }

    override fun shutdown() {
        shuttingDown = true
        // Tearing down the recognizer isn't a normal session end, so suppress delivery.
        completed = true
        listening = false
        stopMicCapture()
        jsCloseSocket(socket)
        socket = null
    }

    private fun sessionUpdateMessage(): String {
        // The model accepts a single optional language hint; pick the first code it can recognize.
        val language = languageHints.firstOrNull { it in SUPPORTED_LANGUAGES }
        val message = AsrSessionUpdateEvent(
            eventId = eventId(),
            session = AsrSessionConfig(
                inputAudioTranscription = AsrTranscriptionConfig(language = language),
                // null turn detection selects manual mode, where we control commit/finish.
                turnDetection = null,
            ),
        )
        return json.encodeToString(AsrSessionUpdateEvent.serializer(), message)
    }

    private fun appendAudioMessage(base64Audio: String): String {
        val message = AsrAppendAudioEvent(eventId = eventId(), audio = base64Audio)
        return json.encodeToString(AsrAppendAudioEvent.serializer(), message)
    }

    private fun simpleMessage(type: String): String {
        val message = AsrSimpleEvent(type = type, eventId = eventId())
        return json.encodeToString(AsrSimpleEvent.serializer(), message)
    }

    private fun eventId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(24)
        repeat(24) { sb.append(chars[Random.nextInt(chars.length)]) }
        return "event_$sb"
    }

    private companion object {
        /**
         * The `language` codes the [QwenService.REALTIME_ASR_MODEL] can bias toward; others fall
         * back to auto-detection.
         */
        private val SUPPORTED_LANGUAGES = setOf(
            "zh", "en", "yue", "ja", "ko", "ru", "vi", "de", "fr", "es", "pt",
            "id", "it", "ar", "th", "tr", "hi", "ms", "nl",
        )

        /** Turns a `getUserMedia`/audio-capture failure code into a readable message. */
        private fun micErrorMessage(code: String): String = when (code) {
            "NotAllowedError", "PermissionDeniedError", "SecurityError" -> "Microphone permission denied"
            "NotFoundError", "DevicesNotFoundError" -> "No microphone was found"
            "unsupported" -> "Speech recognition is not supported in this browser"
            else -> "Microphone unavailable"
        }
    }
}

@Serializable
private data class AsrSessionUpdateEvent(
    @SerialName("event_id") val eventId: String,
    val session: AsrSessionConfig,
    val type: String = "session.update",
)

@Serializable
private data class AsrSessionConfig(
    @SerialName("input_audio_transcription") val inputAudioTranscription: AsrTranscriptionConfig,
    @SerialName("turn_detection") val turnDetection: AsrTurnDetection? = null,
    val modalities: List<String> = listOf("text"),
    @SerialName("input_audio_format") val inputAudioFormat: String = "pcm",
    @SerialName("sample_rate") val sampleRate: Int = 16_000,
)

@Serializable
private data class AsrTranscriptionConfig(
    val language: String? = null,
)

/** Placeholder for server-VAD configuration; unused while we run in manual (commit/finish) mode. */
@Serializable
private data class AsrTurnDetection(
    val type: String = "server_vad",
)

@Serializable
private data class AsrAppendAudioEvent(
    @SerialName("event_id") val eventId: String,
    val audio: String,
    val type: String = "input_audio_buffer.append",
)

@Serializable
private data class AsrSimpleEvent(
    @SerialName("event_id") val eventId: String,
    val type: String,
)

@Serializable
private data class RealtimeAsrEvent(
    val type: String? = null,
    val text: String? = null,
    val stash: String? = null,
    val transcript: String? = null,
    val error: RealtimeAsrError? = null,
)

@Serializable
private data class RealtimeAsrError(
    val message: String? = null,
    val code: String? = null,
)

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

/** The current wall-clock time in milliseconds, used to drive proportional TTS highlighting. */
private fun jsNow(): Double = js("Date.now()")

/**
 * Plays the WAV [url] through a fresh HTML `Audio` element, invoking [onEnded] once when playback
 * finishes (or fails to play). Returns the element so it can later be paused, or `null` if
 * construction throws.
 */
private fun jsPlayWav(url: String, onEnded: () -> Unit): JsAny? = js(
    """(function() {
        var a = new Audio(url);
        a.onended = function() { onEnded(); };
        a.onerror = function() { onEnded(); };
        a.play();
        return a;
    })()""",
)

/** Pauses an active playback element, stopping the audio if one is playing. */
private fun jsStopWav(audio: JsAny?) {
    js("(function(){ if (audio) { try { audio.pause(); } catch (e) {} } })()")
}

/**
 * Opens a `WebSocket` to the Qwen realtime ASR endpoint, authenticating with the API key passed as
 * the `api_key` query parameter. Browsers can't set a custom `Authorization` header on a WebSocket
 * handshake, and this dedicated Model Studio gateway does not honor the OpenAI
 * `openai-insecure-api-key.<key>` subprotocol convention (it rejects that handshake with HTTP 401),
 * so the query parameter is the browser-compatible credential channel it accepts. Wires the Kotlin
 * callbacks to the socket's events and returns the socket so it can later be used to send messages
 * or be closed, or `null` if construction throws.
 */
private fun jsOpenAsrSocket(
    url: String,
    apiKey: String,
    onOpen: () -> Unit,
    onMessage: (JsString) -> Unit,
    onClose: () -> Unit,
    onError: () -> Unit,
): JsAny? = js(
    """(function() {
        try {
            var authedUrl = url + '&api_key=' + encodeURIComponent(apiKey);
            var ws = new WebSocket(authedUrl);
            ws.onopen = function() { onOpen(); };
            ws.onmessage = function(e) { onMessage(String(e.data)); };
            ws.onclose = function() { onClose(); };
            ws.onerror = function() { onError(); };
            return ws;
        } catch (e) {
            return null;
        }
    })()""",
)

/** Sends [text] over [socket], swallowing any failure (e.g. an already-closed socket). */
private fun jsSendText(socket: JsAny?, text: String) {
    js("(function(){ if (socket) { try { socket.send(text); } catch (e) {} } })()")
}

/** Closes [socket], swallowing any failure. */
private fun jsCloseSocket(socket: JsAny?) {
    js("(function(){ if (socket) { try { socket.close(); } catch (e) {} } })()")
}

/**
 * Requests the microphone via `getUserMedia`, then wires a `ScriptProcessorNode` that resamples the
 * mic's native sample rate down to 16 kHz mono, converts each frame to 16-bit PCM, and delivers it to
 * [onChunk] as a base64 string. [onReady] receives an opaque handle for [jsStopMic] once capture
 * starts; [onError] fires instead if the browser has no microphone/`getUserMedia` support or
 * permission is denied.
 */
private fun jsStartMic(
    onChunk: (JsString) -> Unit,
    onReady: (JsAny) -> Unit,
    onError: (JsString) -> Unit,
) {
    js(
        """(function() {
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                onError('unsupported');
                return;
            }
            navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }).then(function(stream) {
                try {
                    var AudioCtx = window.AudioContext || window.webkitAudioContext;
                    var ctx = new AudioCtx();
                    var source = ctx.createMediaStreamSource(stream);
                    var processor = ctx.createScriptProcessor(4096, 1, 1);
                    var gain = ctx.createGain();
                    gain.gain.value = 0;
                    var ratio = ctx.sampleRate / 16000;
                    processor.onaudioprocess = function(e) {
                        var input = e.inputBuffer.getChannelData(0);
                        var outLength = Math.max(1, Math.floor(input.length / ratio));
                        var buf = new Int16Array(outLength);
                        for (var i = 0; i < outLength; i++) {
                            var srcIndex = i * ratio;
                            var i0 = Math.floor(srcIndex);
                            var i1 = Math.min(i0 + 1, input.length - 1);
                            var frac = srcIndex - i0;
                            var sample = input[i0] * (1 - frac) + input[i1] * frac;
                            var s = Math.max(-1, Math.min(1, sample));
                            buf[i] = s < 0 ? (s * 0x8000) : (s * 0x7FFF);
                        }
                        var bytes = new Uint8Array(buf.buffer);
                        var binary = '';
                        for (var j = 0; j < bytes.length; j++) binary += String.fromCharCode(bytes[j]);
                        onChunk(btoa(binary));
                    };
                    source.connect(processor);
                    processor.connect(gain);
                    gain.connect(ctx.destination);
                    onReady({ stream: stream, ctx: ctx, source: source, processor: processor, gain: gain });
                } catch (err) {
                    onError(err && err.name ? String(err.name) : 'audio-capture');
                }
            }).catch(function(err) {
                onError(err && err.name ? String(err.name) : 'audio-capture');
            });
        })()""",
    )
}

/** Tears down a mic-capture [handle] returned by [jsStartMic]: disconnects nodes and stops tracks. */
private fun jsStopMic(handle: JsAny?) {
    js(
        """(function() {
            if (!handle) return;
            try { handle.processor.disconnect(); } catch (e) {}
            try { handle.source.disconnect(); } catch (e) {}
            try { handle.gain.disconnect(); } catch (e) {}
            try { if (handle.stream) { handle.stream.getTracks().forEach(function(t){ t.stop(); }); } } catch (e) {}
            try { handle.ctx.close(); } catch (e) {}
        })()""",
    )
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

private val BASE64_LOOKUP: Map<Char, Int> = BASE64_CHARS.withIndex().associate { (i, c) -> c to i }

/** Decodes a standard (possibly `=`-padded) Base64 string; the inverse of [toBase64]. */
internal fun String.fromBase64(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    val cleanLength = indexOf('=').let { if (it == -1) length else it }
    val out = ByteArray(cleanLength * 6 / 8)
    var outIndex = 0
    var buffer = 0
    var bitsCollected = 0
    for (i in 0 until cleanLength) {
        val value = BASE64_LOOKUP[this[i]] ?: continue
        buffer = (buffer shl 6) or value
        bitsCollected += 6
        if (bitsCollected >= 8) {
            bitsCollected -= 8
            out[outIndex++] = ((buffer shr bitsCollected) and 0xFF).toByte()
        }
    }
    return out
}

/** Concatenates a list of byte-array chunks into a single array, in order. */
private fun List<ByteArray>.flattenBytes(): ByteArray {
    val result = ByteArray(sumOf { it.size })
    var offset = 0
    for (chunk in this) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
