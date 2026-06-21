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
 * surfaces a live, growing transcript. The Paraformer real-time model recognizes both Chinese and
 * English (via `language_hints`), so the home screen's "IRL situation" field can be dictated in
 * either language.
 *
 * The flow follows DashScope's WebSocket protocol: connect, send a `run-task` instruction, wait for
 * `task-started`, then push raw 16 kHz mono PCM frames captured from [AudioRecord] while consuming
 * the `result-generated` events the server streams back. Finalized sentences are concatenated; the
 * sentence currently being spoken is appended live so the text field updates in real time.
 *
 * All listener callbacks are delivered on the main thread. The caller is responsible for holding the
 * `RECORD_AUDIO` runtime permission before invoking [start].
 */
class QwenSpeechRecognizer(
    private val apiKey: String = QwenService.QWEN_API_KEY,
    /** `language_hints` codes biasing recognition toward the languages the learner dictates in. */
    private val languageHints: List<String> = emptyList(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    private val listening = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /** The id of the task currently running, needed to address the matching `finish-task`. */
    private var currentTaskId: String? = null

    /** Sentences the server has already finalized, joined together. */
    private var finalizedText: String = ""

    /** The in-progress sentence the server is still refining. */
    private var partialText: String = ""

    /** Accumulates the raw PCM frames captured during the session so they can be saved on stop. */
    private val recordedAudio = ByteArrayOutputStream()

    /** Guards against delivering [onSessionComplete] more than once per session. */
    private val completed = AtomicBoolean(false)

    var onTextChanged: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Invoked once a session ends normally, delivering the raw 16 kHz mono 16-bit PCM audio that was
     * captured along with the final transcript. Not called if the session fails or is shut down.
     */
    var onSessionComplete: ((audio: ByteArray, transcript: String) -> Unit)? = null

    val isListening: Boolean
        get() = listening.get()

    /**
     * Opens the recognition session and begins streaming microphone audio. The recognized text is
     * delivered incrementally via [onTextChanged]. Has no effect if already listening.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!listening.compareAndSet(false, true)) return

        finalizedText = ""
        partialText = ""
        completed.set(false)
        synchronized(recordedAudio) { recordedAudio.reset() }
        notifyListening(true)

        val taskId = UUID.randomUUID().toString().replace("-", "").take(32)
        currentTaskId = taskId
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "bearer $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(runTaskMessage(taskId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(webSocket, taskId, text)
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
    fun stop() {
        if (!listening.get()) return
        stopRecording()
        currentTaskId?.let { webSocket?.send(finishTaskMessage(it)) }
        // The session is over from the user's perspective the moment they tap stop.
        listening.set(false)
        notifyListening(false)
    }

    private fun handleEvent(webSocket: WebSocket, taskId: String, message: String) {
        val event = try {
            json.decodeFromString(AsrEvent.serializer(), message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Qwen ASR event: $message", t)
            return
        }

        when (event.header?.event) {
            "task-started" -> startRecording(webSocket)
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
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                while (listening.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val frame: ByteString = buffer.toByteString(0, read)
                        webSocket.send(frame)
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
        val transcript = finalizedText
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
    fun shutdown() {
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
        val message = AsrRequest(
            header = AsrRequestHeader(action = "run-task", taskId = taskId, streaming = "duplex"),
            payload = AsrRequestPayload(
                taskGroup = "audio",
                task = "asr",
                function = "recognition",
                model = MODEL,
                parameters = AsrParameters(
                    format = "pcm",
                    sampleRate = SAMPLE_RATE,
                    languageHints = languageHints.takeIf { it.isNotEmpty() },
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

        private const val ENDPOINT =
            "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference/"
        private const val MODEL = "fun-asr-realtime"

        private const val SAMPLE_RATE = 16_000

        /** ~100 ms of 16 kHz mono 16-bit PCM audio per streamed frame. */
        private const val CHUNK_BYTES = 3_200
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
