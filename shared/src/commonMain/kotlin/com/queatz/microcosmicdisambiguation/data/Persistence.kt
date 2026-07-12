package com.queatz.microcosmicdisambiguation.data

/**
 * A fully-materialized study session, independent of any storage engine. The Android app persists
 * these with ObjectBox; the web keeps them in memory. Both hand the UI the exact same type, so the
 * screens are identical on every platform.
 *
 * @param id a stable identifier assigned by the [StudyStore] (0 means "not yet saved")
 * @param situation the free-text IRL situation the learner described
 * @param title the session title shown in lists (falls back to [situation] when blank)
 * @param icon the emoji representing the session
 * @param guide the generated study guide
 * @param missions the follow-up missions, generated lazily; null until first generated
 * @param languages the language pair this session was generated for
 * @param createdAt epoch milliseconds the session was created
 */
data class StudySession(
    val id: Long,
    val situation: String,
    val title: String,
    val icon: String,
    val guide: StudyGuide,
    val missions: MissionSet?,
    val languages: LanguagePair,
    val createdAt: Long,
)

/**
 * A captured dictation recording, independent of any storage engine.
 *
 * @param id a stable identifier assigned by the [RecordingStore]
 * @param transcription the recognized transcript
 * @param createdAt epoch milliseconds the recording was captured
 * @param durationMs the audio duration in milliseconds
 * @param audioRef a platform playback handle (a file path on Android, a blob URL on the web)
 */
data class Recording(
    val id: Long,
    val transcription: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioRef: String,
)

/**
 * The persistence contract for study sessions, shared by every platform.
 *
 * ObjectBox — which the Android app uses — has no JavaScript/Wasm build, so it cannot back the web
 * target. Rather than leak that engine into shared code, persistence is expressed as this
 * interface: Android implements it with ObjectBox, and the web implements it in memory, keeping the
 * shared and UI code completely free of any storage engine.
 */
interface StudyStore {
    /** All stored sessions, newest first. */
    suspend fun all(): List<StudySession>

    /** The session with the given [id], or null if none. */
    suspend fun get(id: Long): StudySession?

    /** Persists a generated [guide] for [situation]/[languages], returning the stored session. */
    suspend fun save(situation: String, guide: StudyGuide, languages: LanguagePair): StudySession

    /** Caches [missions] for the session with [id] so they are not regenerated next time. */
    suspend fun saveMissions(id: Long, missions: MissionSet)

    /** Removes the session with the given [id], if present. */
    suspend fun delete(id: Long)
}

/**
 * The persistence contract for dictation recordings. Android stores the WAV bytes on disk (behind a
 * database row); the web keeps them as in-memory blob URLs.
 */
interface RecordingStore {
    /** All stored recordings, newest first. */
    suspend fun all(): List<Recording>

    /** Persists 16 kHz mono 16-bit [pcm] audio with its [transcription], returning the stored row. */
    suspend fun save(pcm: ByteArray, transcription: String): Recording

    /** Removes the recording with the given [id] (and its audio), if present. */
    suspend fun delete(id: Long)
}

/**
 * Lightweight user-settings persistence. Android backs this with `SharedPreferences`; the web with
 * `localStorage` (or memory). The [languagePair] convenience is derived from the two languages, so
 * every implementation only has to store the three primitive values.
 */
interface SettingsStore {
    /** The currently selected text-to-speech engine. */
    var ttsMode: TtsMode

    /** The language the learner already knows (used for glosses and dictation). */
    var sourceLanguage: Language

    /** The language the learner is studying. */
    var targetLanguage: Language

    /** The current [LanguagePair] formed by [sourceLanguage] and [targetLanguage]. */
    var languagePair: LanguagePair
        get() = LanguagePair(source = sourceLanguage, target = targetLanguage)
        set(value) {
            sourceLanguage = value.source
            targetLanguage = value.target
        }
}
