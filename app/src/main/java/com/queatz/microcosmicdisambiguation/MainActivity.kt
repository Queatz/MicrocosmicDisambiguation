package com.queatz.microcosmicdisambiguation

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.queatz.microcosmicdisambiguation.audio.QwenSpeaker
import com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer
import com.queatz.microcosmicdisambiguation.audio.Speaker
import com.queatz.microcosmicdisambiguation.audio.SpeakerStatus
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.data.TtsMode
import com.queatz.microcosmicdisambiguation.ui.HomeScreen
import com.queatz.microcosmicdisambiguation.ui.MissionScreen
import com.queatz.microcosmicdisambiguation.ui.SettingsScreen
import com.queatz.microcosmicdisambiguation.ui.StudyScreen
import com.queatz.microcosmicdisambiguation.ui.theme.MicrocosmicDisambiguationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MicrocosmicDisambiguationTheme {
                MicrocosmicDisambiguationApp()
            }
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_STUDY = "study"
private const val ROUTE_MISSION = "mission"
private const val ROUTE_SETTINGS = "settings"
private const val ARG_ID = "id"

@Composable
fun MicrocosmicDisambiguationApp() {
    val viewModel: MainViewModel = viewModel()
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    // The active engine is recreated whenever the user switches TTS modes in settings.
    val speaker: TtsEngine = remember(state.ttsMode) {
        when (state.ttsMode) {
            TtsMode.Ai -> QwenSpeaker(context, viewModel.qwenService)
            TtsMode.System -> Speaker(context)
        }
    }
    var speakerStatus by remember { mutableStateOf(SpeakerStatus.Initializing) }
    DisposableEffect(speaker) {
        speaker.onStatusChanged = { speakerStatus = it }
        onDispose { speaker.shutdown() }
    }

    // Real-time speech-to-text for dictating the "IRL situation" on the home screen.
    val speechRecognizer = remember { QwenSpeechRecognizer() }
    DisposableEffect(speechRecognizer) {
        onDispose { speechRecognizer.shutdown() }
    }

    var ttsPromptDismissed by remember { mutableStateOf(false) }
    if (!ttsPromptDismissed &&
        (speakerStatus == SpeakerStatus.MissingData || speakerStatus == SpeakerStatus.NotSupported)
    ) {
        val missingData = speakerStatus == SpeakerStatus.MissingData
        AlertDialog(
            onDismissRequest = { ttsPromptDismissed = true },
            title = { Text("Chinese voice unavailable") },
            text = {
                Text(
                    if (missingData) {
                        "To hear sentences read aloud, the Simplified Chinese voice data needs to be downloaded."
                    } else {
                        "Your text-to-speech engine doesn't support Simplified Chinese. " +
                            "Open settings to install or switch to an engine that does."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ttsPromptDismissed = true
                    val intent = if (missingData) {
                        Speaker.installTtsDataIntent()
                    } else {
                        android.content.Intent("com.android.settings.TTS_SETTINGS")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // No activity available to handle the request; nothing else we can do.
                    }
                }) {
                    Text(if (missingData) "Download" else "Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { ttsPromptDismissed = true }) {
                    Text("Not now")
                }
            },
        )
    }

    // Generation runs on a background job in the ViewModel that outlives navigation, so the result
    // can arrive while the user is elsewhere. Observe it at the app level and only auto-navigate to
    // the freshly generated session when the user is still on the home screen; otherwise just
    // consume the event so it never yanks the user away from another screen later.
    LaunchedEffect(state.generatedSessionId) {
        val id = state.generatedSessionId ?: return@LaunchedEffect
        if (navController.currentDestination?.route == ROUTE_HOME) {
            navController.navigate("$ROUTE_STUDY/$id")
        }
        viewModel.onSessionGenerated()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    state = state,
                    recognizer = speechRecognizer,
                    onStudy = { situation -> viewModel.generate(situation) },
                    onOpenSession = { id -> navController.navigate("$ROUTE_STUDY/$id") },
                    onDeleteSession = { id -> viewModel.deleteSession(id) },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    ttsMode = state.ttsMode,
                    onSelectMode = { viewModel.setTtsMode(it) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_STUDY/{$ARG_ID}",
                arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong(ARG_ID) ?: 0L
                val guide = remember(id) { viewModel.guide(id) }
                if (guide == null) {
                    navController.popBackStack()
                } else {
                    StudyScreen(
                        guide = guide,
                        speaker = speaker,
                        onClose = { navController.popBackStack() },
                        onStartMissions = { navController.navigate("$ROUTE_MISSION/$id") },
                    )
                }
            }
            composable(
                route = "$ROUTE_MISSION/{$ARG_ID}",
                arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong(ARG_ID) ?: 0L
                val missionState by viewModel.missionState.collectAsState()
                LaunchedEffect(id) { viewModel.loadMissions(id) }
                MissionScreen(
                    title = remember(id) { viewModel.title(id) },
                    state = missionState,
                    speaker = speaker,
                    onClose = { navController.popBackStack() },
                    onRetry = {
                        viewModel.resetMissions()
                        viewModel.loadMissions(id)
                    },
                )
            }
        }
    }
}
