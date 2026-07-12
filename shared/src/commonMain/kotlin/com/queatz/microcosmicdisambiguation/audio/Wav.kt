package com.queatz.microcosmicdisambiguation.audio

/**
 * Minimal, multiplatform helpers for wrapping raw PCM samples in a PCM/WAVE container so captured
 * microphone audio can be saved and replayed on any platform (a `.wav` file on Android, a blob on
 * the web).
 */
object Wav {

    /**
     * Wraps raw little-endian PCM [pcm] in a canonical 44-byte RIFF/WAVE header. Defaults match the
     * microphone capture format used by the speech recognizer: 16 kHz, mono, 16-bit.
     */
    fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + pcm.size)
        var p = 0

        fun str(s: String) {
            // The chunk ids are ASCII, so byte-per-char encoding is exact.
            for (c in s) out[p++] = c.code.toByte()
        }
        fun u32(v: Long) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v ushr 8) and 0xFF).toByte()
            out[p++] = ((v ushr 16) and 0xFF).toByte()
            out[p++] = ((v ushr 24) and 0xFF).toByte()
        }
        fun u16(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v ushr 8) and 0xFF).toByte()
        }

        str("RIFF")
        u32((36 + pcm.size).toLong())
        str("WAVE")
        str("fmt ")
        u32(16)
        u16(1) // PCM
        u16(channels)
        u32(sampleRate.toLong())
        u32(byteRate.toLong())
        u16(blockAlign)
        u16(bitsPerSample)
        str("data")
        u32(pcm.size.toLong())
        pcm.copyInto(out, p)
        return out
    }
}
