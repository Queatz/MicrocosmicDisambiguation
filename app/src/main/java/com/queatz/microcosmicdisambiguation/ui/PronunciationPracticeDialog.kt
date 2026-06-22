package com.queatz.microcosmicdisambiguation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.queatz.microcosmicdisambiguation.R
import com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.audio.Wav
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.PronunciationFeedback
import com.queatz.microcosmicdisambiguation.data.comparePronunciation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Colour code for a correctly pronounced unit. */
private val CorrectColor = Color(0xFF2E7D32)

/** Colour code for a mispronounced or missing unit. */
private val IncorrectColor = Color(0xFFC62828)

/**
 * The minimum accuracy at which the recording auto-ends: a full match (every target unit
 * pronounced correctly), so practice never stops on a near-miss.
 */
private const val AUTO_STOP_THRESHOLD = 1.0

/** The distinct stages of a single pronunciation-practice attempt. */
private enum class PracticePhase { Idle, Listening, Processing, Result, Error }

/**
 * A modal that lets the learner practice pronouncing [target] (a whole sentence or a single word):
 * they record themselves, the audio is transcribed by [QwenSpeechRecognizer], and the transcription
 * is aligned to [target] with [comparePronunciation] to colour-code which units were pronounced
 * correctly (green) or not (red). A single "Play comparison" button plays the native-like TTS
 * rendition followed by the learner's own recording for a side-by-side listen.
 *
 * @param target the sentence or word to practice, in the session's [language]
 * @param language the language being studied, used to bias recognition and split units correctly
 * @param speaker the text-to-speech engine that voices the native-like reference
 * @param onDismiss invoked when the modal should close
 */
@Composable
fun PronunciationPracticeDialog(
    target: String,
    language: Language,
    speaker: TtsEngine,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A recognizer biased toward the language being studied, so it transcribes what the learner is
    // attempting rather than the language they already know.
    val recognizer = remember(language) {
        QwenSpeechRecognizer(languageHints = listOf(language.asrCode))
    }
    val player = remember { MediaPlayer() }

    var phase by remember { mutableStateOf(PracticePhase.Idle) }
    var transcript by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<PronunciationFeedback?>(null) }
    var recordedAudio by remember { mutableStateOf<ByteArray?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPlayingComparison by remember { mutableStateOf(false) }
    // Set the moment the live transcript matches the target perfectly, so the UI can briefly
    // confirm the success that ended the recording on its own.
    var autoStopped by remember { mutableStateOf(false) }

    DisposableEffect(recognizer) {
        recognizer.onListeningChanged = { listening ->
            if (listening) {
                phase = PracticePhase.Listening
            } else if (phase == PracticePhase.Listening) {
                // The mic stopped; wait for the transcription/audio to arrive.
                phase = PracticePhase.Processing
            }
        }
        recognizer.onTextChanged = { live ->
            transcript = live
            // Auto-end the recording the instant the learner has said every unit correctly, so
            // they don't have to tap stop themselves. Requiring a full match avoids stopping on a
            // near-miss while the transcript is still being refined.
            if (phase == PracticePhase.Listening) {
                val partial = comparePronunciation(target, live, language.characterSegmented)
                if (partial.score >= AUTO_STOP_THRESHOLD) {
                    autoStopped = true
                    recognizer.stop()
                }
            }
        }
        recognizer.onError = { message ->
            errorMessage = message
            phase = PracticePhase.Error
        }
        recognizer.onSessionComplete = { audio, finalTranscript ->
            recordedAudio = audio
            transcript = finalTranscript
            feedback = comparePronunciation(target, finalTranscript, language.characterSegmented)
            phase = PracticePhase.Result
        }
        onDispose {
            recognizer.onListeningChanged = null
            recognizer.onTextChanged = null
            recognizer.onError = null
            recognizer.onSessionComplete = null
            recognizer.shutdown()
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching { player.reset() }
            player.release()
        }
    }

    // The recognizer reports neither a completion nor an error when it hears only silence (the
    // transcript stays blank), so guard the processing stage with a timeout that surfaces a gentle
    // "didn't catch that" notice and offers a retry.
    LaunchedEffect(phase) {
        if (phase == PracticePhase.Processing) {
            delay(6_000)
            if (phase == PracticePhase.Processing) {
                errorMessage = context.getString(R.string.study_pronounce_no_speech)
                phase = PracticePhase.Error
            }
        }
    }

    fun stopPlayback() {
        isPlayingComparison = false
        // Interrupt the native-like TTS reference (per TtsEngine.speak's contract) and halt the
        // learner's own recording if either is currently playing.
        runCatching { speaker.speak("") }
        runCatching { player.reset() }
    }

    fun beginListening() {
        stopPlayback()
        transcript = ""
        feedback = null
        recordedAudio = null
        errorMessage = null
        autoStopped = false
        recognizer.start()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginListening()
        } else {
            errorMessage = context.getString(R.string.study_pronounce_permission_needed)
            phase = PracticePhase.Error
        }
    }

    fun startRecording() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun playComparison() {
        val audio = recordedAudio ?: return
        if (isPlayingComparison) return
        isPlayingComparison = true
        // Play the native-like reference first, then the learner's own recording right after.
        speaker.speak(
            text = target,
            onDone = {
                scope.launch {
                    delay(500)
                    // The comparison may have been cancelled (e.g. retry pressed) while the
                    // reference was still playing; don't start the recording in that case.
                    if (!isPlayingComparison) return@launch
                    val played = runCatching {
                        val wav = Wav.pcmToWav(audio)
                        val file = File(context.cacheDir, "pronunciation_practice.wav")
                        file.writeBytes(wav)
                        player.reset()
                        player.setDataSource(file.path)
                        player.setOnCompletionListener { isPlayingComparison = false }
                        player.setOnPreparedListener { it.start() }
                        player.prepareAsync()
                    }.isSuccess
                    if (!played) isPlayingComparison = false
                }
            },
        )
    }

    Dialog(onDismissRequest = { if (!recognizer.isListening) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.study_pronounce_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                // The target sentence/word — colour coded once feedback is available, plain before.
                val currentFeedback = feedback
                if (currentFeedback != null) {
                    FeedbackTokens(
                        feedback = currentFeedback,
                        characterSegmented = language.characterSegmented,
                    )
                } else {
                    Text(
                        text = target,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        // Flash the target green the moment a perfect reading auto-stops the
                        // recording, so the learner sees why it ended before the full result lands.
                        color = if (autoStopped) CorrectColor else Color.Unspecified,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                when (phase) {
                    PracticePhase.Idle -> {
                        Button(
                            onClick = { startRecording() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(stringResource(R.string.study_pronounce_start))
                        }
                    }

                    PracticePhase.Listening -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.study_pronounce_listening),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Button(
                            onClick = { recognizer.stop() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(stringResource(R.string.study_pronounce_stop))
                        }
                    }

                    PracticePhase.Processing -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.study_pronounce_processing),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    PracticePhase.Result -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.study_pronounce_you_said),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = transcript.ifBlank {
                                    stringResource(R.string.study_pronounce_nothing_heard)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        // Automatically play the comparison once the result appears.
                        LaunchedEffect(currentFeedback, recordedAudio) {
                            if (recordedAudio != null) {
                                playComparison()
                            }
                        }
                        currentFeedback?.let { result ->
                            Text(
                                text = stringResource(
                                    R.string.study_pronounce_accuracy,
                                    (result.score * 100).roundToInt(),
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (result.score >= 0.85) CorrectColor else IncorrectColor,
                            )
                        }
                        Button(
                            onClick = { playComparison() },
                            enabled = recordedAudio != null && !isPlayingComparison,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(stringResource(R.string.study_pronounce_play_comparison))
                        }
                        OutlinedButton(
                            onClick = { startRecording() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.study_pronounce_retry))
                        }
                    }

                    PracticePhase.Error -> {
                        Text(
                            text = errorMessage
                                ?: stringResource(R.string.study_pronounce_no_speech),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { startRecording() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.study_pronounce_retry))
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

/**
 * Renders the target broken into colour-coded units: green for correct, red for incorrect.
 *
 * The units are laid out as a single flowing, wrapping line so they read as natural text. For
 * word-based languages (e.g. Vietnamese, English) the units are joined with ordinary spaces; for
 * [characterSegmented] languages (e.g. Chinese, Japanese), which are written without spaces between
 * words, the characters are placed directly next to one another.
 */
@Composable
private fun FeedbackTokens(feedback: PronunciationFeedback, characterSegmented: Boolean) {
    val text = buildAnnotatedString {
        feedback.tokens.forEachIndexed { index, token ->
            if (index > 0 && !characterSegmented) {
                append(" ")
            }
            withStyle(SpanStyle(color = if (token.correct) CorrectColor else IncorrectColor)) {
                append(token.text)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
