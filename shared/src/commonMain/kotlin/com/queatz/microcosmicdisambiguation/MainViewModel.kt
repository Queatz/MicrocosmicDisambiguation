package com.queatz.microcosmicdisambiguation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.queatz.microcosmicdisambiguation.data.ClonedVoice
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.LanguagePair
import com.queatz.microcosmicdisambiguation.data.MissionSet
import com.queatz.microcosmicdisambiguation.data.Recording
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import com.queatz.microcosmicdisambiguation.data.StudySession
import com.queatz.microcosmicdisambiguation.data.TtsMode
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.error_generate_missions
import com.queatz.microcosmicdisambiguation.resources.error_generate_study_guide
import com.queatz.microcosmicdisambiguation.resources.error_session_not_found
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/** UI state for the home screen. */
data class HomeUiState(
    val history: List<StudySession> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val generatedSessionId: Long? = null,
    val ttsMode: TtsMode = TtsMode.System,
    val languagePair: LanguagePair = LanguagePair.Default,
    val showWelcomeBanner: Boolean = false,
)

/**
 * UI state for the mission screen of a single study session.
 *
 * @param sessionId the session whose missions are loaded (0 before any load)
 * @param isGenerating true while the missions are being generated for the first time
 * @param missions the loaded missions, or null while still loading/generating
 * @param error a message to show if generation failed
 */
data class MissionUiState(
    val sessionId: Long = 0,
    val isGenerating: Boolean = false,
    val missions: MissionSet? = null,
    val error: String? = null,
)

/**
 * The shared, platform-agnostic view model. It drives every screen on every platform, talking only
 * to the injected [AppContainer] (persistence + AI), never to a platform API. Persistence is
 * suspend-based, so an in-memory [sessions] snapshot backs the handful of synchronous lookups the
 * navigation graph needs.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val studyStore = container.studyStore
    private val recordingStore = container.recordingStore
    private val settings = container.settingsStore

    /** Exposed so the UI can build the Qwen-backed text-to-speech engine. */
    val qwenService = container.qwenService

    /** The loaded sessions, kept in memory so [guide]/[title]/[languagePairOf] can be synchronous. */
    private var sessions: List<StudySession> = emptyList()

    private val _uiState = MutableStateFlow(
        HomeUiState(
            ttsMode = settings.ttsMode,
            languagePair = settings.languagePair,
            showWelcomeBanner = !settings.hasDismissedWelcomeBanner,
        ),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _missionState = MutableStateFlow(MissionUiState())
    val missionState: StateFlow<MissionUiState> = _missionState.asStateFlow()

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _clonedVoices = MutableStateFlow(settings.clonedVoices)
    val clonedVoices: StateFlow<List<ClonedVoice>> = _clonedVoices.asStateFlow()

    init {
        refreshHistory()
        refreshRecordings()
    }

    /** Persists the selected text-to-speech engine and updates the UI state. */
    fun setTtsMode(mode: TtsMode) {
        settings.ttsMode = mode
        _uiState.update { it.copy(ttsMode = mode) }
    }

    /** Persists the source language (the one the learner knows), chosen in settings. */
    fun setSourceLanguage(language: Language) {
        settings.sourceLanguage = language
        _uiState.update { it.copy(languagePair = settings.languagePair) }
    }

    /** Persists the target language (the one being studied), chosen from the home screen. */
    fun setTargetLanguage(language: Language) {
        settings.targetLanguage = language
        _uiState.update { it.copy(languagePair = settings.languagePair) }
    }

    /** Swaps the source and target languages. */
    fun swapLanguages() {
        settings.languagePair = settings.languagePair.swapped()
        _uiState.update { it.copy(languagePair = settings.languagePair) }
    }

    /** Dismisses the first-time welcome banner and remembers not to show it again. */
    fun dismissWelcomeBanner() {
        settings.hasDismissedWelcomeBanner = true
        _uiState.update { it.copy(showWelcomeBanner = false) }
    }

    /** The [LanguagePair] a stored session was generated for (its own languages, not the current selection). */
    fun languagePairOf(id: Long): LanguagePair =
        sessions.firstOrNull { it.id == id }?.languages ?: _uiState.value.languagePair

    private fun refreshHistory() {
        viewModelScope.launch {
            sessions = studyStore.all()
            _uiState.update { it.copy(history = sessions) }
        }
    }

    private fun refreshRecordings() {
        viewModelScope.launch { _recordings.value = recordingStore.all() }
    }

    /** Persists a completed dictation [audio]/[transcript] pair, then refreshes the recordings list. */
    fun saveRecording(audio: ByteArray, transcript: String) {
        viewModelScope.launch {
            recordingStore.save(audio, transcript)
            _recordings.value = recordingStore.all()
        }
    }

    /** Deletes the stored recording with [id] (and its audio) and refreshes the list. */
    fun deleteRecording(id: Long) {
        viewModelScope.launch {
            recordingStore.delete(id)
            _recordings.value = recordingStore.all()
        }
    }

    /**
     * Registers a recorded/uploaded [audio] sample as a cloned CosyVoice voice named [name] for
     * [language], persisting it (enabled) and republishing [clonedVoices] on success. Runs the
     * (network) enrollment on the caller's coroutine so the clone dialog can await the outcome and
     * reveal either success or the real error. [mimeType] describes [audio] (WAV for recordings).
     */
    suspend fun enrollVoice(
        name: String,
        language: Language,
        audio: ByteArray,
        mimeType: String = "audio/wav",
    ): Result<ClonedVoice> = runCatching {
        val voiceId = qwenService.enrollClonedVoice(name = name, audio = audio, mimeType = mimeType)
        val existing = settings.clonedVoices
        val voice = ClonedVoice(
            id = "cv_${(existing.maxOfOrNull { it.createdAt } ?: 0L) + 1L}",
            name = name.trim().ifBlank { language.displayName },
            language = language,
            voiceId = voiceId,
            enabled = true,
            // A monotonically increasing stamp so the most recently enrolled voice wins selection,
            // without needing a wall clock in shared code (see ClonedVoices.enabledVoiceIdFor).
            createdAt = (existing.maxOfOrNull { it.createdAt } ?: 0L) + 1L,
        )
        persistClonedVoices(existing + voice)
        voice
    }

    /** Enables or disables the cloned voice with [id], persisting the change and updating state. */
    fun setClonedVoiceEnabled(id: String, enabled: Boolean) {
        persistClonedVoices(
            settings.clonedVoices.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }

    /** Deletes the cloned voice with [id], persisting the change and updating state. */
    fun deleteClonedVoice(id: String) {
        persistClonedVoices(settings.clonedVoices.filterNot { it.id == id })
    }

    private fun persistClonedVoices(voices: List<ClonedVoice>) {
        settings.clonedVoices = voices
        _clonedVoices.value = voices
    }

    /**
     * Generates a study guide for [situation], persists it, and exposes the new session id via
     * [HomeUiState.generatedSessionId] so navigation survives configuration changes. Consume the
     * event with [onSessionGenerated] once handled.
     */
    fun generate(situation: String) {
        val trimmed = situation.trim()
        if (trimmed.isEmpty() || _uiState.value.isGenerating) return

        val languages = _uiState.value.languagePair
        _uiState.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch {
            try {
                val guide = qwenService.generateStudyGuide(trimmed, languages)
                val saved = studyStore.save(trimmed, guide, languages)
                sessions = studyStore.all()
                _uiState.update {
                    it.copy(isGenerating = false, generatedSessionId = saved.id, history = sessions)
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = t.message ?: getString(Res.string.error_generate_study_guide),
                    )
                }
            }
        }
    }

    /** Consumes the pending [HomeUiState.generatedSessionId] navigation event. */
    fun onSessionGenerated() {
        _uiState.update { it.copy(generatedSessionId = null) }
    }

    /** Deletes the stored session with [id] and refreshes the history list. */
    fun deleteSession(id: Long) {
        viewModelScope.launch {
            studyStore.delete(id)
            sessions = studyStore.all()
            _uiState.update { it.copy(history = sessions) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun guide(id: Long): StudyGuide? = sessions.firstOrNull { it.id == id }?.guide

    /**
     * Marks the play round for structure [structureIndex] of session [id] as completed at least
     * once, persisting it so that play screen becomes skippable on later visits. The updated
     * session list is republished on [uiState] so open screens see the change immediately.
     */
    fun markStructurePlayed(id: Long, structureIndex: Int) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        if (structureIndex in session.playedStructures) return
        val updated = session.playedStructures + structureIndex
        // Update the in-memory snapshot immediately so the UI unlocks skipping without waiting.
        sessions = sessions.map { if (it.id == id) it.copy(playedStructures = updated) else it }
        _uiState.update { it.copy(history = sessions) }
        viewModelScope.launch {
            studyStore.savePlayedStructures(id, updated)
        }
    }

    /**
     * Loads the missions for session [id], generating them from its study guide the first time and
     * caching the result so later visits are instant. The outcome is published on [missionState].
     * Re-invoking for the already-loaded (or in-flight) session is a no-op.
     */
    fun loadMissions(id: Long) {
        val current = _missionState.value
        if (current.sessionId == id && (current.missions != null || current.isGenerating)) return

        val session = sessions.firstOrNull { it.id == id }
        if (session == null) {
            viewModelScope.launch {
                _missionState.value = MissionUiState(
                    sessionId = id,
                    error = getString(Res.string.error_session_not_found),
                )
            }
            return
        }

        val cached = session.missions
        if (cached != null) {
            _missionState.value = MissionUiState(sessionId = id, missions = cached)
            return
        }

        _missionState.value = MissionUiState(sessionId = id, isGenerating = true)
        val languages = session.languages
        // Generation runs on a job tied to the ViewModel (not the screen), so it keeps going if the
        // user leaves the mission screen; Ktor handles its own threading, so no explicit dispatcher
        // is needed (and none exists on Wasm).
        viewModelScope.launch {
            try {
                val missions = qwenService.generateMissions(session.guide, languages)
                studyStore.saveMissions(id, missions)
                sessions = studyStore.all()
                _uiState.update { it.copy(history = sessions) }
                _missionState.update {
                    if (it.sessionId == id) it.copy(isGenerating = false, missions = missions) else it
                }
            } catch (t: Throwable) {
                _missionState.update {
                    if (it.sessionId == id) {
                        it.copy(
                            isGenerating = false,
                            error = t.message ?: getString(Res.string.error_generate_missions),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Clears any loaded missions so the next [loadMissions] starts fresh (e.g. on retry). */
    fun resetMissions() {
        _missionState.value = MissionUiState()
    }

    fun title(id: Long): String = sessions.firstOrNull { it.id == id }?.title.orEmpty()
}
