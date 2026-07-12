package com.queatz.microcosmicdisambiguation.util

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/**
 * Formats [value] with exactly one decimal place (e.g. `1.2`) without relying on any JVM-only
 * `String.format`, so it works identically on Android and Wasm.
 */
fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10).roundToLong()
    val whole = scaled / 10
    val frac = (scaled % 10).absoluteValue
    return "$whole.$frac"
}

/**
 * Formats an epoch-millisecond timestamp as a locale-aware date+time string. Android uses
 * `java.text.DateFormat`; the web uses the browser's `Date.toLocaleString()`.
 */
expect fun formatTimestamp(epochMillis: Long): String
