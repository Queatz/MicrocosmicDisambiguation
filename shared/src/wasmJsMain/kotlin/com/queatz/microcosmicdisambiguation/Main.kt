package com.queatz.microcosmicdisambiguation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.queatz.microcosmicdisambiguation.audio.WebAudioFactory
import com.queatz.microcosmicdisambiguation.data.IndexedDbRecordingStore
import com.queatz.microcosmicdisambiguation.data.IndexedDbSettingsStore
import com.queatz.microcosmicdisambiguation.data.IndexedDbStudyStore
import com.queatz.microcosmicdisambiguation.ui.WithEmojiFallback
import com.queatz.microcosmicdisambiguation.ui.theme.MicrocosmicDisambiguationTheme
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The web entry point. It builds the browser [AppContainer] (IndexedDB persistence + Web Speech /
 * HTML-audio engines) and renders the exact same shared [MicrocosmicDisambiguationApp] the Android
 * app hosts, inside a Compose canvas that fills the page.
 *
 * Settings are read synchronously by the shared UI, so they are hydrated from IndexedDB first (a
 * quick async read); every other store opens the database lazily behind its `suspend` API.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        val container = AppContainer(
            studyStore = IndexedDbStudyStore(),
            recordingStore = IndexedDbRecordingStore(),
            settingsStore = IndexedDbSettingsStore.load(),
            audio = WebAudioFactory(),
        )
        ComposeViewport(document.body!!) {
            CompositionLocalProvider(LocalAppContainer provides container) {
                MicrocosmicDisambiguationTheme {
                    WithEmojiFallback {
                        MicrocosmicDisambiguationApp()
                    }
                }
            }
        }
    }
}
