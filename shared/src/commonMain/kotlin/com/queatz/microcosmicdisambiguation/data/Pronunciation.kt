package com.queatz.microcosmicdisambiguation.data

/**
 * A single unit (a word for whitespace-delimited languages, or a character for character-segmented
 * languages such as Chinese or Japanese) of a pronunciation target, paired with whether the
 * learner's spoken transcription contained it.
 *
 * @param text the unit as it should be shown to the learner
 * @param correct true when this unit was matched in the spoken transcription (highlight green),
 *   false when it was mispronounced or missing (highlight red)
 */
data class PronunciationToken(val text: String, val correct: Boolean)

/**
 * The result of comparing a learner's spoken transcription against a target sentence/word.
 *
 * @param tokens the target broken into units, each flagged correct or not, so the UI can colour
 *   code the feedback
 * @param score the overall accuracy in `[0, 1]`: the fraction of target units that were matched
 */
data class PronunciationFeedback(
    val tokens: List<PronunciationToken>,
    val score: Double,
)

/** Punctuation (CJK and ASCII) stripped before comparing pronunciation, so it never counts. */
private val PRONUNCIATION_PUNCTUATION =
    "，。！？、；：“”‘’（）《》〈〉【】「」—…·,.!?;:\"'()-".toSet()

/**
 * Splits [text] into the comparable units used for pronunciation feedback. Punctuation is always
 * dropped. For [characterSegmented] languages (written without spaces between words) every
 * remaining character becomes its own unit; otherwise the text is split on whitespace into words.
 */
fun pronunciationUnits(text: String, characterSegmented: Boolean): List<String> =
    if (characterSegmented) {
        text
            .filterNot { it.isWhitespace() || it in PRONUNCIATION_PUNCTUATION }
            .map { it.toString() }
    } else {
        text
            .split(Regex("\\s+"))
            .map { word -> word.filterNot { it in PRONUNCIATION_PUNCTUATION } }
            .filter { it.isNotEmpty() }
    }

/** The case-insensitive key two units are matched by. */
private fun matchKey(unit: String): String = unit.lowercase()

/**
 * Returns the indices into [target] that are part of a longest common subsequence shared with
 * [spoken]. These are the target units the learner pronounced correctly (in order); everything else
 * was mispronounced, missing, or extra noise that doesn't line up.
 */
private fun matchedTargetIndices(target: List<String>, spoken: List<String>): Set<Int> {
    val n = target.size
    val m = spoken.size
    if (n == 0 || m == 0) return emptySet()

    // dp[i][j] = length of the longest common subsequence of target[i..] and spoken[j..]
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (target[i] == spoken[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val matched = HashSet<Int>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            target[i] == spoken[j] -> {
                matched.add(i)
                i++
                j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    return matched
}

/**
 * Compares the learner's [spoken] transcription against the [target] sentence or word, producing
 * per-unit feedback and an overall accuracy [PronunciationFeedback.score].
 *
 * Both strings are reduced to comparable units via [pronunciationUnits] (words, or characters for
 * [characterSegmented] languages), matched case-insensitively, then aligned with a longest common
 * subsequence so that mispronounced, missing, and extra units are all handled. Each target unit is
 * flagged correct when it appears (in order) in the spoken transcription.
 */
fun comparePronunciation(
    target: String,
    spoken: String,
    characterSegmented: Boolean,
): PronunciationFeedback {
    val targetUnits = pronunciationUnits(target, characterSegmented)
    val spokenUnits = pronunciationUnits(spoken, characterSegmented)

    val matched = matchedTargetIndices(
        targetUnits.map(::matchKey),
        spokenUnits.map(::matchKey),
    )

    val tokens = targetUnits.mapIndexed { index, unit ->
        PronunciationToken(text = unit, correct = index in matched)
    }
    val score = if (targetUnits.isEmpty()) 0.0 else matched.size.toDouble() / targetUnits.size
    return PronunciationFeedback(tokens = tokens, score = score)
}
