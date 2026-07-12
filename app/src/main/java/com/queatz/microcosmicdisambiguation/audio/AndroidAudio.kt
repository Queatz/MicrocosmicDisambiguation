package com.queatz.microcosmicdisambiguation.audio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.TtsMode
import java.io.File

/**
 * The Android [AudioFactory]: builds the on-device / cloud text-to-speech engines, the streaming
 * Qwen speech recognizer, and a [MediaPlayer]-backed clip player. This is the only place the shared
 * UI's audio needs touch Android APIs.
 */
class AndroidAudioFactory(context: Context) : AudioFactory {

    private val appContext = context.applicationContext

    // Android can prompt the user to install/adjust voice data via the system TTS flow.
    override val canManageVoices: Boolean = true

    override fun createSpeaker(
        mode: TtsMode,
        language: Language,
        qwenService: QwenService,
    ): TtsEngine = when (mode) {
        TtsMode.Ai -> QwenSpeaker(appContext, qwenService, language)
        TtsMode.System -> Speaker(appContext, language)
    }

    override fun createSpeechRecognizer(languageHints: List<String>): SpeechRecognizer =
        QwenSpeechRecognizer(languageHints = languageHints)

    override fun createClipPlayer(): AudioClipPlayer = AndroidAudioClipPlayer(appContext)

    override fun manageVoices(missingData: Boolean) {
        val intent = if (missingData) {
            Speaker.installTtsDataIntent()
        } else {
            Intent("com.android.settings.TTS_SETTINGS")
        }
        // Launched from the application context, so a new task is required.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No activity available to handle the request; nothing else we can do.
        }
    }
}

/**
 * An [AudioClipPlayer] backed by a single [MediaPlayer]. Raw PCM is wrapped in a WAV container and
 * written to the cache so [MediaPlayer] can play it; stored references are played by path.
 */
class AndroidAudioClipPlayer(context: Context) : AudioClipPlayer {

    private val appContext = context.applicationContext
    private val player = MediaPlayer()

    override fun playPcm(pcm: ByteArray, onComplete: () -> Unit) {
        val played = runCatching {
            val wav = Wav.pcmToWav(pcm)
            val file = File(appContext.cacheDir, "clip_playback.wav")
            file.writeBytes(wav)
            player.reset()
            player.setDataSource(file.path)
            player.setOnCompletionListener { onComplete() }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        }.isSuccess
        if (!played) onComplete()
    }

    override fun playRef(ref: String) {
        runCatching {
            player.reset()
            player.setDataSource(ref)
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        }
    }

    override fun stop() {
        runCatching { player.reset() }
    }

    override fun release() {
        runCatching { player.reset() }
        player.release()
    }
}
