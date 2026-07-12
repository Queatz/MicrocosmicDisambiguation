package com.queatz.microcosmicdisambiguation.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scripts whose fallback font has already been fetched and preloaded this session, so the same
 * script is never downloaded twice — regardless of how many times [WithScriptFallback] runs, or
 * for how many different pieces of text.
 */
private val loadedScripts = mutableSetOf<Script>()

/** Serializes the check-then-fetch-then-mark sequence so two scripts encountered at once can't race. */
private val loadingMutex = Mutex()

/**
 * A small client dedicated to font downloads (as opposed to [com.queatz.microcosmicdisambiguation.ai.QwenService]'s
 * client). Some scripts (CJK) ship as a single ~16 MB combined font, hence the generous timeout.
 */
private val fontClient by lazy {
    HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
        }
    }
}

/**
 * Scans [text] for scripts with no fallback loaded yet, downloads each one (see [Script.fontUrl])
 * and preloads it into [fontFamilyResolver]. Already-rendered content is never hidden while this
 * runs — [content] is shown immediately — but once a new font finishes loading, the whole subtree
 * is remounted (via [key]) so every `Text` under it re-measures with the new fallback available.
 */
@Composable
actual fun WithScriptFallback(text: String, content: @Composable () -> Unit) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(text) {
        val missing = text.detectScripts().filterNot { it in loadedScripts }
        if (missing.isEmpty()) return@LaunchedEffect

        var loadedAny = false
        missing.forEach { script ->
            loadingMutex.withLock {
                if (script in loadedScripts) return@withLock
                runCatching {
                    val bytes = fontClient.get(script.fontUrl()).readRawBytes()
                    val family = FontFamily(Font(identity = script.notoFamilyName, data = bytes))
                    fontFamilyResolver.preload(family)
                }.onSuccess {
                    loadedScripts += script
                    loadedAny = true
                }
                // On failure, the script is simply left out of [loadedScripts] so a later call
                // (e.g. the next piece of text using it) retries the download.
            }
        }
        if (loadedAny) reloadKey++
    }

    key(reloadKey) {
        content()
    }
}
