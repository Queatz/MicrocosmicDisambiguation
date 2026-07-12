package com.queatz.microcosmicdisambiguation.data

import kotlin.random.Random

/**
 * Pure logic for the pre-study "Play" round: the coin game the learner plays with a sentence
 * structure's words before studying that sentence. Keeping this logic UI-free makes it unit
 * testable and identical on every platform.
 */

/**
 * A coin's resting position on the playfield, expressed as fractions of the field's width/height
 * (both in `[0, 1]`), so the UI can scale it to any screen size.
 */
data class CoinPosition(val x: Float, val y: Float)

/**
 * The words shown as coins for [structure]: its fill-in words with blanks dropped and duplicates
 * (by [StudyWord.hanzi]) removed, so every coin is a distinct, speakable word.
 */
fun playCoinWords(structure: SentenceStructure): List<StudyWord> =
    structure.words
        .filter { it.hanzi.isNotBlank() }
        .distinctBy { it.hanzi }

/**
 * Whether the learner's [spoken] transcript contains the whole [target] word, judged with the same
 * unit alignment as pronunciation practice: every unit of the target (character for
 * [characterSegmented] languages, word otherwise) must appear, in order, in the transcript. Extra
 * words around the target are fine — the learner only has to say the word somewhere in there.
 */
fun isWordSpoken(target: String, spoken: String, characterSegmented: Boolean): Boolean {
    if (target.isBlank() || spoken.isBlank()) return false
    return comparePronunciation(target, spoken, characterSegmented).score >= 1.0
}

/**
 * Deterministic "random, evenly spaced" resting positions for [count] coins, seeded by [seed] so
 * the same group of words always lands the same way.
 *
 * The playfield is divided into a near-square grid with one cell per coin (plus spares); each coin
 * is assigned a random cell and jittered inside it, which reads as organic and random while
 * guaranteeing the coins stay evenly spread out and never pile up.
 */
fun coinPositions(count: Int, seed: Int): List<CoinPosition> {
    if (count <= 0) return emptyList()
    val random = Random(seed)
    val columns = when {
        count <= 2 -> 1
        count <= 6 -> 2
        else -> 3
    }
    val rows = (count + columns - 1) / columns
    val cellWidth = 1f / columns
    val cellHeight = 1f / rows
    val cells = (0 until rows * columns).shuffled(random).take(count)
    return cells.map { cell ->
        val column = cell % columns
        val row = cell / columns
        // Jitter keeps the layout organic; capped so neighbouring coins can never collide.
        val jitterX = (random.nextFloat() - 0.5f) * cellWidth * 0.4f
        val jitterY = (random.nextFloat() - 0.5f) * cellHeight * 0.4f
        CoinPosition(
            x = ((column + 0.5f) * cellWidth + jitterX).coerceIn(0.1f, 0.9f),
            y = ((row + 0.5f) * cellHeight + jitterY).coerceIn(0.05f, 0.95f),
        )
    }
}

/** A stable per-structure seed for [coinPositions], derived from the words on its coins. */
fun coinSeed(structure: SentenceStructure): Int =
    playCoinWords(structure).joinToString("|") { it.hanzi }.hashCode()
