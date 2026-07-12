package com.queatz.microcosmicdisambiguation.data

import com.queatz.microcosmicdisambiguation.audio.Wav
import com.queatz.microcosmicdisambiguation.audio.toBase64
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.js.JsString
import kotlin.js.Promise

private fun nowMillis(): Long = jsNow().toLong()

private fun jsNow(): Double = js("Date.now()")

/**
 * The JSON codec used to (de)serialize the stored rows. Unknown keys are ignored so older/newer
 * shapes never crash reads, and explicit nulls are dropped so a row saved without an `id` lets
 * IndexedDB auto-generate its key.
 */
private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private const val SESSIONS = "sessions"
private const val RECORDINGS = "recordings"

private const val KEY_TTS_MODE = "tts_mode"
private const val KEY_SOURCE_LANGUAGE = "source_language"
private const val KEY_TARGET_LANGUAGE = "target_language"
private const val KEY_WELCOME_BANNER_DISMISSED = "welcome_banner_dismissed"
private const val KEY_COSY_VOICE_ID = "cosy_voice_id"
private const val KEY_CLONED_VOICES = "cloned_voices"

/**
 * A tiny coroutine-friendly wrapper over the browser IndexedDB, bridging the callback-based JS API
 * to `suspend` functions. Every call funnels through a single cached database (see [ensureSetup]);
 * each operation creates its transaction and issues its request in the same synchronous callback so
 * the transaction never auto-deactivates before the request runs.
 */
internal object IndexedDb {

    /** Every stored row of [store] as a JSON array string. */
    suspend fun getAll(store: String): String {
        ensureSetup()
        return idbGetAll(store).await<JsString>().toString()
    }

    /** Inserts [row] (a JSON object without an `id`) into [store], returning the generated id. */
    suspend fun add(store: String, row: String): Long {
        ensureSetup()
        return idbAdd(store, row).await<JsString>().toString().toLong()
    }

    /** Inserts or replaces [row] (a JSON object carrying its `id`) in [store]. */
    suspend fun put(store: String, row: String) {
        ensureSetup()
        idbPut(store, row).await<JsString>()
    }

    /** The row with the given [id] in [store] as a JSON object string, or null if absent. */
    suspend fun get(store: String, id: Long): String? {
        ensureSetup()
        return idbGet(store, id.toDouble()).await<JsString>().toString().ifEmpty { null }
    }

    /** Removes the row with the given [id] from [store]. */
    suspend fun remove(store: String, id: Long) {
        ensureSetup()
        idbRemove(store, id.toDouble()).await<JsString>()
    }

    /** The stored value for the settings [key], or null if unset. */
    suspend fun getSetting(key: String): String? {
        ensureSetup()
        return idbGetValue(key).await<JsString>().toString().ifEmpty { null }
    }

    /** Stores [value] under the settings [key]. */
    suspend fun putSetting(key: String, value: String) {
        ensureSetup()
        idbSetValue(key, value).await<JsString>()
    }
}

/**
 * Defines `globalThis.MicrocosmicDb` (once) with a cached IndexedDB connection and the small set of
 * object-store helpers the stores need. Idempotent: the guard returns immediately if already set up.
 */
private fun ensureSetup() {
    js(
        """
        (function(){
          if (globalThis.MicrocosmicDb) return;
          var dbPromise = null;
          function dbp(){
            if (dbPromise) return dbPromise;
            dbPromise = new Promise(function(resolve, reject){
              var r = indexedDB.open('microcosmic', 1);
              r.onupgradeneeded = function(){
                var db = r.result;
                if (!db.objectStoreNames.contains('sessions')) db.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
                if (!db.objectStoreNames.contains('recordings')) db.createObjectStore('recordings', { keyPath: 'id', autoIncrement: true });
                if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings', { keyPath: 'key' });
              };
              r.onsuccess = function(){ resolve(r.result); };
              r.onerror = function(){ reject(r.error); };
            });
            return dbPromise;
          }
          function reqp(req){ return new Promise(function(res, rej){ req.onsuccess = function(){ res(req.result); }; req.onerror = function(){ rej(req.error); }; }); }
          globalThis.MicrocosmicDb = {
            getAll: function(name){ return dbp().then(function(db){ return reqp(db.transaction(name, 'readonly').objectStore(name).getAll()); }).then(function(rows){ return JSON.stringify(rows); }); },
            add: function(name, row){ return dbp().then(function(db){ return reqp(db.transaction(name, 'readwrite').objectStore(name).add(JSON.parse(row))); }).then(function(key){ return '' + key; }); },
            put: function(name, row){ return dbp().then(function(db){ return reqp(db.transaction(name, 'readwrite').objectStore(name).put(JSON.parse(row))); }).then(function(){ return ''; }); },
            get: function(name, id){ return dbp().then(function(db){ return reqp(db.transaction(name, 'readonly').objectStore(name).get(id)); }).then(function(row){ return row ? JSON.stringify(row) : ''; }); },
            remove: function(name, id){ return dbp().then(function(db){ return reqp(db.transaction(name, 'readwrite').objectStore(name).delete(id)); }).then(function(){ return ''; }); },
            getValue: function(key){ return dbp().then(function(db){ return reqp(db.transaction('settings', 'readonly').objectStore('settings').get(key)); }).then(function(row){ return row ? '' + row.value : ''; }); },
            setValue: function(key, value){ return dbp().then(function(db){ return reqp(db.transaction('settings', 'readwrite').objectStore('settings').put({ key: key, value: value })); }).then(function(){ return ''; }); }
          };
        })()
        """,
    )
}

private fun idbGetAll(store: String): Promise<JsString> = js("MicrocosmicDb.getAll(store)")

private fun idbAdd(store: String, row: String): Promise<JsString> = js("MicrocosmicDb.add(store, row)")

private fun idbPut(store: String, row: String): Promise<JsString> = js("MicrocosmicDb.put(store, row)")

private fun idbGet(store: String, id: Double): Promise<JsString> = js("MicrocosmicDb.get(store, id)")

private fun idbRemove(store: String, id: Double): Promise<JsString> = js("MicrocosmicDb.remove(store, id)")

private fun idbGetValue(key: String): Promise<JsString> = js("MicrocosmicDb.getValue(key)")

private fun idbSetValue(key: String, value: String): Promise<JsString> = js("MicrocosmicDb.setValue(key, value)")

/**
 * The persisted shape of a study session. [StudyGuide]/[MissionSet] are themselves serializable, so
 * they nest directly; the language pair is stored by [Language.name]. `id` is null before the row is
 * saved (so IndexedDB assigns the key) and carried on reads/updates.
 */
@Serializable
private data class SessionRow(
    val id: Long? = null,
    val situation: String = "",
    val title: String = "",
    val icon: String = "",
    val guide: StudyGuide = StudyGuide(),
    val missions: MissionSet? = null,
    val sourceLang: String = "",
    val targetLang: String = "",
    val createdAt: Long = 0L,
    val played: List<Int> = emptyList(),
)

private fun SessionRow.toSession(): StudySession = StudySession(
    id = id ?: 0L,
    situation = situation,
    title = title.ifBlank { situation },
    icon = icon,
    guide = guide,
    missions = missions,
    languages = LanguagePair(
        source = Language.fromName(sourceLang, LanguagePair.Default.source),
        target = Language.fromName(targetLang, LanguagePair.Default.target),
    ),
    createdAt = createdAt,
    playedStructures = played.toSet(),
)

/**
 * The web [StudyStore], backed by IndexedDB so sessions survive a page reload. Rows keep the guide
 * and (lazily generated) missions as nested JSON, mirroring how the Android ObjectBox store persists
 * them, while handing the shared UI the exact same platform-neutral [StudySession].
 */
class IndexedDbStudyStore : StudyStore {

    override suspend fun all(): List<StudySession> =
        json.decodeFromString(ListSerializer(SessionRow.serializer()), IndexedDb.getAll(SESSIONS))
            .map { it.toSession() }
            .sortedByDescending { it.createdAt }

    override suspend fun get(id: Long): StudySession? =
        IndexedDb.get(SESSIONS, id)
            ?.let { json.decodeFromString(SessionRow.serializer(), it).toSession() }

    override suspend fun save(
        situation: String,
        guide: StudyGuide,
        languages: LanguagePair,
    ): StudySession {
        val row = SessionRow(
            situation = situation,
            title = guide.title.ifBlank { situation },
            icon = guide.icon,
            guide = guide,
            missions = null,
            sourceLang = languages.source.name,
            targetLang = languages.target.name,
            createdAt = nowMillis(),
        )
        val id = IndexedDb.add(SESSIONS, json.encodeToString(SessionRow.serializer(), row))
        return row.copy(id = id).toSession()
    }

    override suspend fun saveMissions(id: Long, missions: MissionSet) {
        val existing = IndexedDb.get(SESSIONS, id) ?: return
        val row = json.decodeFromString(SessionRow.serializer(), existing).copy(missions = missions)
        IndexedDb.put(SESSIONS, json.encodeToString(SessionRow.serializer(), row))
    }

    override suspend fun savePlayedStructures(id: Long, playedStructures: Set<Int>) {
        val existing = IndexedDb.get(SESSIONS, id) ?: return
        val row = json.decodeFromString(SessionRow.serializer(), existing)
            .copy(played = playedStructures.sorted())
        IndexedDb.put(SESSIONS, json.encodeToString(SessionRow.serializer(), row))
    }

    override suspend fun delete(id: Long) {
        IndexedDb.remove(SESSIONS, id)
    }
}

/** The persisted shape of a recording. The audio rides along as a `data:` URL in [audioRef]. */
@Serializable
private data class RecordingRow(
    val id: Long? = null,
    val transcription: String = "",
    val createdAt: Long = 0L,
    val durationMs: Long = 0L,
    val audioRef: String = "",
)

private fun RecordingRow.toRecording(): Recording = Recording(
    id = id ?: 0L,
    transcription = transcription,
    createdAt = createdAt,
    durationMs = durationMs,
    audioRef = audioRef,
)

/**
 * The web [RecordingStore], backed by IndexedDB so captured clips survive a page reload. The audio
 * is stored as a `data:` URL (standing in for the ObjectBox row + WAV file the Android app writes),
 * so [Recording.audioRef] can be played back by the shared recordings screen.
 */
class IndexedDbRecordingStore : RecordingStore {

    override suspend fun all(): List<Recording> =
        json.decodeFromString(ListSerializer(RecordingRow.serializer()), IndexedDb.getAll(RECORDINGS))
            .map { it.toRecording() }
            .sortedByDescending { it.createdAt }

    override suspend fun save(pcm: ByteArray, transcription: String): Recording {
        val wav = Wav.pcmToWav(pcm)
        // 16 kHz mono 16-bit: two bytes per sample.
        val durationMs = pcm.size.toLong() * 1000L / (16_000L * 2L)
        val row = RecordingRow(
            transcription = transcription,
            createdAt = nowMillis(),
            durationMs = durationMs,
            audioRef = "data:audio/wav;base64,${wav.toBase64()}",
        )
        val id = IndexedDb.add(RECORDINGS, json.encodeToString(RecordingRow.serializer(), row))
        return row.copy(id = id).toRecording()
    }

    override suspend fun delete(id: Long) {
        IndexedDb.remove(RECORDINGS, id)
    }
}

/**
 * The web [SettingsStore], backed by IndexedDB so the chosen languages and TTS engine survive a page
 * reload. The shared UI reads these synchronously, so the values are hydrated once via [load] before
 * the app is built, then each change is written back to IndexedDB in the background.
 */
class IndexedDbSettingsStore private constructor(
    initialTts: TtsMode,
    initialSource: Language,
    initialTarget: Language,
    initialWelcomeBannerDismissed: Boolean,
    initialCosyVoiceId: String?,
    initialClonedVoicesJson: String,
) : SettingsStore {

    private val scope = MainScope()

    override var ttsMode: TtsMode = initialTts
        set(value) {
            field = value
            persist(KEY_TTS_MODE, value.name)
        }

    override var sourceLanguage: Language = initialSource
        set(value) {
            field = value
            persist(KEY_SOURCE_LANGUAGE, value.name)
        }

    override var targetLanguage: Language = initialTarget
        set(value) {
            field = value
            persist(KEY_TARGET_LANGUAGE, value.name)
        }

    override var hasDismissedWelcomeBanner: Boolean = initialWelcomeBannerDismissed
        set(value) {
            field = value
            persist(KEY_WELCOME_BANNER_DISMISSED, value.toString())
        }

    override var cosyVoiceId: String? = initialCosyVoiceId
        set(value) {
            field = value
            if (value != null) persist(KEY_COSY_VOICE_ID, value)
        }

    override var clonedVoicesJson: String = initialClonedVoicesJson
        set(value) {
            field = value
            persist(KEY_CLONED_VOICES, value)
        }

    private fun persist(key: String, value: String) {
        scope.launch { IndexedDb.putSetting(key, value) }
    }

    companion object {
        /** Reads the persisted settings from IndexedDB, falling back to defaults for anything unset. */
        suspend fun load(): IndexedDbSettingsStore {
            val ttsMode = runCatching {
                TtsMode.valueOf(IndexedDb.getSetting(KEY_TTS_MODE) ?: TtsMode.System.name)
            }.getOrDefault(TtsMode.System)
            val source = Language.fromName(
                IndexedDb.getSetting(KEY_SOURCE_LANGUAGE),
                LanguagePair.Default.source,
            )
            val target = Language.fromName(
                IndexedDb.getSetting(KEY_TARGET_LANGUAGE),
                LanguagePair.Default.target,
            )
            val welcomeBannerDismissed =
                IndexedDb.getSetting(KEY_WELCOME_BANNER_DISMISSED)?.toBoolean() ?: false
            val cosyVoiceId = IndexedDb.getSetting(KEY_COSY_VOICE_ID)
            val clonedVoicesJson = IndexedDb.getSetting(KEY_CLONED_VOICES) ?: "[]"
            return IndexedDbSettingsStore(
                ttsMode,
                source,
                target,
                welcomeBannerDismissed,
                cosyVoiceId,
                clonedVoicesJson,
            )
        }
    }
}
