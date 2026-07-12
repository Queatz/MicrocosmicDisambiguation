package com.queatz.microcosmicdisambiguation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.queatz.microcosmicdisambiguation.audio.AndroidAudioFactory
import com.queatz.microcosmicdisambiguation.data.ObjectBoxRecordingStore
import com.queatz.microcosmicdisambiguation.data.ObjectBoxStudyStore
import com.queatz.microcosmicdisambiguation.data.SettingsRepository
import com.queatz.microcosmicdisambiguation.ui.theme.MicrocosmicDisambiguationTheme

/**
 * The Android entry point. All UI, navigation and the view model now live in the shared module's
 * [MicrocosmicDisambiguationApp]; this Activity only builds the Android [AppContainer] (ObjectBox
 * persistence, `SharedPreferences` settings, on-device audio engines), sets up edge-to-edge, and
 * hosts the shared composable — so the exact same UI renders here and on the web.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = AppContainer(
            studyStore = ObjectBoxStudyStore(applicationContext),
            recordingStore = ObjectBoxRecordingStore(applicationContext),
            settingsStore = SettingsRepository(applicationContext),
            audio = AndroidAudioFactory(applicationContext),
        )

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                MicrocosmicDisambiguationTheme {
                    MicrocosmicDisambiguationApp()
                }
            }
        }
    }
}
