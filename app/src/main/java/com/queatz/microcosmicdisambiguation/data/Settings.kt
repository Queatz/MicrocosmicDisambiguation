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

    /** The language the learner already knows (used for glosses and dictation), chosen in settings. */
    var sourceLanguage: Language
        get() = Language.fromName(
            prefs.getString(KEY_SOURCE_LANGUAGE, null),
            LanguagePair.Default.source,
        )
        set(value) {
            prefs.edit().putString(KEY_SOURCE_LANGUAGE, value.name).apply()
        }

    /** The language the learner is studying, chosen from the home screen. */
    var targetLanguage: Language
        get() = Language.fromName(
            prefs.getString(KEY_TARGET_LANGUAGE, null),
            LanguagePair.Default.target,
        )
        set(value) {
            prefs.edit().putString(KEY_TARGET_LANGUAGE, value.name).apply()
        }

    /** The current [LanguagePair] formed by [sourceLanguage] and [targetLanguage]. */
    var languagePair: LanguagePair
        get() = LanguagePair(source = sourceLanguage, target = targetLanguage)
        set(value) {
            sourceLanguage = value.source
            targetLanguage = value.target
        }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_TTS_MODE = "tts_mode"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
    }
}
