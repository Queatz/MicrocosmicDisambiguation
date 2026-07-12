package com.queatz.microcosmicdisambiguation.util

/** Formats the timestamp using the browser's locale-aware `Date.toLocaleString()`. */
actual fun formatTimestamp(epochMillis: Long): String = jsFormatTimestamp(epochMillis.toDouble())

private fun jsFormatTimestamp(millis: Double): String =
    js("new Date(millis).toLocaleString()")
