package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.data.SentenceStructure
import com.queatz.microcosmicdisambiguation.data.StudyWord
import com.queatz.microcosmicdisambiguation.data.coinPositions
import com.queatz.microcosmicdisambiguation.data.coinSeed
import com.queatz.microcosmicdisambiguation.data.isWordSpoken
import com.queatz.microcosmicdisambiguation.data.playCoinWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayLogicTest {

    // --- isWordSpoken: judging whether the learner said a coin's word ---

    @Test
    fun isWordSpoken_matchesExactWord() {
        assertTrue(isWordSpoken("苹果", "苹果", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_matchesWordInsideALongerUtterance() {
        // The learner may say extra words around the target; the word still counts.
        assertTrue(isWordSpoken("苹果", "我想买苹果。", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_rejectsAPartialMatch() {
        // Only half the word was said, so the coin must not flip.
        assertFalse(isWordSpoken("苹果", "苹", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_rejectsADifferentWord() {
        assertFalse(isWordSpoken("苹果", "香蕉", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_rejectsBlankTranscriptAndBlankTarget() {
        assertFalse(isWordSpoken("苹果", "", characterSegmented = true))
        assertFalse(isWordSpoken("苹果", "   ", characterSegmented = true))
        assertFalse(isWordSpoken("", "苹果", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_ignoresPunctuationInTheTranscript() {
        assertTrue(isWordSpoken("苹果", "苹果。", characterSegmented = true))
    }

    @Test
    fun isWordSpoken_matchesWholeWordsForSpaceSeparatedLanguages() {
        assertTrue(isWordSpoken("táo", "Tôi muốn mua táo.", characterSegmented = false))
        // "táo" inside "táonon" is not the spoken word "táo".
        assertFalse(isWordSpoken("táo", "táonon", characterSegmented = false))
    }

    @Test
    fun isWordSpoken_matchesCaseInsensitively() {
        assertTrue(isWordSpoken("Táo", "tôi muốn táo", characterSegmented = false))
    }

    @Test
    fun isWordSpoken_matchesMultiWordTargetsInOrder() {
        assertTrue(isWordSpoken("xin chào", "à xin chào bạn", characterSegmented = false))
        // The words must appear in order to count as the spoken phrase.
        assertFalse(isWordSpoken("xin chào", "chào xin", characterSegmented = false))
    }

    // --- playCoinWords: which words become coins ---

    @Test
    fun playCoinWords_dropsBlankAndDuplicateWords() {
        val structure = SentenceStructure(
            words = listOf(
                StudyWord(hanzi = "苹果", english = "apple"),
                StudyWord(hanzi = "", english = "blank"),
                StudyWord(hanzi = "苹果", english = "apple again"),
                StudyWord(hanzi = "水", english = "water"),
            ),
        )
        assertEquals(listOf("苹果", "水"), playCoinWords(structure).map { it.hanzi })
    }

    @Test
    fun playCoinWords_keepsWordOrder() {
        val structure = SentenceStructure(
            words = listOf(
                StudyWord(hanzi = "一"),
                StudyWord(hanzi = "二"),
                StudyWord(hanzi = "三"),
            ),
        )
        assertEquals(listOf("一", "二", "三"), playCoinWords(structure).map { it.hanzi })
    }

    // --- coinPositions: predefined random, evenly spaced coin layout ---

    @Test
    fun coinPositions_returnsOnePositionPerCoin() {
        assertEquals(6, coinPositions(count = 6, seed = 42).size)
        assertEquals(0, coinPositions(count = 0, seed = 42).size)
    }

    @Test
    fun coinPositions_isDeterministicForTheSameSeed() {
        assertEquals(coinPositions(6, seed = 7), coinPositions(6, seed = 7))
    }

    @Test
    fun coinPositions_differsForDifferentSeeds() {
        // Different word groups land differently (astronomically unlikely to collide).
        assertFalse(coinPositions(6, seed = 1) == coinPositions(6, seed = 2))
    }

    @Test
    fun coinPositions_staysWithinThePlayfield() {
        (1..12).forEach { count ->
            coinPositions(count, seed = count).forEach { position ->
                assertTrue("x=${position.x}", position.x in 0f..1f)
                assertTrue("y=${position.y}", position.y in 0f..1f)
            }
        }
    }

    @Test
    fun coinPositions_keepsCoinsEvenlySpaced() {
        // The grid-plus-jitter layout guarantees no two coins ever pile up on each other.
        val positions = coinPositions(6, seed = 99)
        positions.forEachIndexed { i, a ->
            positions.drop(i + 1).forEach { b ->
                val dx = abs(a.x - b.x)
                val dy = abs(a.y - b.y)
                assertTrue("coins too close: $a vs $b", dx > 0.15f || dy > 0.1f)
            }
        }
    }

    @Test
    fun coinSeed_isStableForTheSameWordsAndChangesWithThem() {
        val structure = SentenceStructure(
            words = listOf(StudyWord(hanzi = "苹果"), StudyWord(hanzi = "水")),
        )
        val same = SentenceStructure(
            words = listOf(StudyWord(hanzi = "苹果"), StudyWord(hanzi = "水")),
        )
        val different = SentenceStructure(
            words = listOf(StudyWord(hanzi = "香蕉"), StudyWord(hanzi = "水")),
        )
        assertEquals(coinSeed(structure), coinSeed(same))
        assertFalse(coinSeed(structure) == coinSeed(different))
    }
}
