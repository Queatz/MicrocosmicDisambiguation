package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.data.comparePronunciation
import com.queatz.microcosmicdisambiguation.data.pronunciationUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationTest {

    @Test
    fun pronunciationUnits_splitsCharacterSegmentedLanguageByCharacter() {
        val units = pronunciationUnits("我想买苹果。", characterSegmented = true)
        // Punctuation is dropped; every remaining character is its own unit.
        assertEquals(listOf("我", "想", "买", "苹", "果"), units)
    }

    @Test
    fun pronunciationUnits_splitsWhitespaceLanguageByWord() {
        val units = pronunciationUnits("I want to buy apples.", characterSegmented = false)
        assertEquals(listOf("I", "want", "to", "buy", "apples"), units)
    }

    @Test
    fun comparePronunciation_perfectMatchScoresFull() {
        val feedback = comparePronunciation("我想买苹果。", "我想买苹果", characterSegmented = true)
        assertEquals(1.0, feedback.score, 0.0001)
        assertTrue(feedback.tokens.all { it.correct })
        assertEquals(listOf("我", "想", "买", "苹", "果"), feedback.tokens.map { it.text })
    }

    @Test
    fun comparePronunciation_marksMissingCharacterAsIncorrect() {
        // The learner skipped "买", so only that character should be flagged wrong.
        val feedback = comparePronunciation("我想买苹果", "我想苹果", characterSegmented = true)
        val wrong = feedback.tokens.filterNot { it.correct }.map { it.text }
        assertEquals(listOf("买"), wrong)
        assertEquals(4.0 / 5.0, feedback.score, 0.0001)
    }

    @Test
    fun comparePronunciation_isCaseInsensitiveForWords() {
        val feedback = comparePronunciation(
            target = "I want Coffee",
            spoken = "i WANT coffee",
            characterSegmented = false,
        )
        assertEquals(1.0, feedback.score, 0.0001)
        assertTrue(feedback.tokens.all { it.correct })
    }

    @Test
    fun comparePronunciation_extraSpokenWordsDoNotBreakAlignment() {
        // The learner said an extra word ("really"); the real target words still match.
        val feedback = comparePronunciation(
            target = "I want coffee",
            spoken = "I really want coffee",
            characterSegmented = false,
        )
        assertEquals(1.0, feedback.score, 0.0001)
    }

    @Test
    fun comparePronunciation_wrongWordIsFlagged() {
        val feedback = comparePronunciation(
            target = "I want coffee",
            spoken = "I want tea",
            characterSegmented = false,
        )
        val coffee = feedback.tokens.single { it.text == "coffee" }
        assertFalse(coffee.correct)
        assertEquals(2.0 / 3.0, feedback.score, 0.0001)
    }

    @Test
    fun comparePronunciation_silenceScoresZero() {
        val feedback = comparePronunciation("我想买苹果", "", characterSegmented = true)
        assertEquals(0.0, feedback.score, 0.0001)
        assertTrue(feedback.tokens.none { it.correct })
    }
}
