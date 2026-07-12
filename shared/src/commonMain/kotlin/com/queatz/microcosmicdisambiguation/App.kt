package com.queatz.microcosmicdisambiguation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.queatz.microcosmicdisambiguation.audio.SpeakerStatus
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.tts_download
import com.queatz.microcosmicdisambiguation.resources.tts_missing_data_message
import com.queatz.microcosmicdisambiguation.resources.tts_not_now
import com.queatz.microcosmicdisambiguation.resources.tts_not_supported_message
import com.queatz.microcosmicdisambiguation.resources.tts_open_settings
import com.queatz.microcosmicdisambiguation.resources.tts_voice_unavailable_title
import com.queatz.microcosmicdisambiguation.ui.HomeScreen
import com.queatz.microcosmicdisambiguation.ui.MissionScreen
import com.queatz.microcosmicdisambiguation.ui.RecordingsScreen
import com.queatz.microcosmicdisambiguation.ui.SettingsScreen
import com.queatz.microcosmicdisambiguation.ui.StudyScreen
import com.queatz.microcosmicdisambiguation.ui.text.WithScriptFallback
import org.jetbrains.compose.resources.stringResource

/** The in-app navigation destinations, driving a tiny explicit back stack that works on every platform. */
private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Recordings : Screen
    data class Study(val id: Long) : Screen
    data class Mission(val id: Long) : Screen
}

/**
 * The whole app UI, written entirely in Compose Multiplatform. Android and the web render this exact
 * composable — the only differences are the platform-provided [AppContainer] pieces (persistence,
 * audio engines, permissions) supplied via [LocalAppContainer].
 */
@Composable
fun MicrocosmicDisambiguationApp() {
    val container = LocalAppContainer.current
    val viewModel: MainViewModel = viewModel { MainViewModel(container) }
    val state by viewModel.uiState.collectAsState()
    val missionState by viewModel.missionState.collectAsState()

    // Every script the app needs to render, present or future, is discovered here rather than
    // hand-wired per language: this covers every session's generated study guide and missions
    // (current and historical, since `state.history` embeds them), so any new script encountered
    // in AI-generated content gets its fallback font preloaded automatically. See `WithScriptFallback`.
    val visibleText = remember(state, missionState) { state.toString() + missionState.toString() }

    // A minimal, platform-agnostic navigation stack (replaces Android-only Navigation-Compose).
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = backStack.last()
    fun navigate(screen: Screen) { backStack.add(screen) }
    fun popBack() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    // The language the active speaker reads in. It tracks the home-screen target selection, but each
    // study/mission screen overrides it with that session's own target language so reopening an old
    // session always speaks the language it was created in.
    var speakerLanguage by remember { mutableStateOf(state.languagePair.target) }
    // The active engine is recreated whenever the user switches TTS modes or the spoken language.
    val speaker: TtsEngine = remember(state.ttsMode, speakerLanguage) {
        container.audio.createSpeaker(state.ttsMode, speakerLanguage, viewModel.qwenService)
    }
    var speakerStatus by remember { mutableStateOf(SpeakerStatus.Initializing) }
    DisposableEffect(speaker) {
        speaker.onStatusChanged = { speakerStatus = it }
        onDispose { speaker.shutdown() }
    }

    // Real-time speech-to-text for dictating the "IRL situation" on the home screen, biased toward
    // the language the learner knows (the source language).
    val speechRecognizer = remember(state.languagePair.source) {
        container.audio.createSpeechRecognizer(listOf(state.languagePair.source.asrCode))
    }
    DisposableEffect(speechRecognizer) {
        // Persist the raw audio and final transcript of each completed dictation session.
        speechRecognizer.onSessionComplete = { audio, transcript ->
            viewModel.saveRecording(audio, transcript)
        }
        onDispose {
            speechRecognizer.onSessionComplete = null
            speechRecognizer.shutdown()
        }
    }

    var ttsPromptDismissed by remember { mutableStateOf(false) }
    if (!ttsPromptDismissed &&
        (speakerStatus == SpeakerStatus.MissingData || speakerStatus == SpeakerStatus.NotSupported)
    ) {
        val missingData = speakerStatus == SpeakerStatus.MissingData
        val languageName = speakerLanguage.displayName
        AlertDialog(
            onDismissRequest = { ttsPromptDismissed = true },
            title = { Text(stringResource(Res.string.tts_voice_unavailable_title, languageName)) },
            text = {
                Text(
                    if (missingData) {
                        stringResource(Res.string.tts_missing_data_message, languageName)
                    } else {
                        stringResource(Res.string.tts_not_supported_message, languageName)
                    }
                )
            },
            confirmButton = {
                // Only platforms that can manage voices (Android) offer the actionable button.
                if (container.audio.canManageVoices) {
                    TextButton(onClick = {
                        ttsPromptDismissed = true
                        container.audio.manageVoices(missingData)
                    }) {
                        Text(
                            if (missingData) {
                                stringResource(Res.string.tts_download)
                            } else {
                                stringResource(Res.string.tts_open_settings)
                            }
                        )
                    }
                } else {
                    TextButton(onClick = { ttsPromptDismissed = true }) {
                        Text(stringResource(Res.string.tts_not_now))
                    }
                }
            },
            dismissButton = {
                if (container.audio.canManageVoices) {
                    TextButton(onClick = { ttsPromptDismissed = true }) {
                        Text(stringResource(Res.string.tts_not_now))
                    }
                }
            },
        )
    }

    // Generation runs on a background job in the ViewModel that outlives navigation, so the result
    // can arrive while the user is elsewhere. Only auto-navigate to the freshly generated session
    // when the user is still on the home screen; otherwise just consume the event.
    LaunchedEffect(state.generatedSessionId) {
        val id = state.generatedSessionId ?: return@LaunchedEffect
        if (backStack.last() == Screen.Home) {
            navigate(Screen.Study(id))
        }
        viewModel.onSessionGenerated()
    }

    WithScriptFallback(text = visibleText) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (val screen = current) {
                Screen.Home -> {
                    // On the home screen, the speaker reads the currently selected target language.
                    LaunchedEffect(state.languagePair.target) {
                        speakerLanguage = state.languagePair.target
                    }
                    HomeScreen(
                        state = state,
                        recognizer = speechRecognizer,
                        onStudy = { situation -> viewModel.generate(situation) },
                        onOpenSession = { id -> navigate(Screen.Study(id)) },
                        onDeleteSession = { id -> viewModel.deleteSession(id) },
                        onOpenSettings = { navigate(Screen.Settings) },
                        onDismissError = { viewModel.clearError() },
                        onTargetLanguageChange = { viewModel.setTargetLanguage(it) },
                        onDismissWelcomeBanner = { viewModel.dismissWelcomeBanner() },
                        modifier = contentModifier,
                    )
                }

                Screen.Settings -> {
                    val clonedVoices by viewModel.clonedVoices.collectAsState()
                    SettingsScreen(
                        ttsMode = state.ttsMode,
                        sourceLanguage = state.languagePair.source,
                        targetLanguage = state.languagePair.target,
                        clonedVoices = clonedVoices,
                        onSelectMode = { viewModel.setTtsMode(it) },
                        onSelectSourceLanguage = { viewModel.setSourceLanguage(it) },
                        onBack = { popBack() },
                        onOpenRecordings = { navigate(Screen.Recordings) },
                        onToggleClonedVoice = { id, enabled -> viewModel.setClonedVoiceEnabled(id, enabled) },
                        onDeleteClonedVoice = { id -> viewModel.deleteClonedVoice(id) },
                        onEnrollVoice = { name, language, audio, mimeType ->
                            viewModel.enrollVoice(name, language, audio, mimeType)
                        },
                        createRecognizer = { language ->
                            container.audio.createSpeechRecognizer(listOf(language.asrCode))
                        },
                        modifier = contentModifier,
                    )
                }

                Screen.Recordings -> {
                    val recordings by viewModel.recordings.collectAsState()
                    RecordingsScreen(
                        recordings = recordings,
                        onBack = { popBack() },
                        onDelete = { id -> viewModel.deleteRecording(id) },
                        modifier = contentModifier,
                    )
                }

                is Screen.Study -> {
                    val id = screen.id
                    // Speak this session in its own target language, not the current selection.
                    LaunchedEffect(id) { speakerLanguage = viewModel.languagePairOf(id).target }
                    val guide = remember(id) { viewModel.guide(id) }
                    if (guide == null) {
                        LaunchedEffect(id) { popBack() }
                    } else {
                        val sessionLanguage = remember(id) { viewModel.languagePairOf(id).target }
                        // Read the played rounds from the live history so completing a play round
                        // unlocks skipping immediately (and persists across visits).
                        val playedStructures = state.history
                            .firstOrNull { it.id == id }
                            ?.playedStructures
                            .orEmpty()
                        StudyScreen(
                            guide = guide,
                            speaker = speaker,
                            language = sessionLanguage,
                            playedStructures = playedStructures,
                            onStructurePlayed = { index ->
                                viewModel.markStructurePlayed(id, index)
                            },
                            onClose = { popBack() },
                            onStartMissions = { navigate(Screen.Mission(id)) },
                            modifier = contentModifier,
                        )
                    }
                }

                is Screen.Mission -> {
                    val id = screen.id
                    LaunchedEffect(id) { speakerLanguage = viewModel.languagePairOf(id).target }
                    LaunchedEffect(id) { viewModel.loadMissions(id) }
                    MissionScreen(
                        title = remember(id) { viewModel.title(id) },
                        state = missionState,
                        speaker = speaker,
                        qwenService = viewModel.qwenService,
                        languages = viewModel.languagePairOf(id),
                        onClose = { popBack() },
                        onRetry = {
                            viewModel.resetMissions()
                            viewModel.loadMissions(id)
                        },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}
