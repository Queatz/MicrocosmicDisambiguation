package com.queatz.microcosmicdisambiguation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.queatz.microcosmicdisambiguation.audio.WebAudioFactory
import com.queatz.microcosmicdisambiguation.data.InMemoryRecordingStore
import com.queatz.microcosmicdisambiguation.data.InMemorySettingsStore
import com.queatz.microcosmicdisambiguation.data.InMemoryStudyStore
import com.queatz.microcosmicdisambiguation.ui.theme.MicrocosmicDisambiguationTheme
import kotlinx.browser.document

/**
 * The web entry point. It builds the browser [AppContainer] (in-memory persistence + Web Speech /
 * HTML-audio engines) and renders the exact same shared [MicrocosmicDisambiguationApp] the Android
 * app hosts, inside a Compose canvas that fills the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val container = AppContainer(
        studyStore = InMemoryStudyStore(),
        recordingStore = InMemoryRecordingStore(),
        settingsStore = InMemorySettingsStore(),
        audio = WebAudioFactory(),
    )
    ComposeViewport(document.body!!) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            MicrocosmicDisambiguationTheme {
                MicrocosmicDisambiguationApp()
            }
        }
    }
}
