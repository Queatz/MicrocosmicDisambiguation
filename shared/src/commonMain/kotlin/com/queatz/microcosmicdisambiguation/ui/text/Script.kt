package com.queatz.microcosmicdisambiguation.ui.text

/**
 * A Unicode writing system the app might need to render that is NOT covered by Skia's bundled
 * default font (Latin/Cyrillic/Greek) or by the separately preloaded emoji font (see
 * `WithEmojiFallback`). Each entry maps a set of Unicode code point blocks (from the Unicode
 * Character Database) to the "Noto Sans <X>" family Google publishes for that script — the same
 * family name is used both as the [androidx.compose.ui.text.font.Font] identity when preloading it
 * and to build the download URL in `WithScriptFallback`.
 *
 * This table is intentionally generic: it is keyed by Unicode script, not by [Language]. That way,
 * any language written in a covered script "just works" — including languages added to the app in
 * the future — without ever having to hand-wire a new font per language.
 */
enum class Script(val codePointRanges: List<IntRange>, val notoFamilyName: String) {
    Myanmar(listOf(0x1000..0x109F, 0xA9E0..0xA9FF, 0xAA60..0xAA7F), "Noto Sans Myanmar"),
    Thai(listOf(0x0E00..0x0E7F), "Noto Sans Thai"),
    Lao(listOf(0x0E80..0x0EFF), "Noto Sans Lao"),
    Khmer(listOf(0x1780..0x17FF), "Noto Sans Khmer"),
    Devanagari(listOf(0x0900..0x097F), "Noto Sans Devanagari"),
    Bengali(listOf(0x0980..0x09FF), "Noto Sans Bengali"),
    Gurmukhi(listOf(0x0A00..0x0A7F), "Noto Sans Gurmukhi"),
    Gujarati(listOf(0x0A80..0x0AFF), "Noto Sans Gujarati"),
    Oriya(listOf(0x0B00..0x0B7F), "Noto Sans Oriya"),
    Tamil(listOf(0x0B80..0x0BFF), "Noto Sans Tamil"),
    Telugu(listOf(0x0C00..0x0C7F), "Noto Sans Telugu"),
    Kannada(listOf(0x0C80..0x0CFF), "Noto Sans Kannada"),
    Malayalam(listOf(0x0D00..0x0D7F), "Noto Sans Malayalam"),
    Sinhala(listOf(0x0D80..0x0DFF), "Noto Sans Sinhala"),
    Tibetan(listOf(0x0F00..0x0FFF), "Noto Sans Tibetan"),
    Georgian(listOf(0x10A0..0x10FF), "Noto Sans Georgian"),
    Armenian(listOf(0x0530..0x058F), "Noto Sans Armenian"),
    Ethiopic(listOf(0x1200..0x137F), "Noto Sans Ethiopic"),
    Cherokee(listOf(0x13A0..0x13FF), "Noto Sans Cherokee"),
    CanadianAboriginal(listOf(0x1400..0x167F), "Noto Sans Canadian Aboriginal"),
    Hebrew(listOf(0x0590..0x05FF), "Noto Sans Hebrew"),
    Arabic(listOf(0x0600..0x06FF, 0x0750..0x077F, 0xFB50..0xFDFF, 0xFE70..0xFEFF), "Noto Sans Arabic"),
    Thaana(listOf(0x0780..0x07BF), "Noto Sans Thaana"),
    Mongolian(listOf(0x1800..0x18AF), "Noto Sans Mongolian"),

    // The CJK scripts ship as much larger combined fonts (Noto Sans CJK), so several [Script]
    // entries below intentionally resolve to the same download in `ScriptFonts.fontUrl`.
    Hangul(listOf(0xAC00..0xD7A3, 0x1100..0x11FF, 0x3130..0x318F), "Noto Sans KR"),
    Hiragana(listOf(0x3040..0x309F), "Noto Sans JP"),
    Katakana(listOf(0x30A0..0x30FF), "Noto Sans JP"),
    Han(listOf(0x4E00..0x9FFF, 0x3400..0x4DBF, 0xF900..0xFAFF), "Noto Sans SC"),
    ;

    /** True if [codePoint] falls inside one of this script's Unicode blocks. */
    private fun contains(codePoint: Int): Boolean = codePointRanges.any { codePoint in it }

    companion object {
        /**
         * Resolves the [Script] [codePoint] belongs to, or null when it's Latin, Cyrillic, Greek,
         * a digit, punctuation, an emoji, or any other code point already covered by the bundled
         * default font or the preloaded emoji font — i.e. nothing extra needs to be loaded for it.
         */
        fun of(codePoint: Int): Script? = entries.firstOrNull { it.contains(codePoint) }
    }
}

/**
 * Scans this string for [Script]s that need an extra fallback font before they can render, and
 * returns the distinct set found. Safe to call on arbitrary, mixed-script text — anything outside
 * the mapped ranges is simply skipped.
 *
 * All mapped ranges lie in the Basic Multilingual Plane (code points `<= 0xFFFF`), so this walks
 * UTF-16 code units directly rather than full Unicode code points (no surrogate-pair handling
 * needed, keeping this pure `kotlin.text` — no platform APIs required).
 */
fun String.detectScripts(): Set<Script> {
    if (isEmpty()) return emptySet()
    val found = mutableSetOf<Script>()
    for (char in this) {
        Script.of(char.code)?.let(found::add)
    }
    return found
}
