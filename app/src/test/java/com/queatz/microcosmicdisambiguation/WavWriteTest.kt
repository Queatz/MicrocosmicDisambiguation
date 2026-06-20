package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.audio.QwenSpeaker
import com.queatz.microcosmicdisambiguation.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriteTest {

    @Test
    fun pcmToWav_writesParseableHeaderWithCorrectDuration() {
        // 16kHz mono 16-bit (byteRate 32000). 1 second of audio = 32000 bytes.
        val pcm = ByteArray(32_000)
        val wav = Wav.pcmToWav(pcm)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        // Header (44 bytes) plus the raw PCM payload.
        assertEquals(44 + pcm.size, wav.size)
        // The declared sizes are real, so the duration reads back as exactly 1 second.
        assertEquals(1000L, QwenSpeaker.wavDurationMs(wav))
    }

    @Test
    fun pcmToWav_respectsCustomFormat() {
        // 24kHz mono 16-bit (byteRate 48000). 48000 bytes = 1 second.
        val pcm = ByteArray(48_000)
        val wav = Wav.pcmToWav(pcm, sampleRate = 24_000)
        assertEquals(1000L, QwenSpeaker.wavDurationMs(wav))
    }
}
