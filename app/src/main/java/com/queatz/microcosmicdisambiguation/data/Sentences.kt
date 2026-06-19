package com.queatz.microcosmicdisambiguation.data

/** The marker used in a sentence [SentenceStructure.template] for a draggable blank. */
const val BLANK = "___"

/**
 * Assembles a sentence from a [template] by filling each "___" blank with the corresponding entry
 * in [fills]. A `null` (or missing) fill leaves the blank marker in place.
 *
 * Example: assembleSentence("请给___做___。", listOf("我", "你")) == "请给我做你。"
 */
fun assembleSentence(template: String, fills: List<String?>): String {
    val segments = template.split(BLANK)
    val blankCount = (segments.size - 1).coerceAtLeast(0)
    return buildString {
        segments.forEachIndexed { index, segment ->
            append(segment)
            if (index < blankCount) {
                append(fills.getOrNull(index) ?: BLANK)
            }
        }
    }
}

/** A single word of a sentence, paired with its `[start, end)` character range into the sentence. */
data class SentenceToken(val text: String, val range: IntRange)

/** True when [this] (the currently-spoken range) overlaps [other]. Null/empty ranges never match. */
private infix fun IntRange?.overlaps(other: IntRange): Boolean {
    if (this == null || isEmpty() || other.isEmpty()) return false
    return first <= other.last && other.first <= last
}

/**
 * Maps the currently-spoken [speakingRange] (a character range into [assembledChinese]) to the
 * exact `[start, end)` character range of [english] it corresponds to, using the model-provided
 * [alignment] chunk pairs.
 *
 * Returns `null` when there is no usable alignment: either the list is empty, or its `zh`/`en`
 * spans do not concatenate back to the assembled sentences (which happens once the learner swaps in
 * different words), in which case the caller falls back to proportional highlighting. May return an
 * empty range when the spoken Mandarin word has no English counterpart (e.g. a particle), so nothing
 * is highlighted.
 *
 * Chunks with an empty [Alignment.zh] are English-only filler (e.g. "is"/"a"/"the") that have no
 * Mandarin to speak, so they are never selected as the active chunk and therefore never highlight.
 */
fun alignedEnglishRange(
    english: String,
    assembledChinese: String,
    alignment: List<Alignment>,
    speakingRange: IntRange,
): IntRange? {
    if (alignment.isEmpty()) return null
    if (alignment.joinToString("") { it.zh } != assembledChinese) return null
    if (alignment.joinToString("") { it.en } != english) return null

    var zhOffset = 0
    var active = -1
    alignment.forEachIndexed { index, chunk ->
        val length = chunk.zh.length
        if (length > 0 && (zhOffset until zhOffset + length).overlaps(speakingRange)) {
            active = index
        }
        zhOffset += length
    }
    if (active < 0) return null

    var enOffset = 0
    alignment.forEachIndexed { index, chunk ->
        val length = chunk.en.length
        if (index == active) return enOffset until enOffset + length
        enOffset += length
    }
    return null
}

/**
 * Greedily splits [sentence] into word tokens, preferring the longest match from [vocabulary] at
 * each position. Characters that match no vocabulary entry become single-character tokens, so the
 * whole sentence is always covered contiguously.
 *
 * This lets the app step through and highlight a sentence one real word at a time rather than
 * guessing word boundaries from raw character offsets.
 */
fun tokenize(sentence: String, vocabulary: List<String>): List<SentenceToken> {
    if (sentence.isEmpty()) return emptyList()
    val sorted = vocabulary.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    val tokens = ArrayList<SentenceToken>()
    var i = 0
    while (i < sentence.length) {
        val match = sorted.firstOrNull { sentence.startsWith(it, i) }
        if (match != null) {
            tokens.add(SentenceToken(match, i until i + match.length))
            i += match.length
        } else {
            tokens.add(SentenceToken(sentence[i].toString(), i until i + 1))
            i++
        }
    }
    return tokens
}
