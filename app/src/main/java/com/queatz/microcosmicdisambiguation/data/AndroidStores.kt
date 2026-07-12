package com.queatz.microcosmicdisambiguation.data

import android.content.Context
import com.queatz.microcosmicdisambiguation.audio.QwenSpeaker
import com.queatz.microcosmicdisambiguation.audio.Wav
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Android [StudyStore], backed by ObjectBox. It maps the on-disk [SituationEntity] rows (which
 * keep the guide/missions as JSON) to the platform-neutral [StudySession] the shared UI consumes.
 * ObjectBox is synchronous, so each call hops onto the IO dispatcher to satisfy the suspend contract.
 */
class ObjectBoxStudyStore(context: Context) : StudyStore {

    private val box: Box<SituationEntity> = ObjectBox.store(context).boxFor()

    override suspend fun all(): List<StudySession> = withContext(Dispatchers.IO) {
        box.query()
            .order(SituationEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .find()
            .map { it.toSession() }
    }

    override suspend fun get(id: Long): StudySession? =
        withContext(Dispatchers.IO) { box.get(id)?.toSession() }

    override suspend fun save(
        situation: String,
        guide: StudyGuide,
        languages: LanguagePair,
    ): StudySession = withContext(Dispatchers.IO) {
        val entity = SituationEntity(
            situation = situation,
            title = guide.title.ifBlank { situation },
            icon = guide.icon,
            guideJson = json.encodeToString(StudyGuide.serializer(), guide),
            createdAt = System.currentTimeMillis(),
            sourceLang = languages.source.name,
            targetLang = languages.target.name,
        )
        val id = box.put(entity)
        box.get(id).toSession()
    }

    override suspend fun saveMissions(id: Long, missions: MissionSet) {
        withContext(Dispatchers.IO) {
            val entity = box.get(id) ?: return@withContext
            entity.missionJson = json.encodeToString(MissionSet.serializer(), missions)
            box.put(entity)
        }
    }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { box.remove(id) }
    }

    private fun SituationEntity.toSession(): StudySession = StudySession(
        id = id,
        situation = situation,
        title = title.ifBlank { situation },
        icon = icon.orEmpty(),
        guide = json.decodeFromString(StudyGuide.serializer(), guideJson),
        missions = missionJson?.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString(MissionSet.serializer(), it) },
        languages = LanguagePair(
            source = Language.fromName(sourceLang, LanguagePair.Default.source),
            target = Language.fromName(targetLang, LanguagePair.Default.target),
        ),
        createdAt = createdAt,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The Android [RecordingStore], backed by ObjectBox. The audio itself is written as WAV files under
 * the app's files directory; the database row keeps the file path, transcription and metadata, which
 * are mapped to the platform-neutral [Recording].
 */
class ObjectBoxRecordingStore(context: Context) : RecordingStore {

    private val appContext = context.applicationContext
    private val box: Box<RecordingEntity> = ObjectBox.store(context).boxFor()

    private val recordingsDir: File
        get() = File(appContext.filesDir, "recordings").apply { mkdirs() }

    override suspend fun all(): List<Recording> = withContext(Dispatchers.IO) {
        box.query()
            .order(RecordingEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .find()
            .map { it.toRecording() }
    }

    override suspend fun save(pcm: ByteArray, transcription: String): Recording =
        withContext(Dispatchers.IO) {
            val createdAt = System.currentTimeMillis()
            val wav = Wav.pcmToWav(pcm)
            val file = File(recordingsDir, "recording_$createdAt.wav")
            file.writeBytes(wav)
            val entity = RecordingEntity(
                filePath = file.absolutePath,
                transcription = transcription,
                createdAt = createdAt,
                durationMs = QwenSpeaker.wavDurationMs(wav),
            )
            val id = box.put(entity)
            box.get(id).toRecording()
        }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            val entity = box.get(id) ?: return@withContext
            runCatching { File(entity.filePath).delete() }
            box.remove(id)
        }
    }

    private fun RecordingEntity.toRecording(): Recording =
        Recording(
            id = id,
            transcription = transcription,
            createdAt = createdAt,
            durationMs = durationMs,
            audioRef = filePath,
        )
}
