package com.queatz.microcosmicdisambiguation.ui.text

/**
 * Resolves where to download a raw, directly loadable (TrueType/OpenType, not WOFF/WOFF2) font
 * file for [this] script from.
 *
 * Compose Web's `FontFamily.Resolver.preload` needs raw sfnt bytes (see how `WithEmojiFallback`
 * loads `noto_color_emoji.ttf`); the Google Fonts CSS API instead serves WOFF2 to modern browsers,
 * and browser `fetch`/`XMLHttpRequest` cannot override the `User-Agent` header to ask for anything
 * else. The jsDelivr GitHub CDN mirrors of the upstream Noto repositories serve the plain `.ttf`/
 * `.otf` files directly regardless of caller, so it's used here instead.
 */
fun Script.fontUrl(): String = when (this) {
    Script.Han -> cjkFontUrl(folder = "SimplifiedChinese", suffix = "sc")
    Script.Hangul -> cjkFontUrl(folder = "Korean", suffix = "kr")
    Script.Hiragana, Script.Katakana -> cjkFontUrl(folder = "Japanese", suffix = "jp")
    else -> {
        val slug = notoFamilyName.replace(" ", "")
        "https://cdn.jsdelivr.net/gh/googlefonts/noto-fonts/hinted/ttf/$slug/$slug-Regular.ttf"
    }
}

/**
 * The CJK scripts aren't published under `googlefonts/noto-fonts`; they ship as much larger,
 * combined-coverage fonts from the dedicated `googlefonts/noto-cjk` repository, one per region.
 */
private fun cjkFontUrl(folder: String, suffix: String): String =
    "https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk/Sans/OTF/$folder/NotoSansCJK$suffix-Regular.otf"
