package com.queatz.microcosmicdisambiguation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.MissionSet
import com.queatz.microcosmicdisambiguation.data.RecordingEntity
import com.queatz.microcosmicdisambiguation.data.RecordingRepository
import com.queatz.microcosmicdisambiguation.data.SettingsRepository
import com.queatz.microcosmicdisambiguation.data.SituationEntity
import com.queatz.microcosmicdisambiguation.data.SituationRepository
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import com.queatz.microcosmicdisambiguation.data.TtsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val history: List<SituationEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val generatedSessionId: Long? = null,
    val ttsMode: TtsMode = TtsMode.System,
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SituationRepository(application)
    private val recordingRepository = RecordingRepository(application)
    private val settings = SettingsRepository(application)

    /** Exposed so the UI can build the Qwen-backed text-to-speech engine. */
    val qwenService = QwenService()
    private val qwen = qwenService

    private val _uiState = MutableStateFlow(HomeUiState(ttsMode = settings.ttsMode))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _missionState = MutableStateFlow(MissionUiState())
    val missionState: StateFlow<MissionUiState> = _missionState.asStateFlow()

    private val _recordings = MutableStateFlow<List<RecordingEntity>>(emptyList())
    val recordings: StateFlow<List<RecordingEntity>> = _recordings.asStateFlow()

    init {
        refreshHistory()
        refreshRecordings()
    }

    /** Persists the selected text-to-speech engine and updates the UI state. */
    fun setTtsMode(mode: TtsMode) {
        settings.ttsMode = mode
        _uiState.update { it.copy(ttsMode = mode) }
    }

    private fun refreshHistory() {
        _uiState.update { it.copy(history = repository.all()) }
    }

    private fun refreshRecordings() {
        _recordings.value = recordingRepository.all()
    }

    /** Persists a completed dictation [audio]/[transcript] pair, then refreshes the recordings list. */
    fun saveRecording(audio: ByteArray, transcript: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingRepository.save(audio, transcript) }
            refreshRecordings()
        }
    }

    /** Deletes the stored recording with [id] (and its audio file) and refreshes the list. */
    fun deleteRecording(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingRepository.delete(id) }
            refreshRecordings()
        }
    }

    /**
     * Generates a study guide for [situation], persists it, and exposes the new session id via
     * [HomeUiState.generatedSessionId] so navigation survives configuration changes such as
     * device rotation. Consume the event with [onSessionGenerated] once handled.
     */
    fun generate(situation: String) {
        val trimmed = situation.trim()
        if (trimmed.isEmpty() || _uiState.value.isGenerating) return

        _uiState.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch {
            try {
                val guide = withContext(Dispatchers.IO) { qwen.generateStudyGuide(trimmed) }
                val id = withContext(Dispatchers.IO) { repository.save(trimmed, guide) }
                refreshHistory()
                _uiState.update { it.copy(isGenerating = false, generatedSessionId = id) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isGenerating = false, error = t.message ?: "Failed to generate study guide")
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
            withContext(Dispatchers.IO) { repository.delete(id) }
            refreshHistory()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun guide(id: Long): StudyGuide? {
        val entity = repository.get(id) ?: return null
        return repository.guideOf(entity)
    }

    /**
     * Loads the missions for session [id], generating them from its study guide the first time and
     * caching the result so later visits are instant. The outcome is published on [missionState].
     * Re-invoking for the already-loaded (or in-flight) session is a no-op.
     */
    fun loadMissions(id: Long) {
        val current = _missionState.value
        if (current.sessionId == id && (current.missions != null || current.isGenerating)) return

        val entity = repository.get(id)
        if (entity == null) {
            _missionState.value = MissionUiState(sessionId = id, error = "Session not found")
            return
        }

        val cached = repository.missionsOf(entity)
        if (cached != null) {
            _missionState.value = MissionUiState(sessionId = id, missions = cached)
            return
        }

        _missionState.value = MissionUiState(sessionId = id, isGenerating = true)
        viewModelScope.launch {
            try {
                val guide = repository.guideOf(entity)
                val missions = withContext(Dispatchers.IO) { qwen.generateMissions(guide) }
                withContext(Dispatchers.IO) { repository.saveMissions(id, missions) }
                _missionState.update {
                    if (it.sessionId == id) it.copy(isGenerating = false, missions = missions) else it
                }
            } catch (t: Throwable) {
                _missionState.update {
                    if (it.sessionId == id) {
                        it.copy(
                            isGenerating = false,
                            error = t.message ?: "Failed to generate missions",
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

    fun title(id: Long): String = repository.get(id)?.title.orEmpty()
}
