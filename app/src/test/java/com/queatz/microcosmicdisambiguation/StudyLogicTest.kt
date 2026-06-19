package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.data.Alignment
import com.queatz.microcosmicdisambiguation.data.SentenceStructure
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import com.queatz.microcosmicdisambiguation.data.StudyWord
import com.queatz.microcosmicdisambiguation.data.WordBreakdown
import com.queatz.microcosmicdisambiguation.data.alignedEnglishRange
import com.queatz.microcosmicdisambiguation.data.assembleSentence
import com.queatz.microcosmicdisambiguation.data.tokenize
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyLogicTest {

    @Test
    fun assembleSentence_fillsBlanksInOrder() {
        val result = assembleSentence("请给___做___。", listOf("我", "你"))
        assertEquals("请给我做你。", result)
    }

    @Test
    fun assembleSentence_keepsBlankMarkerWhenEmpty() {
        val result = assembleSentence("请给___做___。", listOf("我", null))
        assertEquals("请给我做___。", result)
    }

    @Test
    fun assembleSentence_handlesTemplateWithoutBlanks() {
        val result = assembleSentence("你好。", emptyList())
        assertEquals("你好。", result)
    }

    @Test
    fun assembleSentence_fillsEnglishBlanksWithWordMeanings() {
        val result = assembleSentence("I want to buy ___.", listOf("apple"))
        assertEquals("I want to buy apple.", result)
    }

    @Test
    fun studyGuide_jsonRoundTrips() {
        val guide = StudyGuide(
            title = "At the market",
            structures = listOf(
                SentenceStructure(
                    template = "我想买___。",
                    english = "I want to buy ___.",
                    words = listOf(
                        StudyWord(hanzi = "苹果", pinyin = "píngguǒ", english = "apple", emoji = "🍎"),
                        StudyWord(hanzi = "水", pinyin = "shuǐ", english = "water", emoji = "💧"),
                    ),
                    breakdown = listOf(
                        WordBreakdown(
                            hanzi = "我",
                            pinyin = "wǒ",
                            english = "I",
                            emoji = "🙋",
                            explanation = "The speaker — the person who wants something.",
                        ),
                        WordBreakdown(
                            hanzi = "想",
                            pinyin = "xiǎng",
                            english = "want",
                            emoji = "💭",
                            explanation = "Expresses a desire or wish to do something.",
                        ),
                    ),
                ),
            ),
        )

        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(StudyGuide.serializer(), guide)
        val decoded = json.decodeFromString(StudyGuide.serializer(), encoded)

        assertEquals(guide, decoded)
        assertEquals("🍎", decoded.structures.first().words.first().emoji)
        assertEquals("🙋", decoded.structures.first().breakdown.first().emoji)
        assertEquals("want", decoded.structures.first().breakdown[1].english)
    }

    @Test
    fun studyGuide_parsesBreakdownFromModelJson() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "words": [
                    { "hanzi": "苹果", "pinyin": "píngguǒ", "english": "apple", "emoji": "🍎" }
                  ],
                  "breakdown": [
                    { "hanzi": "我", "pinyin": "wǒ", "english": "I", "emoji": "🙋", "explanation": "The speaker." },
                    { "hanzi": "想", "pinyin": "xiǎng", "english": "want", "emoji": "💭", "explanation": "Expresses a wish." },
                    { "hanzi": "买", "pinyin": "mǎi", "english": "buy", "emoji": "🛒", "explanation": "The action of purchasing." }
                  ]
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val breakdown = guide.structures.first().breakdown

        assertEquals(3, breakdown.size)
        assertEquals("买", breakdown[2].hanzi)
        assertEquals("The action of purchasing.", breakdown[2].explanation)
    }

    @Test
    fun tokenize_prefersLongestVocabularyMatchAndCoversEverything() {
        val tokens = tokenize("我想买苹果。", listOf("我", "想", "买", "苹果"))

        assertEquals(listOf("我", "想", "买", "苹果", "。"), tokens.map { it.text })
        // tokens cover the whole sentence contiguously
        assertEquals(0, tokens.first().range.first)
        assertEquals("我想买苹果。".length, tokens.last().range.last + 1)
    }

    @Test
    fun tokenize_rangesPointAtTheCorrectCharacters() {
        val sentence = "我想买苹果。"
        val tokens = tokenize(sentence, listOf("我", "想", "买", "苹果"))

        tokens.forEach { token ->
            assertEquals(token.text, sentence.substring(token.range.first, token.range.last + 1))
        }
    }

    @Test
    fun tokenize_fallsBackToSingleCharactersForUnknownWords() {
        val tokens = tokenize("你好", emptyList())
        assertEquals(listOf("你", "好"), tokens.map { it.text })
    }

    @Test
    fun studyGuide_parsesWordsWhenModelEmitsAnObjectMap() {
        // The model sometimes emits "words" as an object keyed by index instead of an array.
        // This previously crashed with "unexpected JSON token at $.structures[0].words".
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "words": {
                    "1": { "hanzi": "苹果", "pinyin": "píngguǒ", "english": "apple", "emoji": "🍎" },
                    "2": { "hanzi": "水", "pinyin": "shuǐ", "english": "water", "emoji": "💧" }
                  }
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val words = guide.structures.first().words

        assertEquals(2, words.size)
        assertEquals("苹果", words[0].hanzi)
        assertEquals("water", words[1].english)
    }

    @Test
    fun studyGuide_parsesWordsWhenModelEmitsASingleObject() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "words": { "hanzi": "苹果", "pinyin": "píngguǒ", "english": "apple", "emoji": "🍎" }
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val words = guide.structures.first().words

        assertEquals(1, words.size)
        assertEquals("苹果", words.first().hanzi)
    }

    @Test
    fun studyGuide_parsesWordsWhenModelEmitsBareStrings() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "words": ["苹果", "水"]
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val words = guide.structures.first().words

        assertEquals(listOf("苹果", "水"), words.map { it.hanzi })
    }

    @Test
    fun studyGuide_parsesAlignmentFromModelJson() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "alignment": [
                    { "zh": "我", "en": "I", "punctuation": false },
                    { "zh": "想", "en": " want", "punctuation": false },
                    { "zh": "买", "en": " to buy", "punctuation": false },
                    { "zh": "苹果", "en": " apple", "punctuation": false },
                    { "zh": "。", "en": ".", "punctuation": true }
                  ]
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val alignment = guide.structures.first().alignment

        assertEquals(5, alignment.size)
        assertEquals("苹果", alignment[3].zh)
        assertEquals(" apple", alignment[3].en)
        assertTrue(alignment.last().punctuation)
        assertFalse(alignment.first().punctuation)
        // Concatenating the spans reproduces both assembled sentences exactly.
        assertEquals("我想买苹果。", alignment.joinToString("") { it.zh })
        assertEquals("I want to buy apple.", alignment.joinToString("") { it.en })
    }

    @Test
    fun studyGuide_parsesAlignmentWhenModelEmitsObjectMap() {
        // Tolerate the model emitting "alignment" as an index-keyed object instead of an array.
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我好。",
                  "english": "I am good.",
                  "alignment": {
                    "1": { "zh": "我", "en": "I am" },
                    "2": { "zh": "好", "en": " good" },
                    "3": { "zh": "。", "en": ".", "punctuation": true }
                  }
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val alignment = guide.structures.first().alignment

        assertEquals(3, alignment.size)
        assertEquals("我好。", alignment.joinToString("") { it.zh })
        assertEquals("I am good.", alignment.joinToString("") { it.en })
    }

    @Test
    fun studyGuide_parsesPunctuationFlagInBreakdown() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我好___",
                  "english": "I am ___",
                  "breakdown": [
                    { "hanzi": "我", "pinyin": "wǒ", "english": "I", "emoji": "🙋", "explanation": "speaker" },
                    { "hanzi": "。", "pinyin": "", "english": "", "emoji": "", "explanation": "", "punctuation": true }
                  ]
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val breakdown = guide.structures.first().breakdown

        assertEquals(2, breakdown.size)
        assertFalse(breakdown[0].punctuation)
        assertTrue(breakdown[1].punctuation)
        // Only the real word survives once punctuation entries are filtered out, mirroring the UI.
        assertEquals(listOf("我"), breakdown.filterNot { it.punctuation }.map { it.hanzi })
    }

    @Test
    fun alignment_jsonRoundTrips() {
        val structure = SentenceStructure(
            template = "我好。",
            english = "I am good.",
            alignment = listOf(
                Alignment(zh = "我", en = "I am"),
                Alignment(zh = "好", en = " good"),
                Alignment(zh = "。", en = ".", punctuation = true),
            ),
        )
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            SentenceStructure.serializer(),
            json.encodeToString(SentenceStructure.serializer(), structure),
        )
        assertEquals(structure, decoded)
    }

    @Test
    fun alignedEnglishRange_mapsReallyToReallyNotIs() {
        // Regression for "This matcha is really delicious.": the Mandarin "真" means "really", but
        // English has the extra copula "is" that does not exist in Mandarin. "is" lives in its own
        // empty-"zh" chunk, so speaking "真" must highlight "really" — never the unrelated "is".
        val chinese = "这抹茶真好吃。"
        val english = "This matcha is really delicious."
        val alignment = listOf(
            Alignment(zh = "这", en = "This"),
            Alignment(zh = "抹茶", en = " matcha"),
            Alignment(zh = "", en = " is"),
            Alignment(zh = "真", en = " really"),
            Alignment(zh = "好吃", en = " delicious"),
            Alignment(zh = "。", en = ".", punctuation = true),
        )
        // Sanity: the chunks reproduce both assembled sentences exactly.
        assertEquals(chinese, alignment.joinToString("") { it.zh })
        assertEquals(english, alignment.joinToString("") { it.en })

        // "真" is the 4th character (index 3) of the Mandarin sentence.
        val range = alignedEnglishRange(english, chinese, alignment, 3..3)
        assertEquals(" really", range?.let { english.substring(it.first, it.last + 1) })
    }

    @Test
    fun alignedEnglishRange_neverHighlightsEnglishOnlyFiller() {
        // The English-only "is" chunk has an empty "zh", so no spoken Mandarin word can select it.
        val chinese = "这抹茶真好吃。"
        val english = "This matcha is really delicious."
        val alignment = listOf(
            Alignment(zh = "这", en = "This"),
            Alignment(zh = "抹茶", en = " matcha"),
            Alignment(zh = "", en = " is"),
            Alignment(zh = "真", en = " really"),
            Alignment(zh = "好吃", en = " delicious"),
            Alignment(zh = "。", en = ".", punctuation = true),
        )
        // Whatever Mandarin character is spoken, the highlighted English span is never the " is".
        chinese.indices.forEach { i ->
            val range = alignedEnglishRange(english, chinese, alignment, i..i)
            val highlighted = range?.let { english.substring(it.first, it.last + 1) }
            assertFalse("char $i highlighted '$highlighted'", highlighted == " is")
        }
    }

    @Test
    fun alignedEnglishRange_fallsBackWhenSpansDoNotMatchSentence() {
        // Once the learner swaps in different words the precomputed spans no longer reproduce the
        // assembled sentence, so we return null and the caller falls back to a proportional guess.
        val alignment = listOf(
            Alignment(zh = "这", en = "This"),
            Alignment(zh = "真", en = " really"),
        )
        assertEquals(null, alignedEnglishRange("Totally different.", "完全不同。", alignment, 0..0))
    }

    @Test
    fun studyGuide_parsesTokensFromModelJson() {
        val content = """
            {
              "title": "At the market",
              "structures": [
                {
                  "template": "我想买___。",
                  "english": "I want to buy ___.",
                  "words": [
                    { "hanzi": "苹果", "pinyin": "píngguǒ", "english": "apple", "emoji": "🍎" }
                  ],
                  "breakdown": [],
                  "tokens": ["我", "想", "买", "苹果", "。"]
                }
              ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val guide = json.decodeFromString(StudyGuide.serializer(), content)
        val tokens = guide.structures.first().tokens

        assertEquals(listOf("我", "想", "买", "苹果", "。"), tokens)
        assertEquals("我想买苹果。", tokens.joinToString(""))
    }
}
