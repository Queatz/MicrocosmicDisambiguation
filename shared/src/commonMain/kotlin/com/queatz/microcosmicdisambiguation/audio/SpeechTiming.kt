package com.queatz.microcosmicdisambiguation.audio

import com.queatz.microcosmicdisambiguation.data.BLANK

/**
 * Multiplatform helpers for driving proportional word/character highlighting against generated
 * (Qwen) TTS audio. The Android engine has its own equivalents tuned to `MediaPlayer`; these are the
 * shared versions used by the web engine so the highlight behaves identically in the browser.
 */

/** Silence (in ms) prepended to every generated clip so its opening isn't clipped before playback. */
const val LEAD_SILENCE_MS: Long = 200L

/**
 * Computes the playback duration (in milliseconds) from a PCM WAV header by dividing the `data`
 * chunk size by the `fmt ` chunk's byte rate. Returns 0 when the bytes aren't a parseable RIFF/WAVE
 * container. The stream-generated Qwen WAVs carry bogus declared sizes, so the actual byte count is
 * trusted instead.
 */
fun wavDurationMs(bytes: ByteArray): Long {
    if (bytes.size < 44) return 0L
    fun u32(o: Int): Long =
        (bytes[o].toLong() and 0xFF) or
            ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or
            ((bytes[o + 3].toLong() and 0xFF) shl 24)
    if (bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return 0L
    var byteRate = 0L
    var dataSize = 0L
    var i = 12
    while (i + 8 <= bytes.size) {
        val id = bytes.decodeToString(i, i + 4)
        val size = u32(i + 4)
        if (id == "fmt ") {
            byteRate = u32(i + 8 + 8)
        }
        if (id == "data") {
            val available = (bytes.size - (i + 8)).toLong().coerceAtLeast(0L)
            dataSize = if (size in 1..available) size else available
            break
        }
        i += 8 + size.toInt() + (size.toInt() and 1)
    }
    return if (byteRate > 0 && dataSize > 0) dataSize * 1000 / byteRate else 0L
}

/**
 * Returns a copy of the PCM WAV [bytes] with [silenceMs] of silent frames inserted at the start of
 * the `data` chunk. Declared RIFF/`data` sizes are bumped only when they look valid (the
 * stream-generated placeholders are left alone). Returns the input unchanged if it isn't a parseable
 * RIFF/WAVE container or [silenceMs] is non-positive.
 */
fun prependSilence(bytes: ByteArray, silenceMs: Long): ByteArray {
    if (silenceMs <= 0 || bytes.size < 44) return bytes
    fun u16(o: Int): Int = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
    fun u32(o: Int): Long =
        (bytes[o].toLong() and 0xFF) or
            ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or
            ((bytes[o + 3].toLong() and 0xFF) shl 24)
    if (bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return bytes
    var byteRate = 0L
    var blockAlign = 1
    var dataHeaderStart = -1
    var declaredDataSize = 0L
    var i = 12
    while (i + 8 <= bytes.size) {
        val id = bytes.decodeToString(i, i + 4)
        val size = u32(i + 4)
        if (id == "fmt ") {
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
    var silenceBytes = silenceMs * byteRate / 1000
    silenceBytes -= silenceBytes % blockAlign
    if (silenceBytes <= 0) return bytes
    val dataStart = dataHeaderStart + 8
    val out = ByteArray(bytes.size + silenceBytes.toInt())
    bytes.copyInto(out, 0, 0, dataStart)
    bytes.copyInto(out, dataStart + silenceBytes.toInt(), dataStart, bytes.size)
    fun writeU32(o: Int, v: Long) {
        out[o] = (v and 0xFF).toByte()
        out[o + 1] = ((v ushr 8) and 0xFF).toByte()
        out[o + 2] = ((v ushr 16) and 0xFF).toByte()
        out[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    val availableData = (bytes.size - dataStart).toLong()
    if (declaredDataSize in 1..availableData) {
        writeU32(dataHeaderStart + 4, declaredDataSize + silenceBytes)
        val riffSize = u32(4)
        writeU32(4, riffSize + silenceBytes)
    }
    return out
}

/**
 * The `[start, end]` ranges (into [text]) of the units that are actually spoken, used to drive
 * proportional highlighting in sync with generated audio. Character-segmented languages (Chinese,
 * Japanese) highlight character by character; whitespace-delimited languages highlight word by word.
 * The blank marker and whitespace are skipped in both modes.
 */
fun speakableRanges(text: String, characterSegmented: Boolean): List<IntRange> =
    if (characterSegmented) characterRanges(text) else wordRanges(text)

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

/** True for CJK ideographs and CJK/general punctuation, matching the Android engine's classifier. */
fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
        code in 0x3400..0x4DBF ||       // CJK Unified Ideographs Extension A
        code in 0x3000..0x303F ||       // CJK Symbols and Punctuation
        code in 0x2000..0x206F          // General Punctuation
}
