package com.queatz.microcosmicdisambiguation.data

import android.content.Context

/**
 * The text-to-speech engine the app uses to read sentences aloud.
 */
enum class TtsMode {
    /** The Qwen (Alibaba DashScope) cloud TTS, which generates audio with word boundaries. */
    Ai,

    /** The on-device Android text-to-speech engine. */
    System,
}

/**
 * Lightweight persistence for user settings, backed by [android.content.SharedPreferences].
 */
class SettingsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The currently selected text-to-speech engine. Defaults to [TtsMode.System]. */
    var ttsMode: TtsMode
        get() = runCatching {
            TtsMode.valueOf(prefs.getString(KEY_TTS_MODE, TtsMode.System.name)!!)
        }.getOrDefault(TtsMode.System)
        set(value) {
            prefs.edit().putString(KEY_TTS_MODE, value.name).apply()
        }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_TTS_MODE = "tts_mode"
    }
}
