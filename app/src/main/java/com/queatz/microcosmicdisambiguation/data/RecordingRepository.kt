package com.queatz.microcosmicdisambiguation.data

import android.content.Context
import com.queatz.microcosmicdisambiguation.audio.QwenSpeaker
import com.queatz.microcosmicdisambiguation.audio.Wav
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import java.io.File

/**
 * On-device persistence for raw dictation recordings backed by ObjectBox. The audio itself is
 * stored as WAV files under [recordingsDir]; the database row keeps the file path, the recognized
 * [RecordingEntity.transcription], and metadata.
 */
class RecordingRepository(context: Context) {

    private val appContext = context.applicationContext
    private val box: Box<RecordingEntity> = ObjectBox.store(context).boxFor()

    /** The directory holding the saved WAV files, created on demand. */
    private val recordingsDir: File
        get() = File(appContext.filesDir, "recordings").apply { mkdirs() }

    /** Returns all saved recordings, newest first. */
    fun all(): List<RecordingEntity> =
        box.query()
            .order(RecordingEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .find()

    /**
     * Writes [pcm] (16 kHz mono 16-bit microphone audio) to a WAV file and stores it alongside its
     * [transcription]. Returns the stored entity id.
     */
    fun save(pcm: ByteArray, transcription: String): Long {
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
        return box.put(entity)
    }

    /** Removes the recording with [id], deleting its WAV file too. Returns true if a row was removed. */
    fun delete(id: Long): Boolean {
        val entity = box.get(id) ?: return false
        runCatching { File(entity.filePath).delete() }
        return box.remove(id)
    }
}
