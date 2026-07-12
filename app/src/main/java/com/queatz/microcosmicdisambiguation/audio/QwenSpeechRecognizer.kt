package com.queatz.microcosmicdisambiguation.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.queatz.microcosmicdisambiguation.ai.QwenService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams microphone audio to the Qwen (Alibaba DashScope) real-time speech recognition service and
 * surfaces a live, growing transcript. The Qwen3-ASR Flash real-time model recognizes a wide range
 * of languages (Chinese, English, Cantonese, Japanese, Korean, Russian, Vietnamese, German, French,
 * and many more), biased via the optional `language` hint, so the dictated text matches the language
 * the learner is speaking. Codes the model can't recognize (e.g. Burmese) are dropped so the model
 * auto-detects instead of mis-transcribing.
 *
 * The flow follows DashScope's OpenAI-style realtime WebSocket protocol: connect, send a
 * `session.update` instruction (manual turn detection), wait for `session.created`, then push raw
 * 16 kHz mono PCM frames captured from [AudioRecord] as base64 `input_audio_buffer.append` events
 * while consuming the `conversation.item.input_audio_transcription.text` events the server streams
 * back. On stop, `input_audio_buffer.commit` and `session.finish` are sent; the final
 * `...transcription.completed` transcript is delivered when it arrives.
 *
 * All listener callbacks are delivered on the main thread. The caller is responsible for holding the
 * `RECORD_AUDIO` runtime permission before invoking [start].
 */
class QwenSpeechRecognizer(
    private val apiKey: String = QwenService.QWEN_API_KEY,
    /** Language codes biasing recognition toward the languages the learner dictates in. */
    private val languageHints: List<String> = emptyList(),
) : SpeechRecognizer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    private val listening = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /** Transcripts the server has already finalized (via `...completed`), joined together. */
    private var finalizedText: String = ""

    /** The in-progress transcript the server is still refining. */
    private var partialText: String = ""

    /** Accumulates the raw PCM frames captured during the session so they can be saved on stop. */
    private val recordedAudio = ByteArrayOutputStream()

    /** Guards against delivering [onSessionComplete] more than once per session. */
    private val completed = AtomicBoolean(false)

    override var onTextChanged: ((String) -> Unit)? = null
    override var onListeningChanged: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /**
     * Invoked once a session ends normally, delivering the raw 16 kHz mono 16-bit PCM audio that was
     * captured along with the final transcript. Not called if the session fails or is shut down.
     */
    override var onSessionComplete: ((audio: ByteArray, transcript: String) -> Unit)? = null

    override val isListening: Boolean
        get() = listening.get()

    /**
     * Opens the recognition session and begins streaming microphone audio. The recognized text is
     * delivered incrementally via [onTextChanged]. Has no effect if already listening.
     */
    @SuppressLint("MissingPermission")
    override fun start() {
        if (!listening.compareAndSet(false, true)) return

        finalizedText = ""
        partialText = ""
        completed.set(false)
        synchronized(recordedAudio) { recordedAudio.reset() }
        notifyListening(true)

        val request = Request.Builder()
            .url("$ENDPOINT?model=$MODEL")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdateMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Qwen ASR WebSocket failure", t)
                fail(t.message ?: "Speech recognition failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishLocally()
            }
        })
    }

    /**
     * Signals the server that audio is complete and ends the session. The microphone is released
     * immediately; the final transcript may arrive shortly after via [onTextChanged].
     */
    override fun stop() {
        if (!listening.get()) return
        stopRecording()
        webSocket?.let {
            // Manual turn detection: commit the buffered audio, then end the session.
            it.send(simpleMessage("input_audio_buffer.commit"))
            it.send(simpleMessage("session.finish"))
        }
        // The session is over from the user's perspective the moment they tap stop.
        listening.set(false)
        notifyListening(false)
    }

    private fun handleEvent(webSocket: WebSocket, message: String) {
        val event = try {
            json.decodeFromString(RealtimeEvent.serializer(), message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Qwen ASR event: $message", t)
            return
        }

        when (event.type) {
            "session.created" -> startRecording(webSocket)
            "conversation.item.input_audio_transcription.text" -> {
                // `text` is the confirmed transcript so far; `stash` is the tentative tail.
                partialText = event.text.orEmpty() + event.stash.orEmpty()
                notifyText(finalizedText + partialText)
            }
            "conversation.item.input_audio_transcription.completed" -> {
                finalizedText += event.transcript.orEmpty()
                partialText = ""
                notifyText(finalizedText)
            }
            "session.finished" -> {
                webSocket.close(1000, null)
                finishLocally()
            }
            "error" -> {
                val reason = event.error?.message ?: "Speech recognition failed"
                webSocket.close(1000, null)
                fail(reason)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(webSocket: WebSocket) {
        if (audioRecord != null) return

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", t)
            fail("Microphone unavailable")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("Microphone unavailable")
            return
        }

        audioRecord = record
        recordThread = Thread {
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                while (listening.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        webSocket.send(appendAudioMessage(encoded))
                        synchronized(recordedAudio) { recordedAudio.write(buffer, 0, read) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error while streaming microphone audio", t)
            } finally {
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                record.release()
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        recordThread = null
        // The recording loop observes `listening` and releases the AudioRecord itself; clearing the
        // reference here prevents a second release.
        audioRecord = null
    }

    private fun finishLocally() {
        webSocket = null
        if (listening.compareAndSet(true, false)) {
            notifyListening(false)
        }
        deliverRecording()
    }

    /** Hands the captured audio and final transcript to [onSessionComplete] exactly once. */
    private fun deliverRecording() {
        if (!completed.compareAndSet(false, true)) return
        val audio = synchronized(recordedAudio) { recordedAudio.toByteArray() }
        val transcript = finalizedText.ifBlank { partialText }
        if (audio.isEmpty() || transcript.isBlank()) return
        mainHandler.post { onSessionComplete?.invoke(audio, transcript) }
    }

    private fun fail(message: String) {
        stopRecording()
        webSocket = null
        listening.set(false)
        // A failed session shouldn't produce a saved recording.
        completed.set(true)
        mainHandler.post {
            onListeningChanged?.invoke(false)
            onError?.invoke(message)
        }
    }

    /** Releases all resources. Call when the recognizer is no longer needed. */
    override fun shutdown() {
        stopRecording()
        // Tearing down the recognizer isn't a normal session end, so suppress delivery.
        completed.set(true)
        webSocket?.close(1000, null)
        webSocket = null
        listening.set(false)
    }

    private fun notifyText(text: String) {
        mainHandler.post { onTextChanged?.invoke(text) }
    }

    private fun notifyListening(value: Boolean) {
        mainHandler.post { onListeningChanged?.invoke(value) }
    }

    private fun sessionUpdateMessage(): String {
        // The model accepts a single optional language hint; pick the first code it can recognize.
        val language = languageHints.firstOrNull { it in SUPPORTED_LANGUAGES }
        val message = SessionUpdateEvent(
            eventId = eventId(),
            session = SessionConfig(
                inputAudioTranscription = TranscriptionConfig(language = language),
                // null turn detection selects manual mode, where we control commit/finish.
                turnDetection = null,
            ),
        )
        return json.encodeToString(SessionUpdateEvent.serializer(), message)
    }

    private fun appendAudioMessage(base64Audio: String): String {
        val message = AppendAudioEvent(eventId = eventId(), audio = base64Audio)
        return json.encodeToString(AppendAudioEvent.serializer(), message)
    }

    private fun simpleMessage(type: String): String {
        val message = SimpleEvent(type = type, eventId = eventId())
        return json.encodeToString(SimpleEvent.serializer(), message)
    }

    private fun eventId(): String =
        "event_" + UUID.randomUUID().toString().replace("-", "").take(24)

    companion object {
        private const val TAG = "QwenSpeechRecognizer"

        private const val ENDPOINT =
            "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime"
        private const val MODEL = "qwen3-asr-flash-realtime"

        private const val SAMPLE_RATE = 16_000

        /**
         * The `language` codes the [MODEL] can bias toward. Codes outside this set (e.g. Burmese)
         * are dropped so the model falls back to automatic language detection rather than failing
         * or defaulting to the wrong language.
         */
        private val SUPPORTED_LANGUAGES = setOf(
            "zh", "en", "yue", "ja", "ko", "ru", "vi", "de", "fr", "es", "pt",
            "id", "it", "ar", "th", "tr", "hi", "ms", "nl",
        )

        /** ~100 ms of 16 kHz mono 16-bit PCM audio per streamed frame. */
        private const val CHUNK_BYTES = 3_200
    }
}

@Serializable
private data class SessionUpdateEvent(
    @SerialName("event_id") val eventId: String,
    val session: SessionConfig,
    val type: String = "session.update",
)

@Serializable
private data class SessionConfig(
    @SerialName("input_audio_transcription") val inputAudioTranscription: TranscriptionConfig,
    @SerialName("turn_detection") val turnDetection: TurnDetection? = null,
    val modalities: List<String> = listOf("text"),
    @SerialName("input_audio_format") val inputAudioFormat: String = "pcm",
    @SerialName("sample_rate") val sampleRate: Int = 16_000,
)

@Serializable
private data class TranscriptionConfig(
    val language: String? = null,
)

/** Placeholder for server-VAD configuration; unused while we run in manual mode. */
@Serializable
private data class TurnDetection(
    val type: String = "server_vad",
)

@Serializable
private data class AppendAudioEvent(
    @SerialName("event_id") val eventId: String,
    val audio: String,
    val type: String = "input_audio_buffer.append",
)

@Serializable
private data class SimpleEvent(
    @SerialName("event_id") val eventId: String,
    val type: String,
)

@Serializable
private data class RealtimeEvent(
    val type: String? = null,
    val text: String? = null,
    val stash: String? = null,
    val transcript: String? = null,
    val error: RealtimeError? = null,
)

@Serializable
private data class RealtimeError(
    val message: String? = null,
    val code: String? = null,
)
