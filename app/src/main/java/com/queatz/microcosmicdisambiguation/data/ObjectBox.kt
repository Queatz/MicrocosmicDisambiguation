package com.queatz.microcosmicdisambiguation.data

import android.content.Context
import io.objectbox.BoxStore

/**
 * Holds the single application-wide [BoxStore]. ObjectBox only permits one open store per database
 * directory, so every repository must share this instance rather than building its own.
 */
object ObjectBox {

    @Volatile
    private var boxStore: BoxStore? = null

    /** Lazily builds (or returns) the shared [BoxStore]. */
    fun store(context: Context): BoxStore =
        boxStore ?: synchronized(this) {
            boxStore ?: MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .build()
                .also { boxStore = it }
        }
}
