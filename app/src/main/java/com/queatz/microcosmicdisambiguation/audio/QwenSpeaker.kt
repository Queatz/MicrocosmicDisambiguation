package com.queatz.microcosmicdisambiguation.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.BLANK
import com.queatz.microcosmicdisambiguation.data.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A [TtsEngine] backed by the Qwen (Alibaba DashScope) cloud TTS service.
 *
 * Unlike the on-device [Speaker], the audio is generated remotely and returned as a single file
 * with no per-word timing information. To satisfy the study screen — which highlights characters as
 * they are spoken via [speak]'s `onRange` callback — this engine measures the generated audio's
 * duration and distributes the speakable characters of the sentence evenly across it, emitting a
 * `[start, end)` range for each character in sync with playback. This reproduces the same range
 * stream the system engine provides, so every highlight feature works identically.
 *
 * Generated audio is cached per sentence so the frequent re-plays on the study screen don't re-hit
 * the network.
 */
class QwenSpeaker(
    context: Context,
    private val qwen: QwenService,
    private val language: Language,
) : TtsEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cache of generated audio files keyed by the exact text that produced them. */
    private val audioCache = ConcurrentHashMap<String, File>()

    private var mediaPlayer: MediaPlayer? = null
    private var rangeJob: Job? = null
    private var speakJob: Job? = null

    // The cloud engine doesn't need on-device voice data, so it's ready as soon as it exists.
    @Volatile
    override var status: SpeakerStatus = SpeakerStatus.Ready
        private set

    override var onStatusChanged: ((SpeakerStatus) -> Unit)? = null
        set(value) {
            field = value
            val current = status
            mainHandler.post { value?.invoke(current) }
        }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onRange: (start: Int, end: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        stopPlayback()

        // The characters the listener will actually hear, mapped back to their offsets in [text],
        // so the emitted ranges line up with what the UI renders (including the blank marker).
        val speakable = speakableRanges(text, language.characterSegmented)
        // Text sent for synthesis: drop the blank markers so the model doesn't read underscores.
        val synthesisText = text.replace(BLANK, " ")
        if (synthesisText.isBlank()) {
            mainHandler.post { onDone() }
            return
        }

        speakJob = scope.launch {
            val file = try {
                audioFor(synthesisText)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to synthesize/download Qwen TTS audio", t)
                mainHandler.post { onDone() }
                return@launch
            }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                play(file, speakable, onStart, onRange, onDone)
            }
        }
    }

    private fun play(
        file: File,
        speakable: List<IntRange>,
        onStart: () -> Unit,
        onRange: (start: Int, end: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(file.absolutePath)
            // The real audio duration, parsed from the WAV header. MediaPlayer.getDuration() is
            // unreliable for these streamed 24kHz Qwen WAVs (it can report a wildly wrong value),
            // which is what made the highlight freeze on the first word: a bogus duration made the
            // proportional index stay at 0 forever. The header gives us the true length.
            val headerDurationMs = wavDurationMs(file)
            player.setOnPreparedListener {
                onStart()
                it.start()
                // Fall back to the (less reliable) player duration only if the header couldn't be
                // parsed; by now the player is prepared so getDuration() is at least valid to call.
                val durationMs = headerDurationMs.takeIf { d -> d > 0 }
                    ?: it.duration.toLong().coerceAtLeast(0L)
                scheduleRanges(durationMs, speakable, onRange)
            }
            player.setOnCompletionListener {
                cancelRangeJob()
                releasePlayer()
                onDone()
            }
            player.setOnErrorListener { _, _, _ ->
                cancelRangeJob()
                releasePlayer()
                onDone()
                true
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to play Qwen TTS audio", t)
            releasePlayer()
            onDone()
        }
    }

    /**
     * Drives [onRange] highlighting by advancing through the speakable characters proportionally to
     * the wall-clock time elapsed since playback started, using the [durationMs] parsed from the
     * WAV header.
     *
     * Wall-clock timing (rather than [MediaPlayer.getCurrentPosition]/[MediaPlayer.getDuration]) is
     * deliberate: the player's reported duration is unreliable for these streamed Qwen WAVs, and a
     * bogus duration is exactly what previously pinned the proportional index at 0, freezing the
     * highlight on the first word. The header-derived [durationMs] is accurate, so a simple elapsed
     * timer keeps the highlight marching through the whole sentence.
     *
     * Runs off the main thread (so its timer is independent of main-thread load) and posts the
     * highlight updates back to the main thread, mirroring how [Speaker] delivers its callbacks.
     */
    private fun scheduleRanges(
        durationMs: Long,
        speakable: List<IntRange>,
        onRange: (start: Int, end: Int) -> Unit,
    ) {
        if (speakable.isEmpty() || durationMs <= 0) return
        // The clip starts with LEAD_SILENCE_MS of padding (see prependSilence), so the spoken
        // characters only occupy the remainder. Map elapsed time onto that speech window so the
        // highlight doesn't run ahead of the audio during the silent lead-in.
        val leadMs = LEAD_SILENCE_MS.coerceIn(0, durationMs)
        val speechDurationMs = (durationMs - leadMs).coerceAtLeast(1L)
        rangeJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var lastIndex = -1
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val elapsedSpeech = (elapsed - leadMs).coerceAtLeast(0L)
                val index = (elapsedSpeech * speakable.size / speechDurationMs)
                    .toInt()
                    .coerceIn(0, speakable.size - 1)
                if (index != lastIndex) {
                    lastIndex = index
                    val range = speakable[index]
                    mainHandler.post { onRange(range.first, range.last + 1) }
                }
                if (elapsed >= durationMs) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Returns the cached audio for [text], generating and caching it on first use. */
    private suspend fun audioFor(text: String): File {
        audioCache[text]?.let { if (it.exists()) return it }
        val bytes = qwen.synthesizeSpeech(text, language)
        // Prepend a short lead-in of silence so the first few milliseconds of speech are never
        // clipped before playback ramps up.
        val padded = prependSilence(bytes, LEAD_SILENCE_MS)
        val file = File(appContext.cacheDir, "qwen_tts_${text.hashCode()}.wav")
        file.writeBytes(padded)
        audioCache[text] = file
        return file
    }

    private fun stopPlayback() {
        speakJob?.cancel()
        speakJob = null
        cancelRangeJob()
        releasePlayer()
    }

    private fun cancelRangeJob() {
        rangeJob?.cancel()
        rangeJob = null
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            player.release()
        }
        mediaPlayer = null
    }

    override fun shutdown() {
        onStatusChanged = null
        stopPlayback()
        scope.cancel()
    }

    /**
     * Parses the playback duration (in milliseconds) directly from a PCM WAV file's header. Returns
     * 0 if the file can't be read or parsed, so the caller can fall back. This is the reliable
     * alternative to [MediaPlayer.getDuration], which misreports these files.
     */
    private fun wavDurationMs(file: File): Long = try {
        wavDurationMs(file.readBytes())
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to parse WAV duration", t)
        0L
    }

    companion object {
        private const val TAG = "QwenSpeaker"

        /**
         * Computes the playback duration (in milliseconds) from a PCM WAV header by dividing the
         * `data` chunk size by the `fmt ` chunk's byte rate. Returns 0 when the bytes aren't a
         * parseable RIFF/WAVE container.
         */
        internal fun wavDurationMs(bytes: ByteArray): Long {
            if (bytes.size < 44) return 0L
            fun u32(o: Int): Long =
                (bytes[o].toLong() and 0xFF) or
                    ((bytes[o + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[o + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[o + 3].toLong() and 0xFF) shl 24)
            // Verify RIFF/WAVE container before trusting the chunk layout.
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return 0L
            }
            var byteRate = 0L
            var dataSize = 0L
            var i = 12
            while (i + 8 <= bytes.size) {
                val id = String(bytes, i, 4, Charsets.US_ASCII)
                val size = u32(i + 4)
                if (id == "fmt ") {
                    // fmt data layout: audioFormat(2) channels(2) sampleRate(4) byteRate(4)...
                    byteRate = u32(i + 8 + 8)
                }
                if (id == "data") {
                    // The Qwen WAVs are stream-generated, so their declared RIFF/data sizes are
                    // bogus placeholders (~Int.MAX). Trust the actual bytes present instead, which
                    // is the whole point of parsing the header ourselves rather than letting
                    // MediaPlayer derive a ~12-hour duration from the fake size.
                    val available = (bytes.size - (i + 8)).toLong().coerceAtLeast(0L)
                    dataSize = if (size in 1..available) size else available
                    break
                }
                // Chunks are word-aligned, so an odd size is followed by a pad byte.
                i += 8 + size.toInt() + (size.toInt() and 1)
            }
            return if (byteRate > 0 && dataSize > 0) dataSize * 1000 / byteRate else 0L
        }

        /** How often (ms) the highlight is advanced against the elapsed-time clock (~30fps). */
        private const val POLL_INTERVAL_MS = 33L

        /**
         * Silence (in ms) prepended to every generated clip. The Qwen WAVs are stream-generated and
         * occasionally have their first few milliseconds clipped by the time playback actually
         * starts, so the opening sound is lost. Prepending a short run of silent PCM frames gives
         * the player a small lead-in, guaranteeing the real audio is heard in full.
         */
        internal const val LEAD_SILENCE_MS = 200L

        /**
         * Returns a copy of the PCM WAV [bytes] with [silenceMs] of silent frames inserted at the
         * start of the `data` chunk, leaving the original audio otherwise untouched. The declared
         * RIFF/`data` sizes are bumped by the inserted amount when they look valid, and left as-is
         * when they're the bogus stream placeholders. Returns the input unchanged if it isn't a
         * parseable RIFF/WAVE container or [silenceMs] is non-positive.
         */
        internal fun prependSilence(bytes: ByteArray, silenceMs: Long): ByteArray {
            if (silenceMs <= 0 || bytes.size < 44) return bytes
            fun u16(o: Int): Int =
                (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
            fun u32(o: Int): Long =
                (bytes[o].toLong() and 0xFF) or
                    ((bytes[o + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[o + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[o + 3].toLong() and 0xFF) shl 24)
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                return bytes
            }
            var byteRate = 0L
            var blockAlign = 1
            var dataHeaderStart = -1
            var declaredDataSize = 0L
            var i = 12
            while (i + 8 <= bytes.size) {
                val id = String(bytes, i, 4, Charsets.US_ASCII)
                val size = u32(i + 4)
                if (id == "fmt ") {
                    // fmt data layout: audioFormat(2) channels(2) sampleRate(4) byteRate(4)
                    // blockAlign(2) bitsPerSample(2)
                    byteRate = u32(i + 8 + 8)
                    blockAlign = u16(i + 8 + 12).coerceAtLeast(1)
                }
                if (id == "data") {
                    dataHeaderStart = i
                    declaredDataSize = size
                    break
                }
                i += 8 + size.toInt() + (size.toInt() and 1)
            }
            if (byteRate <= 0 || dataHeaderStart < 0) return bytes
            // Align the silence to whole frames so we don't desync the 16-bit PCM samples.
            var silenceBytes = (silenceMs * byteRate / 1000)
            silenceBytes -= silenceBytes % blockAlign
            if (silenceBytes <= 0) return bytes
            val dataStart = dataHeaderStart + 8
            val out = ByteArray(bytes.size + silenceBytes.toInt())
            // Header (RIFF..data header) unchanged, then silence, then the original audio data.
            System.arraycopy(bytes, 0, out, 0, dataStart)
            System.arraycopy(bytes, dataStart, out, dataStart + silenceBytes.toInt(), bytes.size - dataStart)
            fun writeU32(o: Int, v: Long) {
                out[o] = (v and 0xFF).toByte()
                out[o + 1] = ((v ushr 8) and 0xFF).toByte()
                out[o + 2] = ((v ushr 16) and 0xFF).toByte()
                out[o + 3] = ((v ushr 24) and 0xFF).toByte()
            }
            // Only adjust the declared sizes when they're real values; the stream-generated files
            // use bogus ~Int.MAX placeholders that must be left alone (see wavDurationMs).
            val availableData = (bytes.size - dataStart).toLong()
            if (declaredDataSize in 1..availableData) {
                writeU32(dataHeaderStart + 4, declaredDataSize + silenceBytes)
                val riffSize = u32(4)
                writeU32(4, riffSize + silenceBytes)
            }
            return out
        }

        /**
         * The `[start, end)` ranges (into [text]) of the units that are actually spoken, used to
         * drive proportional highlighting in sync with the generated audio.
         *
         * For [characterSegmented] languages (e.g. Chinese, Japanese), which are written without
         * spaces between words, each speakable character becomes its own range. For
         * whitespace-delimited languages (e.g. English, Russian, Vietnamese, Korean), each
         * whitespace-separated word becomes a single range, so the highlight advances word by word
         * rather than letter by letter. The blank marker and whitespace are skipped in both modes.
         */
        private fun speakableRanges(text: String, characterSegmented: Boolean): List<IntRange> =
            if (characterSegmented) characterRanges(text) else wordRanges(text)

        /** Per-character speakable ranges, for languages written without spaces between words. */
        private fun characterRanges(text: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (i < text.length) {
                if (text.startsWith(BLANK, i)) {
                    i += BLANK.length
                    continue
                }
                val c = text[i]
                if (!c.isWhitespace() && (c.isLetterOrDigit() || isCjk(c))) {
                    ranges.add(i..i)
                }
                i++
            }
            return ranges
        }

        /** Per-word speakable ranges, for whitespace-delimited languages. */
        private fun wordRanges(text: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (i < text.length) {
                if (text.startsWith(BLANK, i)) {
                    i += BLANK.length
                    continue
                }
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val start = i
                while (i < text.length && !text[i].isWhitespace() && !text.startsWith(BLANK, i)) {
                    i++
                }
                ranges.add(start..(i - 1))
            }
            return ranges
        }

        private fun isCjk(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c) ?: return false
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.GENERAL_PUNCTUATION
        }
    }
}
