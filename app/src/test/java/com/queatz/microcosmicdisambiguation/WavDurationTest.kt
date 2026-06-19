package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.audio.QwenSpeaker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavDurationTest {

    /**
     * Builds a minimal PCM WAV header followed by [dataBytes] of audio data. The RIFF and `data`
     * chunk sizes are written as [declaredDataSize], which lets us reproduce the stream-generated
     * Qwen files whose declared sizes are bogus placeholders.
     */
    private fun wav(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataBytes: Int,
        declaredDataSize: Long,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
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
        u32(declaredDataSize) // bogus overall size, mirroring the real files
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
        u32(declaredDataSize) // bogus data size
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    @Test
    fun wavDurationMs_usesRealBytesWhenDeclaredSizeIsBogus() {
        // Reproduces the real Qwen sample: 24kHz mono 16-bit, byteRate 48000, ~80640 data bytes,
        // but with the near-Int.MAX bogus size that fooled MediaPlayer into a ~12h duration.
        val bytes = wav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 80640,
            declaredDataSize = 0x7FFFFF9BL,
        )
        // 80640 bytes / 48000 bytes-per-second = 1.68s
        assertEquals(1680L, QwenSpeaker.wavDurationMs(bytes))
    }

    @Test
    fun wavDurationMs_usesDeclaredSizeWhenValid() {
        val bytes = wav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            dataBytes = 48000,
            declaredDataSize = 48000L,
        )
        // 48000 bytes / 48000 bytes-per-second = 1.0s
        assertEquals(1000L, QwenSpeaker.wavDurationMs(bytes))
    }

    @Test
    fun wavDurationMs_returnsZeroForNonWav() {
        assertEquals(0L, QwenSpeaker.wavDurationMs("not a wav file at all!!".toByteArray()))
    }
}
