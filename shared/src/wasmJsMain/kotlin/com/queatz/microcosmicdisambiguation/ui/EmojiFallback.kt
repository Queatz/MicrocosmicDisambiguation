package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.queatz.microcosmicdisambiguation.resources.Res

/**
 * Registers the bundled color emoji font as a global fallback so emojis stop rendering as "tofu"
 * boxes on the Compose Web/Skiko renderer (which ships no emoji-capable font).
 *
 * The bundled font is Noto Color Emoji (COLRv1), which Skia renders in full color. The font is
 * preloaded into the active [FontFamily.Resolver]: Skia first looks up glyphs in the fonts
 * referenced by a `Text`/theme, then in the bundled default (Roboto), and finally in the preloaded
 * fallback collection — which is where the color emoji glyphs now come from. [content] is shown
 * only once the fallback font is ready, so the first frame never renders boxes.
 */
@Composable
fun WithEmojiFallback(content: @Composable () -> Unit) {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    var fontsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val emojiFamily = FontFamily(Font(identity = "NotoColorEmoji", data = emojiFontBytes()))
            fontFamilyResolver.preload(emojiFamily)
        }
        fontsLoaded = true
    }

    if (fontsLoaded) {
        content()
    } else {
        // While the fallback font loads, `content()` (and therefore the whole app UI) isn't composed
        // yet, so the Compose canvas would otherwise paint its default opaque white — a visible white
        // flash when the OS is in dark mode. Fill the canvas with the theme background (which follows
        // the OS light/dark scheme) so this gap matches the app itself.
        ThemedLoadingBackground()
    }
}

/** The raw bytes of the bundled color emoji fallback font (Noto Color Emoji, COLRv1). */
private suspend fun emojiFontBytes(): ByteArray = Res.readBytes("font/noto_color_emoji.ttf")

/** Fills the whole canvas with the current theme background so the pre-app gap isn't a white flash. */
@Composable
private fun ThemedLoadingBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
