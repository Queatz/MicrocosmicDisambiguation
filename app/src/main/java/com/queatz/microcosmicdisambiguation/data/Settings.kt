package com.queatz.microcosmicdisambiguation.data

import android.content.Context

/**
 * Lightweight persistence for user settings, backed by [android.content.SharedPreferences].
 *
 * [TtsMode] and the language types now live in the shared multiplatform module
 * ([com.queatz.microcosmicdisambiguation.data.TtsMode]); this class remains Android-only because it
 * is backed by [android.content.SharedPreferences].
 */
class SettingsRepository(context: Context) : SettingsStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The currently selected text-to-speech engine. Defaults to [TtsMode.System]. */
    override var ttsMode: TtsMode
        get() = runCatching {
            TtsMode.valueOf(prefs.getString(KEY_TTS_MODE, TtsMode.System.name)!!)
        }.getOrDefault(TtsMode.System)
        set(value) {
            prefs.edit().putString(KEY_TTS_MODE, value.name).apply()
        }

    /** The language the learner already knows (used for glosses and dictation), chosen in settings. */
    override var sourceLanguage: Language
        get() = Language.fromName(
            prefs.getString(KEY_SOURCE_LANGUAGE, null),
            LanguagePair.Default.source,
        )
        set(value) {
            prefs.edit().putString(KEY_SOURCE_LANGUAGE, value.name).apply()
        }

    /** The language the learner is studying, chosen from the home screen. */
    override var targetLanguage: Language
        get() = Language.fromName(
            prefs.getString(KEY_TARGET_LANGUAGE, null),
            LanguagePair.Default.target,
        )
        set(value) {
            prefs.edit().putString(KEY_TARGET_LANGUAGE, value.name).apply()
        }

    /** Whether the first-time welcome banner on the home screen has already been dismissed. */
    override var hasDismissedWelcomeBanner: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_BANNER_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WELCOME_BANNER_DISMISSED, value).apply()
        }

    /** The cached CosyVoice "voice design" voice id, or null if none has been created yet. */
    override var cosyVoiceId: String?
        get() = prefs.getString(KEY_COSY_VOICE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_COSY_VOICE_ID, value).apply()
        }

    /** The learner's enrolled cloned voices, serialized as a JSON array. Defaults to an empty array. */
    override var clonedVoicesJson: String
        get() = prefs.getString(KEY_CLONED_VOICES, "[]") ?: "[]"
        set(value) {
            prefs.edit().putString(KEY_CLONED_VOICES, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_TTS_MODE = "tts_mode"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_WELCOME_BANNER_DISMISSED = "welcome_banner_dismissed"
        private const val KEY_COSY_VOICE_ID = "cosy_voice_id"
        private const val KEY_CLONED_VOICES = "cloned_voices"
    }
}
