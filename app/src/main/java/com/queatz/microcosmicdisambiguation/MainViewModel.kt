package com.queatz.microcosmicdisambiguation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.queatz.microcosmicdisambiguation.ai.QwenService
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SituationRepository(application)
    private val settings = SettingsRepository(application)

    /** Exposed so the UI can build the Qwen-backed text-to-speech engine. */
    val qwenService = QwenService()
    private val qwen = qwenService

    private val _uiState = MutableStateFlow(HomeUiState(ttsMode = settings.ttsMode))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refreshHistory()
    }

    /** Persists the selected text-to-speech engine and updates the UI state. */
    fun setTtsMode(mode: TtsMode) {
        settings.ttsMode = mode
        _uiState.update { it.copy(ttsMode = mode) }
    }

    private fun refreshHistory() {
        _uiState.update { it.copy(history = repository.all()) }
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

    fun title(id: Long): String = repository.get(id)?.title.orEmpty()
}
