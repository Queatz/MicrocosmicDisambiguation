package com.queatz.microcosmicdisambiguation.data

import com.queatz.microcosmicdisambiguation.audio.Wav
import com.queatz.microcosmicdisambiguation.audio.toBase64

private fun nowMillis(): Long = jsNow().toLong()

private fun jsNow(): Double = js("Date.now()")

/**
 * The web [StudyStore]. ObjectBox has no JavaScript/Wasm build, so the browser keeps sessions in
 * memory (a production build could persist these to `localStorage`/IndexedDB behind this exact same
 * contract). The Android app, by contrast, backs the identical interface with ObjectBox.
 */
class InMemoryStudyStore : StudyStore {

    private val sessions = mutableListOf<StudySession>()
    private var nextId = 1L

    override suspend fun all(): List<StudySession> = sessions.sortedByDescending { it.createdAt }

    override suspend fun get(id: Long): StudySession? = sessions.firstOrNull { it.id == id }

    override suspend fun save(
        situation: String,
        guide: StudyGuide,
        languages: LanguagePair,
    ): StudySession {
        val session = StudySession(
            id = nextId++,
            situation = situation,
            title = guide.title.ifBlank { situation },
            icon = guide.icon,
            guide = guide,
            missions = null,
            languages = languages,
            createdAt = nowMillis(),
        )
        sessions += session
        return session
    }

    override suspend fun saveMissions(id: Long, missions: MissionSet) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) sessions[index] = sessions[index].copy(missions = missions)
    }

    override suspend fun delete(id: Long) {
        sessions.removeAll { it.id == id }
    }
}

/**
 * The web [RecordingStore]. The audio is kept as an in-memory `data:` URL (again standing in for the
 * ObjectBox + file storage the Android app uses), so [Recording.audioRef] can be played back by the
 * shared recordings screen through the web clip player.
 */
class InMemoryRecordingStore : RecordingStore {

    private val recordings = mutableListOf<Recording>()
    private var nextId = 1L

    override suspend fun all(): List<Recording> = recordings.sortedByDescending { it.createdAt }

    override suspend fun save(pcm: ByteArray, transcription: String): Recording {
        val wav = Wav.pcmToWav(pcm)
        // 16 kHz mono 16-bit: two bytes per sample.
        val durationMs = pcm.size.toLong() * 1000L / (16_000L * 2L)
        val recording = Recording(
            id = nextId++,
            transcription = transcription,
            createdAt = nowMillis(),
            durationMs = durationMs,
            audioRef = "data:audio/wav;base64,${wav.toBase64()}",
        )
        recordings += recording
        return recording
    }

    override suspend fun delete(id: Long) {
        recordings.removeAll { it.id == id }
    }
}

/**
 * The web [SettingsStore]. Kept in memory for the browser session; a production build could persist
 * to `localStorage` behind this same contract.
 */
class InMemorySettingsStore : SettingsStore {
    override var ttsMode: TtsMode = TtsMode.System
    override var sourceLanguage: Language = LanguagePair.Default.source
    override var targetLanguage: Language = LanguagePair.Default.target
}
