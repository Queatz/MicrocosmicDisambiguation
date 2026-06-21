package com.queatz.microcosmicdisambiguation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray

/**
 * One thing the learner can choose to say at a turn of a mission, using what they studied.
 *
 * @param hanzi the Mandarin Chinese line (simplified characters)
 * @param pinyin the romanized pronunciation
 * @param english the English meaning of the line
 * @param correct true when saying this successfully advances the conversation
 * @param reply how the other person reacts to this choice (in English), so the learner sees the
 *   consequence of what they said — encouraging when [correct], gently corrective otherwise
 */
@Serializable
data class MissionChoice(
    @SerialName("text") val hanzi: String = "",
    @SerialName("romanization") val pinyin: String = "",
    @SerialName("translation") val english: String = "",
    val correct: Boolean = false,
    val reply: String = "",
)

/**
 * A single back-and-forth step of a mission: the other person says [speaker], and the learner must
 * pick the right thing to say back from [choices].
 *
 * @param speaker the other person's line in Mandarin (may be empty for the opening turn where the
 *   learner speaks first)
 * @param speakerPinyin the romanized pronunciation of [speaker]
 * @param speakerEnglish the English meaning of [speaker]
 * @param emoji a single emoji depicting the other person or what they are doing
 * @param prompt a short English nudge telling the learner what they are trying to do this turn
 * @param choices the options the learner chooses between, exactly one of which is [MissionChoice.correct]
 */
@Serializable
data class MissionTurn(
    val speaker: String = "",
    @SerialName("speakerRomanization") val speakerPinyin: String = "",
    @SerialName("speakerTranslation") val speakerEnglish: String = "",
    val emoji: String = "",
    val prompt: String = "",
    @Serializable(with = MissionChoiceListSerializer::class)
    val choices: List<MissionChoice> = emptyList(),
)

/**
 * A self-contained, real-life situation the learner role-plays through, navigating it with the
 * Mandarin they studied.
 *
 * @param title a short title for the scene
 * @param icon a single emoji representing the scene
 * @param setting a one-line English description of where the learner is and what is happening
 * @param role who the other person in the conversation is (e.g. "a street food vendor")
 * @param turns the conversation turns (1-3) the learner navigates in order
 * @param success a short congratulatory English line shown once the learner navigates every turn
 */
@Serializable
data class Mission(
    val title: String = "",
    val icon: String = "",
    val setting: String = "",
    val role: String = "",
    @Serializable(with = MissionTurnListSerializer::class)
    val turns: List<MissionTurn> = emptyList(),
    val success: String = "",
)

/**
 * A set of real-life missions generated for a study session, simulating situations the learner can
 * successfully navigate using what they learned on the study screen.
 *
 * @param missions the missions (3) that make up the simulation
 */
@Serializable
data class MissionSet(
    @Serializable(with = MissionListSerializer::class)
    val missions: List<Mission> = emptyList(),
)

/**
 * Coerces whatever shape the model emitted for a list-of-objects mission field into a clean
 * [JsonArray] of objects, mirroring [normalizeObjectList] for study guides so generation never
 * crashes on a stray shape.
 *
 * Tolerated shapes:
 * - a proper array of objects (kept as-is, dropping any bare non-object entries);
 * - an object used as a map of items (its object values become the array);
 * - anything else (becomes an empty array).
 */
private fun normalizeMissionObjectList(element: JsonElement): JsonArray = when (element) {
    is JsonArray -> buildJsonArray { element.forEach { if (it is JsonObject) add(it) } }
    is JsonObject -> buildJsonArray { element.values.forEach { if (it is JsonObject) add(it) } }
    else -> buildJsonArray { }
}

/** Tolerant serializer for [MissionTurn.choices]; see [normalizeMissionObjectList]. */
object MissionChoiceListSerializer :
    JsonTransformingSerializer<List<MissionChoice>>(ListSerializer(MissionChoice.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeMissionObjectList(element)
}

/** Tolerant serializer for [Mission.turns]; see [normalizeMissionObjectList]. */
object MissionTurnListSerializer :
    JsonTransformingSerializer<List<MissionTurn>>(ListSerializer(MissionTurn.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeMissionObjectList(element)
}

/** Tolerant serializer for [MissionSet.missions]; see [normalizeMissionObjectList]. */
object MissionListSerializer :
    JsonTransformingSerializer<List<Mission>>(ListSerializer(Mission.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        normalizeMissionObjectList(element)
}
