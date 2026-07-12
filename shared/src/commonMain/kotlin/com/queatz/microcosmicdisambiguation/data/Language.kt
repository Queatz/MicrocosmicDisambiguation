package com.queatz.microcosmicdisambiguation.data

/**
 * A language the app can teach to or from. Every learning session has a [LanguagePair]: the
 * [LanguagePair.source] is the language the learner already knows (used for the on-screen glosses
 * and for dictating the IRL situation) and the [LanguagePair.target] is the language being learned
 * (the sentences, words, and spoken audio).
 *
 * Each entry carries the metadata the rest of the app needs to adapt to that language:
 *
 * @param displayName the human-readable name shown in the UI
 * @param flag a flag emoji used as a compact visual label
 * @param localeTag the BCP-47 tag used to configure the on-device [android.speech.tts.TextToSpeech]
 *   engine. There is no per-language cloud TTS voice to configure here: CosyVoice detects the
 *   spoken language directly from the text itself (it supports 31 languages), and the single cloud
 *   voice used for every language is resolved once by
 *   [com.queatz.microcosmicdisambiguation.ai.QwenService] rather than stored per [Language].
 * @param asrCode the `language` hint passed to the Qwen real-time speech recognizer when the
 *   learner dictates a situation in this language
 * @param scriptDescription a short description of the writing system, injected into the AI prompt so
 *   the generated study guide uses the correct script
 * @param usesRomanization true when a separate romanization line is meaningful for this language
 *   (e.g. pinyin, romaji); false for languages already written in the Latin alphabet
 * @param characterSegmented true when the language is written without spaces between words (so
 *   playback highlighting must advance character-by-character) rather than whitespace-delimited
 */
enum class Language(
    val displayName: String,
    val flag: String,
    val localeTag: String,
    val asrCode: String,
    val scriptDescription: String,
    val usesRomanization: Boolean,
    val characterSegmented: Boolean,
) {
    English(
        displayName = "English",
        flag = "\uD83C\uDDEC\uD83C\uDDE7",
        localeTag = "en-US",
        asrCode = "en",
        scriptDescription = "the Latin alphabet",
        usesRomanization = false,
        characterSegmented = false,
    ),
    Chinese(
        displayName = "Chinese",
        flag = "\uD83C\uDDE8\uD83C\uDDF3",
        localeTag = "zh-CN",
        asrCode = "zh",
        scriptDescription = "simplified Chinese characters",
        usesRomanization = true,
        characterSegmented = true,
    ),
    Vietnamese(
        displayName = "Vietnamese",
        flag = "\uD83C\uDDFB\uD83C\uDDF3",
        localeTag = "vi-VN",
        asrCode = "vi",
        scriptDescription = "the Vietnamese Latin alphabet with full diacritics",
        usesRomanization = false,
        characterSegmented = false,
    ),
    Korean(
        displayName = "Korean",
        flag = "\uD83C\uDDF0\uD83C\uDDF7",
        localeTag = "ko-KR",
        asrCode = "ko",
        scriptDescription = "the Korean Hangul script",
        usesRomanization = true,
        characterSegmented = false,
    ),
    Japanese(
        displayName = "Japanese",
        flag = "\uD83C\uDDEF\uD83C\uDDF5",
        localeTag = "ja-JP",
        asrCode = "ja",
        scriptDescription = "natural Japanese script (kanji and kana)",
        usesRomanization = true,
        characterSegmented = true,
    ),
    Burmese(
        displayName = "Burmese",
        flag = "\uD83C\uDDF2\uD83C\uDDF2",
        localeTag = "my-MM",
        asrCode = "my",
        scriptDescription = "the Burmese (Myanmar) script",
        usesRomanization = true,
        characterSegmented = true,
    ),
    Russian(
        displayName = "Russian",
        flag = "\uD83C\uDDF7\uD83C\uDDFA",
        localeTag = "ru-RU",
        asrCode = "ru",
        scriptDescription = "the Russian Cyrillic alphabet",
        usesRomanization = true,
        characterSegmented = false,
    );

    companion object {
        /** Resolves a stored [name] back to a [Language], falling back to [default] if unknown. */
        fun fromName(name: String?, default: Language): Language =
            entries.firstOrNull { it.name == name } ?: default
    }
}

/**
 * The pair of languages a study session uses: the [source] the learner already knows and the
 * [target] they are learning. A pair is only meaningful when the two differ.
 */
data class LanguagePair(
    val source: Language,
    val target: Language,
) {
    /** Returns this pair with [source] and [target] swapped. */
    fun swapped(): LanguagePair = LanguagePair(source = target, target = source)

    companion object {
        /** The historical default the app shipped with: learning Chinese from English. */
        val Default = LanguagePair(source = Language.English, target = Language.Chinese)
    }
}
