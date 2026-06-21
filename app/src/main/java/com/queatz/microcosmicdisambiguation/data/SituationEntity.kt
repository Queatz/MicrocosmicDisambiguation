package com.queatz.microcosmicdisambiguation.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * A persisted study session for a given IRL situation.
 *
 * The generated [StudyGuide] is stored as a JSON string in [guideJson] so the schema stays simple
 * while still keeping the full structure on device. The follow-up [MissionSet], generated lazily
 * the first time the learner opens the missions for this session, is likewise cached as JSON in
 * [missionJson] (blank until generated).
 */
@Entity
data class SituationEntity(
    @Id var id: Long = 0,
    var situation: String = "",
    var title: String = "",
    var icon: String? = "",
    var guideJson: String = "",
    var createdAt: Long = 0,
    var missionJson: String? = "",
    /** The [Language.name] the learner already knows for this session (its source language). */
    var sourceLang: String = "",
    /** The [Language.name] the learner is studying in this session (its target language). */
    var targetLang: String = "",
)
