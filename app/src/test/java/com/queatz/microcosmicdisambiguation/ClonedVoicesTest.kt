package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.data.ClonedVoice
import com.queatz.microcosmicdisambiguation.data.ClonedVoices
import com.queatz.microcosmicdisambiguation.data.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonedVoicesTest {

    private fun voice(
        id: String,
        language: Language,
        voiceId: String,
        enabled: Boolean = true,
        createdAt: Long = 1L,
    ) = ClonedVoice(
        id = id,
        name = id,
        language = language,
        voiceId = voiceId,
        enabled = enabled,
        createdAt = createdAt,
    )

    @Test
    fun encodeDecode_roundTripsAllFields() {
        val voices = listOf(
            voice("a", Language.Chinese, "cosy-a", enabled = true, createdAt = 1L),
            voice("b", Language.English, "cosy-b", enabled = false, createdAt = 2L),
        )
        val decoded = ClonedVoices.decode(ClonedVoices.encode(voices))
        assertEquals(voices, decoded)
    }

    @Test
    fun decode_returnsEmptyForBlankOrMalformed() {
        assertTrue(ClonedVoices.decode(null).isEmpty())
        assertTrue(ClonedVoices.decode("").isEmpty())
        assertTrue(ClonedVoices.decode("   ").isEmpty())
        assertTrue(ClonedVoices.decode("not json").isEmpty())
    }

    @Test
    fun enabledVoiceIdFor_picksMostRecentEnabledMatchingLanguage() {
        val voices = listOf(
            voice("old", Language.Chinese, "cosy-old", enabled = true, createdAt = 1L),
            voice("new", Language.Chinese, "cosy-new", enabled = true, createdAt = 5L),
        )
        assertEquals("cosy-new", ClonedVoices.enabledVoiceIdFor(voices, Language.Chinese))
    }

    @Test
    fun enabledVoiceIdFor_ignoresDisabledAndOtherLanguages() {
        val voices = listOf(
            voice("disabled", Language.Chinese, "cosy-disabled", enabled = false, createdAt = 9L),
            voice("english", Language.English, "cosy-english", enabled = true, createdAt = 8L),
            voice("enabled", Language.Chinese, "cosy-enabled", enabled = true, createdAt = 2L),
        )
        assertEquals("cosy-enabled", ClonedVoices.enabledVoiceIdFor(voices, Language.Chinese))
    }

    @Test
    fun enabledVoiceIdFor_returnsNullWhenNoneEnabledForLanguage() {
        val voices = listOf(
            voice("disabled", Language.Chinese, "cosy-disabled", enabled = false, createdAt = 1L),
            voice("english", Language.English, "cosy-english", enabled = true, createdAt = 2L),
        )
        assertNull(ClonedVoices.enabledVoiceIdFor(voices, Language.Chinese))
        assertNull(ClonedVoices.enabledVoiceIdFor(emptyList(), Language.Chinese))
    }
}
