package com.queatz.microcosmicdisambiguation.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray

/**
 * A single vocabulary item for a study session.
 *
 * @param hanzi the Mandarin Chinese word (simplified characters)
 * @param pinyin the romanized pronunciation
 * @param english the English meaning
 * @param emoji an "AI generated icon" represented as an emoji that depicts the word
 */
@Serializable
data class StudyWord(
    val hanzi: String = "",
    val pinyin: String = "",
    val english: String = "",
    val emoji: String = "",
)

/**
 * A single word in the detailed, word-by-word breakdown of a sentence.
 *
 * Unlike [StudyWord] (which is a draggable, swappable vocabulary item), a breakdown entry walks
 * through the sentence exactly as it reads, defining and explaining each word in context.
 *
 * @param hanzi the Mandarin Chinese word (simplified characters)
 * @param pinyin the romanized pronunciation
 * @param english the English meaning of this word
 * @param emoji an emoji that depicts the word
 * @param explanation a short, friendly note explaining what this word does in the sentence
 * @param punctuation true when this entry is a punctuation mark rather than a real word, so the UI
 *   can skip it in the breakdown and in the word-by-word player
 */
@Serializable
data class WordBreakdown(
    val hanzi: String = "",
    val pinyin: String = "",
    val english: String = "",
    val emoji: String = "",
    val explanation: String = "",
    val punctuation: Boolean = false,
)

/**
 * A single aligned chunk pairing a span of the Mandarin sentence with the exact span of the natural
 * English sentence it corresponds to. Concatenating all [zh] (and likewise all [en]) in order
 * reproduces the assembled Mandarin (and English) sentence. This lets the UI highlight the precise
 * English words that match the Mandarin currently being spoken, instead of guessing proportionally.
 *
 * @param zh the Mandarin span (may be empty for English-only filler such as articles)
 * @param en the English span (may be empty for Mandarin-only words such as particles)
 * @param punctuation true when this chunk is a punctuation mark rather than a real word
 */
@Serializable
data class Alignment(
    val zh: String = "",
    val en: String = "",
    val punctuation: Boolean = false,
)

/**
 * A sentence structure with blanks (represented by "___") that words can be dragged into.
 *
 * @param template the Mandarin sentence template, using "___" to mark each blank
 * @param english the English meaning of the template
 * @param words the words (4-8) that can be dragged in and out of the blanks
 * @param breakdown a detailed, word-by-word breakdown of the assembled sentence, defining and
 *   explaining each word in the order it is spoken
 * @param tokens the COMPLETE assembled sentence split into individual words, in spoken order.
 *   These back the "word by word" playback so each word can be highlighted accurately.
 * @param alignment aligned Mandarin/English chunk pairs (in spoken order) that map each part of the
 *   assembled Mandarin sentence to the exact part of the natural English sentence, so the English
 *   words can be highlighted precisely as the Mandarin is spoken.
 */
@Serializable
data class SentenceStructure(
    val template: String = "",
    val english: String = "",
    @Serializable(with = StudyWordListSerializer::class)
    val words: List<StudyWord> = emptyList(),
    @Serializable(with = WordBreakdownListSerializer::class)
    val breakdown: List<WordBreakdown> = emptyList(),
    @Serializable(with = TokenListSerializer::class)
    val tokens: List<String> = emptyList(),
    @Serializable(with = AlignmentListSerializer::class)
    val alignment: List<Alignment> = emptyList(),
)

/**
 * Coerces whatever shape the model emitted for a list-of-objects field into a clean [JsonArray] of
 * objects, so generation never crashes on a stray shape (the cause of "unexpected JSON token at
 * $.structures[0].words").
 *
 * Tolerated shapes:
 * - a proper array of objects (kept as-is);
 * - an array containing bare strings (each becomes `{ "hanzi": <string> }`);
 * - a single object that is itself one item (wrapped into a one-element array);
 * - an object used as a map of items (its values become the array);
 * - anything else (becomes an empty array).
 */
private fun normalizeObjectList(element: JsonElement): JsonArray = when (element) {
    is JsonArray -> buildJsonArray { element.forEach { add(normalizeItem(it)) } }
    is JsonObject -> if (element.containsKey("hanzi")) {
        // a single item object, not a map of items
        buildJsonArray { add(element) }
    } else {
        buildJsonArray { element.values.forEach { add(normalizeItem(it)) } }
    }

    else -> buildJsonArray { }
}

/**
 * Coerces whatever shape the model emitted for the alignment field into a clean [JsonArray] of
 * objects. Like [normalizeObjectList] but keyed on the alignment's `zh` field, and dropping bare
 * non-object entries since an alignment chunk needs both a `zh` and `en` span.
 */
private fun normalizeAlignmentList(element: JsonElement): JsonArray = when (element) {
    is JsonArray -> buildJsonArray { element.forEach { if (it is JsonObject) add(it) } }
    is JsonObject -> if (element.containsKey("zh") || element.containsKey("en")) {
        // a single alignment object, not a map of items
        buildJsonArray { add(element) }
    } else {
        buildJsonArray { element.values.forEach { if (it is JsonObject) add(it) } }
    }

    else -> buildJsonArray { }
}

/** Normalizes a single list element to an object, turning a bare string into `{ "hanzi": ... }`. */
private fun normalizeItem(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> element
    is JsonPrimitive -> JsonObject(mapOf("hanzi" to element))
    else -> JsonObject(emptyMap())
}

/** Tolerant serializer for [SentenceStructure.words]; see [normalizeObjectList]. */
object StudyWordListSerializer :
    JsonTransformingSerializer<List<StudyWord>>(ListSerializer(StudyWord.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeObjectList(element)
}

/** Tolerant serializer for [SentenceStructure.breakdown]; see [normalizeObjectList]. */
object WordBreakdownListSerializer :
    JsonTransformingSerializer<List<WordBreakdown>>(ListSerializer(WordBreakdown.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeObjectList(element)
}

/** Tolerant serializer for [SentenceStructure.alignment]; see [normalizeAlignmentList]. */
object AlignmentListSerializer :
    JsonTransformingSerializer<List<Alignment>>(ListSerializer(Alignment.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeAlignmentList(element)
}

/**
 * Tolerant serializer for [SentenceStructure.tokens]. Accepts an array of strings, an array of
 * objects (taking each object's `text`/`hanzi`), or a single string, never crashing on shape.
 */
object TokenListSerializer :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> buildJsonArray { element.forEach { add(normalizeToken(it)) } }
        is JsonPrimitive -> buildJsonArray { add(element) }
        else -> buildJsonArray { }
    }

    private fun normalizeToken(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> element
        is JsonObject -> element["text"] ?: element["hanzi"] ?: JsonPrimitive("")
        else -> JsonPrimitive("")
    }
}

/**
 * A complete study guide generated for an IRL situation.
 *
 * @param title a short title for the situation
 * @param icon a single emoji that represents the whole scenario
 * @param structures the sentence structures (3) that make up the study session
 */
@Serializable
data class StudyGuide(
    val title: String = "",
    val icon: String = "",
    val structures: List<SentenceStructure> = emptyList(),
)
