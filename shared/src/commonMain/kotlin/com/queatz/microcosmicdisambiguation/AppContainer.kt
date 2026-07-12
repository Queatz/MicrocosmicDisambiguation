package com.queatz.microcosmicdisambiguation

import androidx.compose.runtime.staticCompositionLocalOf
import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.audio.AudioFactory
import com.queatz.microcosmicdisambiguation.data.RecordingStore
import com.queatz.microcosmicdisambiguation.data.SettingsStore
import com.queatz.microcosmicdisambiguation.data.StudyStore

/**
 * The platform-provided dependencies the shared UI needs: persistence, audio engines, and the AI
 * service. Each platform builds one of these and provides it to the shared [MicrocosmicDisambiguationApp]
 * via [LocalAppContainer] — Android with ObjectBox + on-device audio, the web with in-memory stores
 * + the Web Speech API — so the UI itself stays completely platform-agnostic.
 */
class AppContainer(
    val studyStore: StudyStore,
    val recordingStore: RecordingStore,
    val settingsStore: SettingsStore,
    val audio: AudioFactory,
    val qwenService: QwenService = QwenService(
        cachedVoiceId = { settingsStore.cosyVoiceId },
        enabledClonedVoiceId = { settingsStore.enabledClonedVoiceId(it) },
    ),
)

/** Provides the current [AppContainer] to the shared UI. Must be supplied by the platform host. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided. Wrap the app UI in CompositionLocalProvider(LocalAppContainer provides ...).")
}
