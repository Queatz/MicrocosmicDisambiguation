package com.queatz.microcosmicdisambiguation.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A user-trained ("cloned") CosyVoice text-to-speech voice, tied to a specific [language].
 *
 * The learner records or uploads a short audio sample which is registered with DashScope's
 * CosyVoice voice-enrollment API (see [com.queatz.microcosmicdisambiguation.ai.QwenService.enrollClonedVoice]);
 * the returned [voiceId] is what every subsequent AI synthesis call reuses. Only enabled voices are
 * eligible to speak, so the learner can keep several and toggle between them.
 *
 * @param id a stable, app-local identifier (independent of the remote [voiceId])
 * @param name a human-readable label shown in settings
 * @param language the language this voice was cloned for; the AI speaker only uses it for that language
 * @param voiceId the CosyVoice `voice_id` returned by enrollment, passed to synthesis
 * @param enabled whether this voice is eligible to be selected by the AI speaker
 * @param createdAt epoch milliseconds the voice was enrolled
 */
@Serializable
data class ClonedVoice(
    val id: String,
    val name: String,
    val language: Language,
    val voiceId: String,
    val enabled: Boolean = true,
    val createdAt: Long,
)

/**
 * (De)serializes the list of [ClonedVoice]s to and from the single JSON string every [SettingsStore]
 * persists, and resolves which enrolled voice the AI speaker should use for a given language. Kept in
 * one shared place so Android (`SharedPreferences`) and the web (IndexedDB) store the exact same
 * shape and the selection rule is identical everywhere.
 */
object ClonedVoices {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(ClonedVoice.serializer())

    /** Decodes the persisted [clonedVoicesJson], returning an empty list for blank or malformed input. */
    fun decode(clonedVoicesJson: String?): List<ClonedVoice> {
        if (clonedVoicesJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, clonedVoicesJson) }.getOrDefault(emptyList())
    }

    /** Encodes [voices] to the JSON string persisted by every [SettingsStore]. */
    fun encode(voices: List<ClonedVoice>): String = json.encodeToString(serializer, voices)

    /**
     * The `voice_id` of the enabled cloned voice the AI speaker should use for [language], or null
     * when none is enrolled/enabled for it (so the caller falls back to the default voice). The
     * most recently enrolled matching voice wins, so re-cloning a language takes effect immediately.
     */
    fun enabledVoiceIdFor(voices: List<ClonedVoice>, language: Language): String? =
        voices.filter { it.enabled && it.language == language }.maxByOrNull { it.createdAt }?.voiceId
}
