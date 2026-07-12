package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.queatz.microcosmicdisambiguation.LocalAppContainer
import com.queatz.microcosmicdisambiguation.MissionUiState
import com.queatz.microcosmicdisambiguation.ai.QwenService
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.data.LanguagePair
import com.queatz.microcosmicdisambiguation.data.Mission
import com.queatz.microcosmicdisambiguation.data.MissionReply
import com.queatz.microcosmicdisambiguation.data.MissionTurn
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.back
import com.queatz.microcosmicdisambiguation.resources.finish
import com.queatz.microcosmicdisambiguation.resources.mission_build_failed
import com.queatz.microcosmicdisambiguation.resources.mission_building
import com.queatz.microcosmicdisambiguation.resources.mission_building_subtitle
import com.queatz.microcosmicdisambiguation.resources.mission_header
import com.queatz.microcosmicdisambiguation.resources.mission_next
import com.queatz.microcosmicdisambiguation.resources.mission_none_available
import com.queatz.microcosmicdisambiguation.resources.mission_reply_continue
import com.queatz.microcosmicdisambiguation.resources.mission_reply_failed
import com.queatz.microcosmicdisambiguation.resources.mission_reply_hint
import com.queatz.microcosmicdisambiguation.resources.mission_reply_listen_npc
import com.queatz.microcosmicdisambiguation.resources.mission_reply_no_speech
import com.queatz.microcosmicdisambiguation.resources.mission_reply_nothing_heard
import com.queatz.microcosmicdisambiguation.resources.mission_reply_npc_response
import com.queatz.microcosmicdisambiguation.resources.mission_reply_permission_needed
import com.queatz.microcosmicdisambiguation.resources.mission_reply_processing
import com.queatz.microcosmicdisambiguation.resources.mission_reply_prompt_default
import com.queatz.microcosmicdisambiguation.resources.mission_reply_recording
import com.queatz.microcosmicdisambiguation.resources.mission_reply_say_back
import com.queatz.microcosmicdisambiguation.resources.mission_reply_speak
import com.queatz.microcosmicdisambiguation.resources.mission_reply_stop
import com.queatz.microcosmicdisambiguation.resources.mission_reply_translation
import com.queatz.microcosmicdisambiguation.resources.mission_reply_your_reply
import com.queatz.microcosmicdisambiguation.resources.mission_talking_to
import com.queatz.microcosmicdisambiguation.resources.try_again
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Hosts the mission experience for a study session: a simulation of real-life situations the
 * learner navigates by speaking freely. Shows a loading state while missions are generated, an
 * error state with a retry, and otherwise the interactive [MissionRunner].
 */
@Composable
fun MissionScreen(
    title: String,
    state: MissionUiState,
    speaker: TtsEngine,
    qwenService: QwenService,
    languages: LanguagePair,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missions = state.missions?.missions.orEmpty()

    when {
        state.isGenerating -> MissionStatus(modifier) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(Res.string.mission_building),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.mission_building_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        state.error != null -> MissionStatus(modifier) {
            Text(
                text = stringResource(Res.string.mission_build_failed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose) { Text(stringResource(Res.string.back)) }
                Button(onClick = onRetry) { Text(stringResource(Res.string.try_again)) }
            }
        }

        missions.isEmpty() -> MissionStatus(modifier) {
            Text(
                text = stringResource(Res.string.mission_none_available),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(onClick = onClose) { Text(stringResource(Res.string.back)) }
        }

        else -> MissionRunner(
            title = title,
            missions = missions,
            speaker = speaker,
            qwenService = qwenService,
            languages = languages,
            onClose = onClose,
            modifier = modifier,
        )
    }
}

/** A centered column used for the loading/error/empty states. */
@Composable
private fun MissionStatus(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** The distinct stages of a single open-ended reply attempt within a mission turn. */
private enum class ReplyPhase { Idle, Listening, Transcribing, Thinking, Result, Error }

/**
 * Walks the learner through every [missions] in order. Each mission is a short conversation that the
 * learner navigates by speaking freely: they read the situation, press "Speak now", say whatever
 * they want in the target language, and the AI both translates their own words back to them and has
 * the other person react in character. There is no right/wrong answer — any plausible reply advances
 * the conversation — and the learner can keep talking back to continue the exchange within a turn,
 * so the mission branches based on what the learner actually says.
 */
@Composable
private fun MissionRunner(
    title: String,
    missions: List<Mission>,
    speaker: TtsEngine,
    qwenService: QwenService,
    languages: LanguagePair,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val audioFactory = LocalAppContainer.current.audio
    val micPermission = rememberMicPermission()
    // Error messages read from callbacks (outside composition) must be captured up front.
    val replyFailedMessage = stringResource(Res.string.mission_reply_failed)
    val noSpeechMessage = stringResource(Res.string.mission_reply_no_speech)
    val permissionNeededMessage = stringResource(Res.string.mission_reply_permission_needed)

    // A recognizer biased toward the language being studied, so it transcribes the learner's
    // attempt at the target language rather than the language they already know.
    val recognizer = remember(languages.target) {
        audioFactory.createSpeechRecognizer(languageHints = listOf(languages.target.asrCode))
    }

    var missionIndex by remember { mutableIntStateOf(0) }
    // current turn within the active mission
    var turnIndex by remember(missionIndex) { mutableIntStateOf(0) }
    // the exchanges so far in this mission, so the AI keeps the other person consistent across turns
    var history by remember(missionIndex) { mutableStateOf<List<MissionReply>>(emptyList()) }

    // These per-attempt states are intentionally NOT keyed on the turn: the recognizer callbacks
    // below are registered once (the recognizer outlives individual turns), so they must mutate the
    // same state instances every turn. [advance] and [beginListening] reset them explicitly.
    var phase by remember { mutableStateOf(ReplyPhase.Idle) }
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<MissionReply?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val mission = missions[missionIndex]
    val isLastMission = missionIndex == missions.lastIndex
    val currentTurn = mission.turns.getOrNull(turnIndex)
    // The final turn of the mission: completing it ends the mission directly (no success screen).
    val isLastTurn = turnIndex >= mission.turns.lastIndex

    // The recognizer callbacks are installed once, so let them read the latest turn/context/history
    // rather than the values captured when they were first registered.
    val currentMission = rememberUpdatedState(mission)
    val currentTurnState = rememberUpdatedState(currentTurn)
    val currentHistory = rememberUpdatedState(history)
    val currentLanguages = rememberUpdatedState(languages)

    DisposableEffect(recognizer) {
        recognizer.onListeningChanged = { listening ->
            if (listening) {
                phase = ReplyPhase.Listening
            } else if (phase == ReplyPhase.Listening) {
                // The mic stopped; wait for the transcription to arrive.
                phase = ReplyPhase.Transcribing
            }
        }
        recognizer.onTextChanged = { transcript = it }
        recognizer.onError = { message ->
            errorMessage = message
            phase = ReplyPhase.Error
        }
        recognizer.onSessionComplete = { _, finalTranscript ->
            transcript = finalTranscript
            phase = ReplyPhase.Thinking
            val turn = currentTurnState.value
            if (turn == null) {
                phase = ReplyPhase.Error
            } else {
                scope.launch {
                    try {
                        val result = qwenService.generateMissionReply(
                            mission = currentMission.value,
                            turn = turn,
                            transcript = finalTranscript,
                            languages = currentLanguages.value,
                            history = currentHistory.value,
                        )
                        reply = result
                        phase = ReplyPhase.Result
                        if (result.reply.isNotBlank()) speaker.speak(result.reply)
                    } catch (t: Throwable) {
                        errorMessage = t.message ?: replyFailedMessage
                        phase = ReplyPhase.Error
                    }
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

    // The recognizer reports neither a completion nor an error when it hears only silence (the
    // transcript stays blank), so guard the transcribing stage with a timeout that surfaces a gentle
    // "didn't catch that" notice and offers a retry.
    LaunchedEffect(phase) {
        if (phase == ReplyPhase.Transcribing) {
            delay(6_000)
            if (phase == ReplyPhase.Transcribing) {
                errorMessage = noSpeechMessage
                phase = ReplyPhase.Error
            }
        }
    }

    fun beginListening() {
        transcript = ""
        reply = null
        errorMessage = null
        recognizer.start()
    }

    fun startRecording() {
        micPermission.request(
            onGranted = { beginListening() },
            onDenied = {
                errorMessage = permissionNeededMessage
                phase = ReplyPhase.Error
            },
        )
    }

    fun advance() {
        reply?.let { history = history + it }
        // Reset the per-attempt state for the next turn (these states are not turn-keyed).
        phase = ReplyPhase.Idle
        transcript = ""
        reply = null
        errorMessage = null
        if (isLastTurn) {
            if (isLastMission) onClose() else missionIndex++
        } else {
            turnIndex++
        }
    }

    // Keep talking within the SAME turn, as if the conversation were really ongoing: the other
    // person's just-given reply is folded into [history] (so the next AI response stays consistent
    // and reacts to it) and the learner immediately records what they want to say back.
    fun continueConversation() {
        reply?.let { history = history + it }
        startRecording()
    }

    // Speak the other person's opening line whenever a new turn begins.
    LaunchedEffect(missionIndex, turnIndex) {
        val line = currentTurn?.speaker
        if (!line.isNullOrBlank()) {
            speaker.speak(line)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MissionHeader(
            title = title,
            mission = mission,
            missionNumber = missionIndex + 1,
            missionCount = missions.size,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (currentTurn != null) {
                OpenEndedReply(
                    turn = currentTurn,
                    phase = phase,
                    transcript = transcript,
                    reply = reply,
                    errorMessage = errorMessage,
                    onSpeakLine = { speaker.speak(it) },
                    onSpeakReply = { startRecording() },
                    onStop = { recognizer.stop() },
                    onListenNpc = { speaker.speak(it) },
                    onContinueConversation = { continueConversation() },
                    onRetry = { startRecording() },
                )
            }
        }

        MissionActions(
            phase = phase,
            isLastTurn = isLastTurn,
            isLastMission = isLastMission,
            onContinue = { advance() },
        )
    }
}

@Composable
private fun MissionHeader(
    title: String,
    mission: Mission,
    missionNumber: Int,
    missionCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.mission_header, missionNumber, missionCount, title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = mission.icon
            if (icon.isNotBlank()) {
                Text(text = icon, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = mission.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (mission.setting.isNotBlank()) {
            Text(
                text = mission.setting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mission.role.isNotBlank()) {
            Text(
                text = stringResource(Res.string.mission_talking_to, mission.role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One conversation turn rendered as an open-ended reply interface: the other person's line (tap to
 * hear), a short nudge for what the learner is trying to do, and — depending on [phase] — the
 * controls to record a spoken reply, a live recording indicator, a loading state, or the three AI
 * feedback layers (the learner's transcript, its translation, and the other person's response with a
 * "Listen" button).
 */
@Composable
private fun OpenEndedReply(
    turn: MissionTurn,
    phase: ReplyPhase,
    transcript: String,
    reply: MissionReply?,
    errorMessage: String?,
    onSpeakLine: (String) -> Unit,
    onSpeakReply: () -> Unit,
    onStop: () -> Unit,
    onListenNpc: (String) -> Unit,
    onContinueConversation: () -> Unit,
    onRetry: () -> Unit,
) {
    if (turn.speaker.isNotBlank()) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable { onSpeakLine(turn.speaker) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (turn.emoji.isNotBlank()) {
                    Text(text = turn.emoji, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = turn.speaker,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (turn.speakerPinyin.isNotBlank()) {
                        Text(
                            text = turn.speakerPinyin,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    // The conversational prompt: what the learner is being asked to say this turn.
    Text(
        text = turn.prompt.ifBlank { stringResource(Res.string.mission_reply_prompt_default) },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    when (phase) {
        ReplyPhase.Idle -> {
            BigSpeakButton(
                label = stringResource(Res.string.mission_reply_speak),
                onClick = onSpeakReply,
            )
        }

        ReplyPhase.Listening -> {
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
                    text = stringResource(Res.string.mission_reply_recording),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(Res.string.mission_reply_stop))
            }
        }

        ReplyPhase.Transcribing, ReplyPhase.Thinking -> {
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
                    text = stringResource(Res.string.mission_reply_processing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        ReplyPhase.Result -> {
            reply?.let { result ->
                // Top: the learner's own words (transcript), in the target language.
                FeedbackBlock(
                    label = stringResource(Res.string.mission_reply_your_reply),
                    value = transcript.ifBlank {
                        stringResource(Res.string.mission_reply_nothing_heard)
                    },
                )
                // Middle: the translation of what they said, into their own language.
                if (result.translation.isNotBlank()) {
                    FeedbackBlock(
                        label = stringResource(Res.string.mission_reply_translation),
                        value = result.translation,
                    )
                }
                // Bottom: the other person's in-character response, with a button to hear it.
                if (result.reply.isNotBlank()) {
                    val npcLabel = stringResource(Res.string.mission_reply_npc_response)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = npcLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = result.reply,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (result.replyRomanization.isNotBlank()) {
                                Text(
                                    text = result.replyRomanization,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (result.replyTranslation.isNotBlank()) {
                                Text(
                                    text = result.replyTranslation,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            OutlinedButton(
                                onClick = { onListenNpc(result.reply) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(stringResource(Res.string.mission_reply_listen_npc))
                            }
                        }
                    }
                }
                // Keep the conversation going: speak a reply back to what they just said, as if
                // you were really talking to them. "Continue mission" (below) moves on instead.
                BigSpeakButton(
                    label = stringResource(Res.string.mission_reply_say_back),
                    onClick = onContinueConversation,
                )
            }
        }

        ReplyPhase.Error -> {
            Text(
                text = errorMessage ?: stringResource(Res.string.mission_reply_no_speech),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            BigSpeakButton(
                label = stringResource(Res.string.mission_reply_speak),
                onClick = onRetry,
            )
        }
    }
}

/**
 * A large, centered, round microphone button — the main call to action for speaking. Used to start
 * a reply, to reply back and keep the conversation going, and to try again after an error.
 */
@Composable
private fun BigSpeakButton(label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A small labelled block of feedback text (e.g. the transcript or its translation). */
@Composable
private fun FeedbackBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * The bottom action bar. Once the other person has responded it offers "Continue mission" to move
 * the conversation forward — or, on the mission's last turn, on to the next mission (or Finish).
 * Before a reply has been processed it simply nudges the learner to speak.
 */
@Composable
private fun MissionActions(
    phase: ReplyPhase,
    isLastTurn: Boolean,
    isLastMission: Boolean,
    onContinue: () -> Unit,
) {
    if (phase == ReplyPhase.Result) {
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    !isLastTurn -> stringResource(Res.string.mission_reply_continue)
                    isLastMission -> stringResource(Res.string.finish)
                    else -> stringResource(Res.string.mission_next)
                },
            )
        }
    } else {
        Text(
            text = stringResource(Res.string.mission_reply_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
