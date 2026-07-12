package com.queatz.microcosmicdisambiguation.audio

/**
 * Plays short audio clips: either raw captured PCM (to replay the learner's own recording) or a
 * stored audio reference (a file path on Android, a blob URL on the web).
 *
 * Android is backed by [android.media.MediaPlayer]; the web by an HTML `Audio` element. Only this
 * contract is used by the shared UI, so playback works identically from the screens' point of view.
 */
interface AudioClipPlayer {

    /** Plays 16 kHz mono 16-bit [pcm] audio, invoking [onComplete] when it finishes. */
    fun playPcm(pcm: ByteArray, onComplete: () -> Unit = {})

    /** Plays audio identified by a platform [ref] (a file path on Android, a blob URL on the web). */
    fun playRef(ref: String)

    /** Stops any current playback. */
    fun stop()

    /** Releases any resources held by the player. */
    fun release()
}
