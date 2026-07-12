package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class QwenChunkingTest {

    @Test
    fun testChunkBytesValue() {
        // Verify that CHUNK_BYTES is updated to 12800 (400ms)
        assertEquals(12800, QwenSpeechRecognizer.CHUNK_BYTES)
    }

    @Test
    fun testBufferingLogicSimulator() {
        val chunkBytes = 12800
        val inputChunks = listOf(
            ByteArray(3200).apply { for (i in indices) this[i] = 1 },
            ByteArray(3200).apply { for (i in indices) this[i] = 2 },
            ByteArray(3200).apply { for (i in indices) this[i] = 3 },
            ByteArray(3200).apply { for (i in indices) this[i] = 4 }, // Total 12800 bytes
            ByteArray(1000).apply { for (i in indices) this[i] = 5 },
            ByteArray(2200).apply { for (i in indices) this[i] = 6 }  // Total 16000 bytes
        )

        val sentChunks = mutableListOf<ByteArray>()
        val sendBuffer = ByteArrayOutputStream()

        for (input in inputChunks) {
            sendBuffer.write(input)
            while (sendBuffer.size() >= chunkBytes) {
                val data = sendBuffer.toByteArray()
                val chunk = data.copyOfRange(0, chunkBytes)
                sentChunks.add(chunk)

                sendBuffer.reset()
                if (data.size > chunkBytes) {
                    sendBuffer.write(data, chunkBytes, data.size - chunkBytes)
                }
            }
        }

        // On stop, send remainder
        if (sendBuffer.size() > 0) {
            sentChunks.add(sendBuffer.toByteArray())
        }

        // We expect exactly two chunks sent:
        // Chunk 1: size 12800, filled with 1s, 2s, 3s, 4s
        // Chunk 2: size 3200, filled with 5s and 6s
        assertEquals(2, sentChunks.size)
        assertEquals(12800, sentChunks[0].size)
        assertEquals(3200, sentChunks[1].size)

        // Verify content
        assertEquals(1.toByte(), sentChunks[0][0])
        assertEquals(2.toByte(), sentChunks[0][3200])
        assertEquals(3.toByte(), sentChunks[0][6400])
        assertEquals(4.toByte(), sentChunks[0][9600])

        assertEquals(5.toByte(), sentChunks[1][0])
        assertEquals(6.toByte(), sentChunks[1][1000])
    }
}
