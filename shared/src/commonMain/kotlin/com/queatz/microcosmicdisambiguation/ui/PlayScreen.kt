package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.queatz.microcosmicdisambiguation.LocalAppContainer
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.SentenceStructure
import com.queatz.microcosmicdisambiguation.data.StudyWord
import com.queatz.microcosmicdisambiguation.data.coinPositions
import com.queatz.microcosmicdisambiguation.data.coinSeed
import com.queatz.microcosmicdisambiguation.data.isWordSpoken
import com.queatz.microcosmicdisambiguation.data.playCoinWords
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.back
import com.queatz.microcosmicdisambiguation.resources.play_all_done
import com.queatz.microcosmicdisambiguation.resources.play_checking
import com.queatz.microcosmicdisambiguation.resources.play_continue
import com.queatz.microcosmicdisambiguation.resources.play_game_hint
import com.queatz.microcosmicdisambiguation.resources.play_intro_hint
import com.queatz.microcosmicdisambiguation.resources.play_lets_play
import com.queatz.microcosmicdisambiguation.resources.play_listen
import com.queatz.microcosmicdisambiguation.resources.play_meanings_hint
import com.queatz.microcosmicdisambiguation.resources.play_no_speech
import com.queatz.microcosmicdisambiguation.resources.play_permission_needed
import com.queatz.microcosmicdisambiguation.resources.play_progress
import com.queatz.microcosmicdisambiguation.resources.play_round
import com.queatz.microcosmicdisambiguation.resources.play_say_word
import com.queatz.microcosmicdisambiguation.resources.play_say_word_meaning
import com.queatz.microcosmicdisambiguation.resources.play_skip
import com.queatz.microcosmicdisambiguation.resources.play_try_again
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sin

/** The overall stages of a play round. */
private enum class PlayPhase {
    /** Coins float in; tapping a coin flips it to peek at the meaning. */
    Intro,

    /** The game: each coin must be tapped and its word spoken correctly. */
    Playing,

    /**
     * The second game: every coin shows its translation and the learner must recall and speak the
     * word (not the translation) to flip each coin back over.
     */
    Meanings,

    /** Every coin has been spoken in both the speaking and meanings phases. */
    Done,
}

/** The state of the audio-input frame while the learner speaks a coin's word. */
private enum class SpeakState {
    /** The app is reading the word aloud; recognition has not started yet. */
    Speaking,

    /** The mic is live and listening for the learner's attempt. */
    Listening,

    /** The mic has stopped; waiting for the final transcription. */
    Checking,

    /** The attempt didn't match; the learner can retry. */
    TryAgain,

    /** Something went wrong (no speech heard, permission denied, …). */
    Error,
}

/** How long a peeked coin stays flipped before flipping itself back. */
private const val PEEK_MS = 2_000L

/**
 * The pre-study "Play" screen for one sentence structure: the structure's words float up as 3D
 * coins showing a big emoji and the word. In the intro the learner can tap coins to flip them and
 * peek at the meaning (they flip back after 2 seconds). "Let's play" starts the game: tapping a
 * coin opens an audio-input frame and the learner must say that word out loud; a correctly spoken
 * word locks its coin flipped. Once every coin is flipped the meanings phase begins: all the coins
 * now show their translations and the learner must recall and speak each word again (without being
 * shown or told it) to flip its coin back over. Once every coin is flipped back the bottom button
 * becomes "Continue".
 *
 * @param title the study session title
 * @param structure the sentence structure whose words are played
 * @param roundNumber this play round's 1-based position within the session
 * @param roundCount total number of play rounds (structures) in the session
 * @param language the language being studied (biases recognition, drives unit matching)
 * @param speaker reads words aloud when coins are tapped
 * @param canSkip true when this word group's play round was already completed once, unlocking Skip
 * @param onClose leaves the study flow entirely
 * @param onCompleted fired once when both phases are complete: every coin spoken correctly, then
 *   every meaning recalled (persist "played")
 * @param onContinue proceeds to the study screen (via Continue after completing, or Skip)
 */
@Composable
fun PlayScreen(
    title: String,
    structure: SentenceStructure,
    roundNumber: Int,
    roundCount: Int,
    language: Language,
    speaker: TtsEngine,
    canSkip: Boolean,
    onClose: () -> Unit,
    onCompleted: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = remember(structure) { playCoinWords(structure) }
    val positions = remember(structure) { coinPositions(words.size, coinSeed(structure)) }

    // A structure with no words has nothing to play; move straight on to studying it.
    if (words.isEmpty()) {
        LaunchedEffect(structure) { onContinue() }
        return
    }

    val audioFactory = LocalAppContainer.current.audio
    val micPermission = rememberMicPermission()
    // Messages read inside recognizer callbacks (outside composition) are captured up front.
    val noSpeechMessage = stringResource(Res.string.play_no_speech)
    val permissionNeededMessage = stringResource(Res.string.play_permission_needed)

    // A recognizer biased toward the language being studied, so it hears the learner's attempt at
    // the coin's word rather than the language they already know.
    val recognizer = remember(language) {
        audioFactory.createSpeechRecognizer(languageHints = listOf(language.asrCode))
    }

    var phase by remember(structure) { mutableStateOf(PlayPhase.Intro) }
    // Coins flipped for a 2-second peek during the intro (index -> flip generation counter).
    val peeked = remember(structure) { mutableStateMapOf<Int, Long>() }
    var peekGeneration by remember(structure) { mutableStateOf(0L) }
    // Coins whose word has been spoken correctly; they stay flipped for good.
    val spoken = remember(structure) { mutableStateMapOf<Int, Boolean>() }
    // Coins whose word was recalled from its meaning (meanings phase); they flip back for good.
    val recalled = remember(structure) { mutableStateMapOf<Int, Boolean>() }
    // The coin currently being spoken (game mode), or null when no audio frame is open.
    var activeIndex by remember(structure) { mutableStateOf<Int?>(null) }
    var speakState by remember(structure) { mutableStateOf(SpeakState.Listening) }
    var transcript by remember(structure) { mutableStateOf("") }
    var errorMessage by remember(structure) { mutableStateOf<String?>(null) }
    // Bumped every time a coin is tapped so a word we finish reading only starts the mic if the
    // learner hasn't already moved on to another coin.
    var speakGeneration by remember(structure) { mutableStateOf(0L) }

    // The recognizer callbacks are installed once but must see the latest target coin.
    val currentActive = rememberUpdatedState(activeIndex)
    val currentWords = rememberUpdatedState(words)
    val currentLanguage = rememberUpdatedState(language)

    // Whether this coin's word has already been judged correct in the current phase.
    fun isCorrect(index: Int): Boolean = when (phase) {
        PlayPhase.Meanings -> recalled[index] == true
        else -> spoken[index] == true
    }

    fun markCorrect(index: Int) {
        if (isCorrect(index)) return
        when (phase) {
            PlayPhase.Meanings -> recalled[index] = true
            else -> spoken[index] = true
        }
        // Celebrate by reading the word back one more time.
        words.getOrNull(index)?.let { speaker.speak(it.hanzi) }
    }

    DisposableEffect(recognizer) {
        recognizer.onListeningChanged = { listening ->
            if (listening) {
                speakState = SpeakState.Listening
            } else if (speakState == SpeakState.Listening) {
                // The mic stopped; wait for the final transcription to arrive.
                speakState = SpeakState.Checking
            }
        }
        recognizer.onTextChanged = { live ->
            transcript = live
            val index = currentActive.value
            if (index != null && !isCorrect(index)) {
                val target = currentWords.value.getOrNull(index)
                // The instant the live transcript contains the whole word, it counts — stop listening.
                if (target != null &&
                    isWordSpoken(target.hanzi, live, currentLanguage.value.characterSegmented)
                ) {
                    markCorrect(index)
                    activeIndex = null
                    recognizer.stop()
                }
            }
        }
        recognizer.onError = { message ->
            errorMessage = message
            speakState = SpeakState.Error
        }
        recognizer.onSessionComplete = { _, finalTranscript ->
            transcript = finalTranscript
            val index = currentActive.value
            if (index != null && !isCorrect(index)) {
                val target = currentWords.value.getOrNull(index)
                if (target != null &&
                    isWordSpoken(target.hanzi, finalTranscript, currentLanguage.value.characterSegmented)
                ) {
                    markCorrect(index)
                    activeIndex = null
                } else {
                    speakState = SpeakState.TryAgain
                }
            }
        }
        onDispose {
            recognizer.onListeningChanged = null
            recognizer.onTextChanged = null
            recognizer.onError = null
            recognizer.onSessionComplete = null
            recognizer.shutdown()
        }
    }

    // The recognizer reports neither a completion nor an error when it hears only silence, so guard
    // the checking stage with a timeout that surfaces a gentle "didn't catch that" notice.
    LaunchedEffect(speakState, activeIndex) {
        if (speakState == SpeakState.Checking && activeIndex != null) {
            delay(6_000)
            if (speakState == SpeakState.Checking) {
                errorMessage = noSpeechMessage
                speakState = SpeakState.Error
            }
        }
    }

    fun beginListening() {
        transcript = ""
        errorMessage = null
        speakState = SpeakState.Listening
        recognizer.start()
    }

    fun speakCoin(index: Int) {
        if (isCorrect(index)) return
        activeIndex = index

        // Always read the word aloud first so the learner hears it, and only *then* start
        // listening — with the pulsing "recording" cue — so the mic doesn't pick up the app's own
        // voice, and the pulse itself signals "now it's your turn", not "get ready".
        val generation = ++speakGeneration
        if (recognizer.isListening) recognizer.stop()
        transcript = ""
        errorMessage = null
        speakState = SpeakState.Speaking
        micPermission.request(
            onGranted = {
                val word = words.getOrNull(index)
                if (word != null) {
                    // Speak the word first; only once the learner has heard it do we start
                    // listening for their attempt (from the onDone callback below), which is what
                    // flips speakState to Listening and turns on the pulse.
                    speaker.speak(
                        word.hanzi,
                        onDone = {
                            // Skip if the learner has since moved on to another coin.
                            if (speakGeneration == generation && currentActive.value == index) {
                                beginListening()
                            }
                        },
                    )
                } else {
                    // No word to read aloud (shouldn't normally happen); just listen.
                    beginListening()
                }
            },
            onDenied = {
                errorMessage = permissionNeededMessage
                speakState = SpeakState.Error
            },
        )
    }

    fun tapCoin(index: Int) {
        when (phase) {
            PlayPhase.Intro -> {
                peeked[index] = ++peekGeneration
                words.getOrNull(index)?.let { speaker.speak(it.hanzi) }
            }

            PlayPhase.Playing -> speakCoin(index)

            // A recalled coin is settled; replay its word. The rest still need to be recalled.
            PlayPhase.Meanings -> if (recalled[index] == true) {
                words.getOrNull(index)?.let { speaker.speak(it.hanzi) }
            } else {
                speakCoin(index)
            }

            PlayPhase.Done -> words.getOrNull(index)?.let { speaker.speak(it.hanzi) }
        }
    }

    // Speaking phase complete: every coin is flipped to its translation, so after a beat to let the
    // last pop land, the meanings phase begins on the already-flipped coins.
    val allSpoken = spoken.size == words.size && words.indices.all { spoken[it] == true }
    LaunchedEffect(allSpoken) {
        if (allSpoken && phase == PlayPhase.Playing) {
            activeIndex = null
            if (recognizer.isListening) recognizer.stop()
            delay(900)
            phase = PlayPhase.Meanings
        }
    }

    // Meanings phase complete: every meaning was recalled, so the whole play round is done.
    val allRecalled = recalled.size == words.size && words.indices.all { recalled[it] == true }
    LaunchedEffect(allRecalled) {
        if (allRecalled && phase == PlayPhase.Meanings) {
            activeIndex = null
            if (recognizer.isListening) recognizer.stop()
            phase = PlayPhase.Done
            onCompleted()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayHeader(
            title = title,
            roundNumber = roundNumber,
            roundCount = roundCount,
            onClose = onClose,
        )

        // The playfield the coins float around in.
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val fieldWidth = maxWidth
            val fieldHeight = maxHeight
            val coinSize = 108.dp

            words.forEachIndexed { index, word ->
                val position = positions[index]
                val isPeeked = phase == PlayPhase.Intro && peeked.containsKey(index)
                val isSpoken = spoken[index] == true
                val isRecalled = recalled[index] == true
                val flipped = when (phase) {
                    PlayPhase.Intro -> isPeeked
                    PlayPhase.Playing -> isSpoken
                    // In the meanings phase every coin starts flipped (translation showing) and
                    // flips back over once its word is recalled.
                    PlayPhase.Meanings -> !isRecalled
                    PlayPhase.Done -> false
                }

                // Flip a peeked coin back after 2 seconds (re-tapping restarts the timer).
                val peekKey = peeked[index]
                if (peekKey != null) {
                    LaunchedEffect(peekKey) {
                        delay(PEEK_MS)
                        peeked.remove(index)
                    }
                }

                WordCoin(
                    word = word,
                    index = index,
                    flipped = flipped,
                    spoken = when (phase) {
                        PlayPhase.Meanings, PlayPhase.Done -> isRecalled
                        else -> isSpoken
                    },
                    active = activeIndex == index,
                    size = coinSize,
                    x = (fieldWidth - coinSize) * position.x,
                    y = (fieldHeight - coinSize) * position.y,
                    fieldHeight = fieldHeight,
                    onTap = { tapCoin(index) },
                )
            }
        }

        // The audio-input frame, revealed above the bottom button while a coin is being spoken.
        AnimatedVisibility(
            visible = (phase == PlayPhase.Playing || phase == PlayPhase.Meanings) && activeIndex != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            val index = activeIndex
            val target = index?.let { words.getOrNull(it) }
            if (target != null) {
                SpeakFrame(
                    word = target,
                    meanings = phase == PlayPhase.Meanings,
                    state = speakState,
                    transcript = transcript,
                    errorMessage = errorMessage,
                    onRetry = { speakCoin(index) },
                )
            }
        }

        // The hint + primary action at the bottom.
        val correctCount = when (phase) {
            PlayPhase.Meanings -> words.indices.count { recalled[it] == true }
            else -> words.indices.count { spoken[it] == true }
        }
        Text(
            text = when (phase) {
                PlayPhase.Intro -> stringResource(Res.string.play_intro_hint)
                PlayPhase.Playing -> stringResource(Res.string.play_game_hint)
                PlayPhase.Meanings -> stringResource(Res.string.play_meanings_hint)
                PlayPhase.Done -> stringResource(Res.string.play_all_done)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (phase == PlayPhase.Done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (phase == PlayPhase.Done) FontWeight.Bold else null,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        when (phase) {
            PlayPhase.Intro -> {
                Button(
                    onClick = { phase = PlayPhase.Playing },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.play_lets_play))
                }
            }

            PlayPhase.Playing, PlayPhase.Meanings -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.play_progress, correctCount, words.size))
                }
            }

            PlayPhase.Done -> {
                val pulse by rememberInfiniteTransition().animateFloat(
                    initialValue = 1f,
                    targetValue = 1.04f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        },
                ) {
                    Text(stringResource(Res.string.play_continue))
                }
            }
        }

        // Skipping is only allowed once this word group's play round was completed at least once.
        if (canSkip && phase != PlayPhase.Done) {
            TextButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.play_skip))
            }
        }
    }
}

@Composable
private fun PlayHeader(
    title: String,
    roundNumber: Int,
    roundCount: Int,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.back),
                )
            }
            Column {
                Text(
                    text = stringResource(Res.string.play_round, roundNumber, roundCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A single 3D word coin. The front shows the word's emoji (big) and its text; the back shows the
 * translation. The coin floats in from below the playfield when it first appears, bobs gently in
 * place, flips around its Y axis when [flipped], and pops with a springy pulse when [spoken].
 */
@Composable
private fun WordCoin(
    word: StudyWord,
    index: Int,
    flipped: Boolean,
    spoken: Boolean,
    active: Boolean,
    size: Dp,
    x: Dp,
    y: Dp,
    fieldHeight: Dp,
    onTap: () -> Unit,
) {
    // Entrance: the coin floats up from below the playfield to its resting spot, staggered by index.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 120L)
        enter.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 120f),
        )
    }

    // Gentle idle bobbing, phase-shifted per coin so the field feels alive rather than mechanical.
    val bobbing = rememberInfiniteTransition()
    val bobPhase by bobbing.animateFloat(
        initialValue = 0f,
        targetValue = 2f * 3.14159f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400 + index * 180, easing = LinearEasing),
        ),
    )
    val bobOffset = (sin(bobPhase + index * 1.3f) * 5f).dp

    // The 3D flip. 0° shows the front; 180° shows the back.
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 220f),
    )

    // A springy pop when the word is spoken correctly.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(spoken) {
        if (spoken) {
            pop.animateTo(1.25f, spring(dampingRatio = 0.35f, stiffness = 400f))
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
        }
    }

    // A soft glowing pulse while this coin is the one being spoken.
    val activePulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val startY = fieldHeight + size
    val enterY = startY + (y - startY) * enter.value
    val scale = pop.value * (if (active) activePulse else 1f) * (0.6f + 0.4f * enter.value)

    val colorScheme = MaterialTheme.colorScheme
    val frontBase = colorScheme.primaryContainer
    val backBase = if (spoken) colorScheme.tertiaryContainer else colorScheme.secondaryContainer
    val ringColor = if (active) colorScheme.primary else colorScheme.outline.copy(alpha = 0.4f)

    Box(
        modifier = Modifier
            .offset(x = x, y = enterY + bobOffset)
            .size(size)
            .graphicsLayer {
                rotationY = rotation
                scaleX = scale
                scaleY = scale
                alpha = enter.value.coerceIn(0f, 1f)
                cameraDistance = 12f * density
            }
            .shadow(elevation = if (active) 16.dp else 8.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            CoinFace(base = frontBase, ringColor = ringColor) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = word.emoji, fontSize = 34.sp)
                    Text(
                        text = word.hanzi,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = colorScheme.onPrimaryContainer,
                    )
                }
            }
        } else {
            // The back face is mirrored by the 180° flip; flip it again so its text reads normally.
            CoinFace(
                base = backBase,
                ringColor = ringColor,
                modifier = Modifier.graphicsLayer { rotationY = 180f },
            ) {
                Text(
                    text = word.english,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    color = if (spoken) colorScheme.onTertiaryContainer else colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
}

/**
 * One face of a coin: a radial-gradient disc (lit from the upper left, like a real coin catching
 * light) with a rim ring, hosting the face's [content].
 */
@Composable
private fun CoinFace(
    base: Color,
    ringColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerp(base, Color.White, 0.35f),
                        base,
                        lerp(base, Color.Black, 0.15f),
                    ),
                ),
                shape = CircleShape,
            )
            .border(width = 3.dp, color = ringColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * The audio-input frame that appears above the bottom button while the learner is speaking a
 * coin's word: the coin's word with a nudge to say it, then a live "listening" row with the
 * transcript so far, a spinner while checking, or a gentle retry notice.
 *
 * When [meanings] is true (the meanings phase), only the translation and emoji are shown — the
 * learner has to recall the word themselves, so the word and its romanization are hidden.
 */
@Composable
private fun SpeakFrame(
    word: StudyWord,
    meanings: Boolean,
    state: SpeakState,
    transcript: String,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = word.emoji, style = MaterialTheme.typography.headlineSmall)
                Column {
                    Text(
                        text = if (meanings) word.english else word.hanzi,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!meanings && word.pinyin.isNotBlank()) {
                        Text(
                            text = word.pinyin,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            when (state) {
                // The app is reading the word aloud; recognition hasn't started yet.
                SpeakState.Speaking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "🔊", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(Res.string.play_listen),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                SpeakState.Listening -> {
                    // A strong, unmistakable cue that the mic is live: a red mic with an expanding,
                    // fading halo pulsing around it.
                    val cue = rememberInfiniteTransition()
                    val haloScale by cue.animateFloat(
                        initialValue = 0.9f,
                        targetValue = 1.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    )
                    val haloAlpha by cue.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .graphicsLayer {
                                        scaleX = haloScale
                                        scaleY = haloScale
                                        alpha = haloAlpha
                                    }
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            text = transcript.ifBlank {
                                stringResource(
                                    if (meanings) {
                                        Res.string.play_say_word_meaning
                                    } else {
                                        Res.string.play_say_word
                                    },
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                SpeakState.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(Res.string.play_checking),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                SpeakState.TryAgain -> {
                    Text(
                        text = stringResource(Res.string.play_try_again),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRetry() },
                    )
                }

                SpeakState.Error -> {
                    Text(
                        text = errorMessage ?: stringResource(Res.string.play_no_speech),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRetry() },
                    )
                }
            }
        }
    }
}
