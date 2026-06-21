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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.queatz.microcosmicdisambiguation.MissionUiState
import com.queatz.microcosmicdisambiguation.R
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.data.Mission
import com.queatz.microcosmicdisambiguation.data.MissionChoice
import com.queatz.microcosmicdisambiguation.data.MissionTurn

/**
 * Hosts the mission experience for a study session: a simulation of real-life situations the
 * learner navigates using what they studied. Shows a loading state while missions are generated,
 * an error state with a retry, and otherwise the interactive [MissionRunner].
 */
@Composable
fun MissionScreen(
    title: String,
    state: MissionUiState,
    speaker: TtsEngine,
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
                text = stringResource(R.string.mission_building),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.mission_building_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        state.error != null -> MissionStatus(modifier) {
            Text(
                text = stringResource(R.string.mission_build_failed),
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
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.back)) }
                Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            }
        }

        missions.isEmpty() -> MissionStatus(modifier) {
            Text(
                text = stringResource(R.string.mission_none_available),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(onClick = onClose) { Text(stringResource(R.string.back)) }
        }

        else -> MissionRunner(
            title = title,
            missions = missions,
            speaker = speaker,
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

/**
 * Walks the learner through every [missions] in order. Each mission is a short conversation where
 * the learner picks what to say back to the other person; picking the correct line every turn
 * navigates the situation successfully and unlocks the next mission.
 */
@Composable
private fun MissionRunner(
    title: String,
    missions: List<Mission>,
    speaker: TtsEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var missionIndex by remember { mutableIntStateOf(0) }
    // current turn within the active mission
    var turnIndex by remember(missionIndex) { mutableIntStateOf(0) }
    // the choice the learner has tapped this turn, kept so we can show its consequence and let a
    // wrong pick be retried before committing
    var selected by remember(missionIndex, turnIndex) { mutableStateOf<MissionChoice?>(null) }

    val mission = missions[missionIndex]
    val isLastMission = missionIndex == missions.lastIndex
    val currentTurn = mission.turns.getOrNull(turnIndex)
    // The final turn of the mission: completing it ends the mission directly (no success screen).
    val isLastTurn = turnIndex >= mission.turns.lastIndex

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
                TurnContent(
                    turn = currentTurn,
                    selected = selected,
                    onSpeak = { speaker.speak(it) },
                    onSelect = { choice ->
                        selected = choice
                        if (choice.hanzi.isNotBlank()) speaker.speak(choice.hanzi)
                    },
                )
            }
        }

        MissionActions(
            isLastTurn = isLastTurn,
            isLastMission = isLastMission,
            selected = selected,
            onContinue = {
                selected = null
                turnIndex++
            },
            onRetry = { selected = null },
            onNextMission = {
                if (isLastMission) {
                    onClose()
                } else {
                    missionIndex++
                }
            },
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
            text = stringResource(R.string.mission_header, missionNumber, missionCount, title),
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
                text = stringResource(R.string.mission_talking_to, mission.role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One conversation turn: the other person's line (tap to hear) and the choices the learner picks
 * between. To keep missions challenging, no English translation is shown for the line or the
 * choices — the learner must understand the Mandarin itself (only the mission's initial context in
 * the header stays in English). Once a choice is tapped its consequence ([MissionChoice.reply]) is
 * shown so the learner sees what happens — a positive outcome for the correct line, a gentle
 * correction otherwise.
 */
@Composable
private fun TurnContent(
    turn: MissionTurn,
    selected: MissionChoice?,
    onSpeak: (String) -> Unit,
    onSelect: (MissionChoice) -> Unit,
) {
    if (turn.speaker.isNotBlank()) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable { onSpeak(turn.speaker) },
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

    // Shuffle the choices (stable per turn) so the correct answer isn't always in the same spot.
    val choices = remember(turn) { turn.choices.shuffled() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        choices.forEach { choice ->
            ChoiceCard(
                choice = choice,
                selected = selected,
                onClick = { onSelect(choice) },
            )
        }
    }

    selected?.let { choice ->
        // Always reveal the outcome of a pick. The correct line shows a positive outcome (with a
        // warm fallback if the generated reply was empty) so the learner sees their success, not
        // just the negative outcomes of wrong choices.
        val outcome = choice.reply.ifBlank {
            if (choice.correct) stringResource(R.string.mission_default_success) else ""
        }
        if (outcome.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (choice.correct) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * A single tappable choice. After selection it turns green when [MissionChoice.correct] or red
 * otherwise, so the learner sees whether the line they said worked.
 */
@Composable
private fun ChoiceCard(
    choice: MissionChoice,
    selected: MissionChoice?,
    onClick: () -> Unit,
) {
    val isSelected = selected === choice
    val containerColor = when {
        isSelected && choice.correct -> MaterialTheme.colorScheme.tertiaryContainer
        isSelected && !choice.correct -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // Once the learner lands on the correct line, lock the choices so they can't undo a win.
    val locked = selected?.correct == true
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = !locked || isSelected, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = choice.hanzi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (choice.pinyin.isNotBlank()) {
                Text(
                    text = choice.pinyin,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The bottom action bar. After a wrong pick it offers Try again; after a correct pick it offers
 * Continue, or — on the mission's last turn — moves straight on to the next mission (or Finish),
 * since the positive outcome is already shown in the turn itself (no separate success screen).
 */
@Composable
private fun MissionActions(
    isLastTurn: Boolean,
    isLastMission: Boolean,
    selected: MissionChoice?,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onNextMission: () -> Unit,
) {
    when {
        selected == null -> Text(
            text = stringResource(R.string.mission_pick_what_to_say),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        !selected.correct -> OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.try_again))
        }

        isLastTurn -> Button(
            onClick = onNextMission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isLastMission) stringResource(R.string.finish) else stringResource(R.string.mission_next))
        }

        else -> Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.mission_continue))
        }
    }
}
