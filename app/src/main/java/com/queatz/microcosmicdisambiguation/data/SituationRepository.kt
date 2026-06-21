package com.queatz.microcosmicdisambiguation.data

import android.content.Context
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.serialization.json.Json

/**
 * On-device persistence for study sessions backed by ObjectBox.
 */
class SituationRepository(context: Context) {

    private val box: Box<SituationEntity> = ObjectBox.store(context).boxFor()

    /** Returns all saved situations, newest first. */
    fun all(): List<SituationEntity> =
        box.query()
            .order(SituationEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .find()

    fun get(id: Long): SituationEntity? = box.get(id)

    /** Removes the stored situation with [id]. Returns true if a row was deleted. */
    fun delete(id: Long): Boolean = box.remove(id)

    /** Persists a generated study guide (for the given [languages]) and returns the stored entity id. */
    fun save(situation: String, guide: StudyGuide, languages: LanguagePair): Long {
        val entity = SituationEntity(
            situation = situation,
            title = guide.title.ifBlank { situation },
            icon = guide.icon,
            guideJson = json.encodeToString(StudyGuide.serializer(), guide),
            createdAt = System.currentTimeMillis(),
            sourceLang = languages.source.name,
            targetLang = languages.target.name,
        )
        return box.put(entity)
    }

    fun guideOf(entity: SituationEntity): StudyGuide =
        json.decodeFromString(StudyGuide.serializer(), entity.guideJson)

    /** The [LanguagePair] this session was generated for, defaulting sensibly for legacy rows. */
    fun languagePairOf(entity: SituationEntity): LanguagePair =
        LanguagePair(
            source = Language.fromName(entity.sourceLang, LanguagePair.Default.source),
            target = Language.fromName(entity.targetLang, LanguagePair.Default.target),
        )

    /** The cached [MissionSet] for [entity], or null if missions have not been generated yet. */
    fun missionsOf(entity: SituationEntity): MissionSet? =
        entity.missionJson?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(MissionSet.serializer(), it) }

    /** Caches [missions] for the session with [id] so they are not regenerated next time. */
    fun saveMissions(id: Long, missions: MissionSet) {
        val entity = box.get(id) ?: return
        entity.missionJson = json.encodeToString(MissionSet.serializer(), missions)
        box.put(entity)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
