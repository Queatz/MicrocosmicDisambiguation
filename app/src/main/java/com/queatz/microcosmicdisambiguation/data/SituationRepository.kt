package com.queatz.microcosmicdisambiguation.data

import android.content.Context
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.serialization.json.Json

/**
 * On-device persistence for study sessions backed by ObjectBox.
 */
class SituationRepository(context: Context) {

    private val box: Box<SituationEntity> = store(context).boxFor()

    /** Returns all saved situations, newest first. */
    fun all(): List<SituationEntity> =
        box.query()
            .order(SituationEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .find()

    fun get(id: Long): SituationEntity? = box.get(id)

    /** Removes the stored situation with [id]. Returns true if a row was deleted. */
    fun delete(id: Long): Boolean = box.remove(id)

    /** Persists a generated study guide and returns the stored entity id. */
    fun save(situation: String, guide: StudyGuide): Long {
        val entity = SituationEntity(
            situation = situation,
            title = guide.title.ifBlank { situation },
            icon = guide.icon,
            guideJson = json.encodeToString(StudyGuide.serializer(), guide),
            createdAt = System.currentTimeMillis(),
        )
        return box.put(entity)
    }

    fun guideOf(entity: SituationEntity): StudyGuide =
        json.decodeFromString(StudyGuide.serializer(), entity.guideJson)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var boxStore: BoxStore? = null

        /** Lazily builds a single application-wide [BoxStore]. */
        private fun store(context: Context): BoxStore =
            boxStore ?: synchronized(this) {
                boxStore ?: MyObjectBox.builder()
                    .androidContext(context.applicationContext)
                    .build()
                    .also { boxStore = it }
            }
    }
}
