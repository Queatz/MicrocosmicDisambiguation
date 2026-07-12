package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.queatz.microcosmicdisambiguation.audio.fromBase64
import kotlin.js.JsString

/**
 * Web [AudioPicker]: opens a hidden file input accepting any audio MIME type, reads the chosen file
 * as a `data:` URL via `FileReader`, then splits out the MIME type and decodes the base64 payload
 * back to raw bytes so the shared clone-a-voice dialog can enroll it exactly like the Android build.
 */
@Composable
actual fun rememberAudioPicker(): AudioPicker = remember {
    object : AudioPicker {
        override fun pick(onPicked: (PickedAudio) -> Unit) {
            jsPickAudio { dataUrl ->
                val url = dataUrl.toString()
                val comma = url.indexOf(',')
                if (comma <= 0) return@jsPickAudio
                val header = url.substring(0, comma)
                val base64 = url.substring(comma + 1)
                val mime = Regex("data:([^;]+)").find(header)?.groupValues?.getOrNull(1)
                    ?.ifBlank { null } ?: "audio/*"
                val bytes = base64.fromBase64()
                if (bytes.isNotEmpty()) onPicked(PickedAudio(bytes, mime))
            }
        }
    }
}

/**
 * Creates a transient file input, and once a file is chosen reads it as a `data:` URL, handing the
 * whole URL string to [onPicked]. Never invoked if the chooser is dismissed without a selection.
 */
private fun jsPickAudio(onPicked: (JsString) -> Unit) {
    js(
        """(function() {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = 'audio/*';
            input.onchange = function(e) {
                var file = e.target.files && e.target.files[0];
                if (!file) return;
                var reader = new FileReader();
                reader.onload = function() { onPicked(String(reader.result || '')); };
                reader.readAsDataURL(file);
            };
            input.click();
        })()""",
    )
}
