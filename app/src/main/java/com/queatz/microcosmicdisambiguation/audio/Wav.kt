package com.queatz.microcosmicdisambiguation.audio

import java.io.ByteArrayOutputStream

/**
 * Minimal helpers for wrapping raw PCM samples in a PCM/WAVE container so captured microphone audio
 * can be saved to a `.wav` file and replayed by [android.media.MediaPlayer].
 */
object Wav {

    /**
     * Wraps raw little-endian PCM [pcm] in a canonical 44-byte RIFF/WAVE header, producing bytes
     * ready to be written to a `.wav` file. Defaults match the microphone capture format used by
     * [QwenSpeechRecognizer]: 16 kHz, mono, 16-bit.
     */
    fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write((v ushr 8 and 0xFF).toInt())
            out.write((v ushr 16 and 0xFF).toInt())
            out.write((v ushr 24 and 0xFF).toInt())
        }
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write(v ushr 8 and 0xFF)
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
        out.write(pcm)
        return out.toByteArray()
    }
}
