package com.queatz.microcosmicdisambiguation.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams microphone audio to the Qwen (Alibaba DashScope) real-time speech recognition service and
 * surfaces a live, growing transcript. The Fun-ASR real-time model recognizes a wide range
 * of languages (Chinese, English, Cantonese, Japanese, Korean, Russian, Vietnamese, German, French,
 * and many more), biased via the optional `language` hint, so the dictated text matches the language
 * the learner is speaking. Codes the model can't recognize (e.g. Burmese) are dropped so the model
 * auto-detects instead of mis-transcribing.
 *
 * The flow follows DashScope's task-based duplex WebSocket protocol: connect, send a `run-task`
 * instruction (with model and format/sample_rate details), wait for `task-started` event, then stream
 * raw 16 kHz mono PCM frames captured from [AudioRecord] as binary messages while consuming
 * `result-generated` events the server streams back. On stop, `finish-task` is sent, and the final
 * transcript is delivered when the server sends the `task-finished` event.
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

    /** The id of the task currently running, needed to address the matching `finish-task`. */
    private var currentTaskId: String? = null

    /** Transcripts the server has already finalized, joined together. */
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

        val taskId = UUID.randomUUID().toString().replace("-", "").take(32)
        currentTaskId = taskId

        val request = Request.Builder()
            .url(QwenService.INFERENCE_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "DIAG onOpen, sending run-task message")
                val sent = webSocket.send(runTaskMessage(taskId))
                Log.d(TAG, "DIAG run-task sent=$sent")
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
        currentTaskId?.let { taskId ->
            webSocket?.send(finishTaskMessage(taskId))
        }
        // The session is over from the user's perspective the moment they tap stop.
        listening.set(false)
        notifyListening(false)
    }

    private fun handleEvent(webSocket: WebSocket, message: String) {
        Log.d(TAG, "DIAG received message: $message")
        val event = try {
            json.decodeFromString(AsrEvent.serializer(), message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Qwen ASR event: $message", t)
            return
        }

        when (event.header?.event) {
            "task-started" -> {
                Log.d(TAG, "DIAG task-started received, beginning mic recording")
                startRecording(webSocket)
            }
            "result-generated" -> {
                val sentence = event.payload?.output?.sentence ?: return
                val text = sentence.text.orEmpty()
                if (sentence.sentenceEnd) {
                    finalizedText += text
                    partialText = ""
                } else {
                    partialText = text
                }
                notifyText(finalizedText + partialText)
            }
            "task-finished" -> {
                webSocket.close(1000, null)
                finishLocally()
            }
            "task-failed" -> {
                val reason = event.header.errorMessage ?: "Speech recognition failed"
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
            val readBuffer = ByteArray(3200)
            val sendBuffer = ByteArrayOutputStream()
            try {
                record.startRecording()
                while (listening.get()) {
                    val read = record.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        synchronized(recordedAudio) { recordedAudio.write(readBuffer, 0, read) }
                        sendBuffer.write(readBuffer, 0, read)
                        
                        while (sendBuffer.size() >= CHUNK_BYTES) {
                            val data = sendBuffer.toByteArray()
                            val chunk = data.copyOfRange(0, CHUNK_BYTES)
                            webSocket.send(chunk.toByteString())
                            
                            sendBuffer.reset()
                            if (data.size > CHUNK_BYTES) {
                                sendBuffer.write(data, CHUNK_BYTES, data.size - CHUNK_BYTES)
                            }
                        }
                    }
                }
                if (sendBuffer.size() > 0) {
                    val chunk = sendBuffer.toByteArray()
                    webSocket.send(chunk.toByteString())
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

    /**
     * Hands the captured audio and final transcript to [onSessionComplete] exactly once. Only
     * [audio] is required to be non-empty: the transcript is best-effort and may legitimately stay
     * blank (e.g. a short voice-cloning sample with no recognizable words, or background noise), and
     * callers that don't care about the transcript (such as the clone-a-voice dialog) shouldn't lose
     * a perfectly good recording just because ASR produced nothing.
     */
    private fun deliverRecording() {
        if (!completed.compareAndSet(false, true)) return
        val audio = synchronized(recordedAudio) { recordedAudio.toByteArray() }
        if (audio.isEmpty()) return
        val transcript = finalizedText.ifBlank { partialText }
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

    private fun runTaskMessage(taskId: String): String {
        val language = languageHints.firstOrNull { it in SUPPORTED_LANGUAGES }
        val hints = language?.let { listOf(it) }

        val message = AsrRequest(
            header = AsrRequestHeader(action = "run-task", taskId = taskId, streaming = "duplex"),
            payload = AsrRequestPayload(
                taskGroup = "audio",
                task = "asr",
                function = "recognition",
                model = QwenService.REALTIME_ASR_MODEL,
                parameters = AsrParameters(
                    format = "pcm",
                    sampleRate = SAMPLE_RATE,
                    languageHints = hints,
                ),
                input = AsrInput(),
            ),
        )
        return json.encodeToString(AsrRequest.serializer(), message)
    }

    private fun finishTaskMessage(taskId: String): String {
        val message = AsrRequest(
            header = AsrRequestHeader(action = "finish-task", taskId = taskId, streaming = "duplex"),
            payload = AsrRequestPayload(input = AsrInput()),
        )
        return json.encodeToString(AsrRequest.serializer(), message)
    }

    companion object {
        private const val TAG = "QwenSpeechRecognizer"

        private const val SAMPLE_RATE = 16_000

        /**
         * The `language` codes the [QwenService.REALTIME_ASR_MODEL] can bias toward. Codes outside
         * this set (e.g. Burmese) are dropped so the model falls back to automatic language
         * detection rather than failing or defaulting to the wrong language.
         */
        private val SUPPORTED_LANGUAGES = setOf(
            "zh", "en", "yue", "ja", "ko", "ru", "vi", "de", "fr", "es", "pt",
            "id", "it", "ar", "th", "tr", "hi", "ms", "nl",
        )

        /** ~400 ms of 16 kHz mono 16-bit PCM audio per streamed frame. */
        internal const val CHUNK_BYTES = 12_800
    }
}

@Serializable
private data class AsrRequest(
    val header: AsrRequestHeader,
    val payload: AsrRequestPayload,
)

@Serializable
private data class AsrRequestHeader(
    val action: String,
    @SerialName("task_id") val taskId: String? = null,
    val streaming: String,
)

@Serializable
private data class AsrRequestPayload(
    @SerialName("task_group") val taskGroup: String? = null,
    val task: String? = null,
    val function: String? = null,
    val model: String? = null,
    val parameters: AsrParameters? = null,
    val input: AsrInput? = null,
)

@Serializable
private data class AsrParameters(
    val format: String,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("language_hints") val languageHints: List<String>? = null,
)

@Serializable
private class AsrInput

@Serializable
private data class AsrEvent(
    val header: AsrEventHeader? = null,
    val payload: AsrEventPayload? = null,
)

@Serializable
private data class AsrEventHeader(
    val event: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
private data class AsrEventPayload(
    val output: AsrEventOutput? = null,
)

@Serializable
private data class AsrEventOutput(
    val sentence: AsrSentence? = null,
)

@Serializable
private data class AsrSentence(
    val text: String? = null,
    @SerialName("sentence_end") val sentenceEnd: Boolean = false,
)
