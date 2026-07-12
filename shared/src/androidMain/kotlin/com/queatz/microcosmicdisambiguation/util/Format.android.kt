package com.queatz.microcosmicdisambiguation.util

import java.text.DateFormat
import java.util.Date

actual fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
