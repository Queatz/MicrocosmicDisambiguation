package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.queatz.microcosmicdisambiguation.HomeUiState
import com.queatz.microcosmicdisambiguation.audio.SpeechRecognizer
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.LanguagePair
import com.queatz.microcosmicdisambiguation.data.StudySession
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.cancel
import com.queatz.microcosmicdisambiguation.resources.delete
import com.queatz.microcosmicdisambiguation.resources.home_change_language
import com.queatz.microcosmicdisambiguation.resources.home_delete_session_message
import com.queatz.microcosmicdisambiguation.resources.home_delete_session_title
import com.queatz.microcosmicdisambiguation.resources.home_describe_situation
import com.queatz.microcosmicdisambiguation.resources.home_generating
import com.queatz.microcosmicdisambiguation.resources.home_irl_situation_placeholder
import com.queatz.microcosmicdisambiguation.resources.home_learning
import com.queatz.microcosmicdisambiguation.resources.home_no_sessions
import com.queatz.microcosmicdisambiguation.resources.home_previous_sessions
import com.queatz.microcosmicdisambiguation.resources.home_stop_voice_input
import com.queatz.microcosmicdisambiguation.resources.home_study_this
import com.queatz.microcosmicdisambiguation.resources.home_voice_input
import com.queatz.microcosmicdisambiguation.resources.home_welcome_banner_dismiss
import com.queatz.microcosmicdisambiguation.resources.home_welcome_banner_message
import com.queatz.microcosmicdisambiguation.resources.home_welcome_banner_title
import com.queatz.microcosmicdisambiguation.resources.home_whats_going_on
import com.queatz.microcosmicdisambiguation.resources.settings
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    state: HomeUiState,
    recognizer: SpeechRecognizer,
    onStudy: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
    onDismissWelcomeBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var situation by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Shown once, the very first time the app is opened; dismissing it persists forever.
        AnimatedVisibility(visible = state.showWelcomeBanner) {
            WelcomeBanner(
                onDismiss = onDismissWelcomeBanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        // A wide window (landscape phone or tablet) shows the input and the previous sessions side by
        // side; a narrow window stacks them. Measuring the available width keeps this identical on every
        // platform, without any Android-only configuration APIs.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val useTwoColumnLayout = state.history.isNotEmpty() && maxWidth >= 600.dp

            if (useTwoColumnLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    MainSection(
                        state = state,
                        recognizer = recognizer,
                        situation = situation,
                        onSituationChange = { situation = it },
                        onStudy = onStudy,
                        onDismissError = onDismissError,
                        onTargetLanguageChange = onTargetLanguageChange,
                        focusRequester = focusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                    PreviousSessionsSection(
                        state = state,
                        onOpenSession = onOpenSession,
                        onDeleteSession = onDeleteSession,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TargetLanguageButton(
                            languagePair = state.languagePair,
                            onTargetLanguageChange = onTargetLanguageChange,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        MainSection(
                            state = state,
                            recognizer = recognizer,
                            situation = situation,
                            onSituationChange = { situation = it },
                            onStudy = onStudy,
                            onDismissError = onDismissError,
                            onTargetLanguageChange = onTargetLanguageChange,
                            focusRequester = focusRequester,
                            showLanguageButton = false,
                        )
                        PreviousSessionsSection(
                            state = state,
                            onOpenSession = onOpenSession,
                            onDeleteSession = onDeleteSession,
                            onOpenSettings = onOpenSettings,
                            scrollable = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A dismissible, first-time-only banner introducing the app, shown above the rest of the home
 * screen until the learner taps its close ("X") button, which persists the dismissal so it never
 * reappears.
 */
@Composable
private fun WelcomeBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.home_welcome_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.home_welcome_banner_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.home_welcome_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MainSection(
    state: HomeUiState,
    recognizer: SpeechRecognizer,
    situation: TextFieldValue,
    onSituationChange: (TextFieldValue) -> Unit,
    onStudy: (String) -> Unit,
    onDismissError: () -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    showLanguageButton: Boolean = true,
) {
    var isListening by remember { mutableStateOf(false) }
    // The text already present when dictation starts, so live results are appended rather than
    // clobbering anything the user typed first.
    var transcriptBase by remember { mutableStateOf("") }
    val micPermission = rememberMicPermission()

    DisposableEffect(recognizer) {
        recognizer.onListeningChanged = { isListening = it }
        recognizer.onTextChanged = { text ->
            val base = transcriptBase
            val combined = if (base.isBlank()) text else "$base $text"
            // Keep the caret at the end so the latest dictated words are always visible.
            onSituationChange(TextFieldValue(combined, selection = TextRange(combined.length)))
        }
        onDispose {
            recognizer.onListeningChanged = null
            recognizer.onTextChanged = null
            recognizer.onError = null
        }
    }

    fun beginListening() {
        transcriptBase = situation.text.trimEnd()
        recognizer.start()
    }

    fun toggleListening() {
        if (isListening) {
            recognizer.stop()
        } else {
            micPermission.request(onGranted = { beginListening() })
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (showLanguageButton) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TargetLanguageButton(
                    languagePair = state.languagePair,
                    onTargetLanguageChange = onTargetLanguageChange,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.home_whats_going_on),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(Res.string.home_describe_situation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = situation,
                onValueChange = onSituationChange,
                placeholder = { Text(stringResource(Res.string.home_irl_situation_placeholder), style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                enabled = !state.isGenerating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onStudy(situation.text) }),
            )

            IconButton(
                onClick = { toggleListening() },
                enabled = !state.isGenerating,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isListening) {
                        stringResource(Res.string.home_stop_voice_input)
                    } else {
                        stringResource(Res.string.home_voice_input)
                    },
                    tint = if (isListening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        Button(
            onClick = {
                if (isListening) {
                    toggleListening()
                }
                onStudy(situation.text)
            },
            enabled = situation.text.isNotBlank() && !state.isGenerating,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .fillMaxWidth(),
        ) {
            if (state.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(stringResource(Res.string.home_generating), style = MaterialTheme.typography.titleMedium)
            } else {
                Text(stringResource(Res.string.home_study_this), style = MaterialTheme.typography.titleMedium)
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            state.error?.let { error ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onDismissError() },
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The button at the top of the home screen that shows which language the learner is currently
 * studying and lets them switch the target language. The source language (the one the learner
 * already knows) is chosen in settings, so it is excluded from the choices here.
 */
@Composable
private fun TargetLanguageButton(
    languagePair: LanguagePair,
    onTargetLanguageChange: (Language) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.home_learning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "${languagePair.target.flag} ${languagePair.target.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(Res.string.home_change_language),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { language ->
                if (language != languagePair.source) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${language.flag} ${language.displayName}",
                                fontWeight = if (language == languagePair.target) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        },
                        onClick = {
                            expanded = false
                            if (language != languagePair.target) onTargetLanguageChange(language)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviousSessionsSection(
    state: HomeUiState,
    onOpenSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.home_previous_sessions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(Res.string.settings),
                )
            }
        }

        if (state.history.isEmpty()) {
            Text(
                text = stringResource(Res.string.home_no_sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.history.isNotEmpty()) {
            if (scrollable) {
                // Rendered inside an outer verticalScroll, so use a plain Column to avoid
                // nesting two vertically scrollable containers.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.history.forEach { item ->
                        HistoryRow(
                            item = item,
                            onClick = { onOpenSession(item.id) },
                            onLongClick = { onDeleteSession(item.id) },
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(state.history.size) {
                    listState.animateScrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(state.history, key = { it.id }) { item ->
                        HistoryRow(
                            item = item,
                            onClick = { onOpenSession(item.id) },
                            onLongClick = { onDeleteSession(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: StudySession, onClick: () -> Unit, onLongClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(Res.string.home_delete_session_title)) },
            text = { Text(stringResource(Res.string.home_delete_session_message, item.title.ifBlank { item.situation })) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onLongClick()
                }) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteDialog = true },
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = item.icon
                if (icon.isNotBlank()) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = item.title.ifBlank { item.situation },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.situation.isNotBlank() && item.situation != item.title) {
                        Text(
                            text = item.situation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = item.languages.target.flag,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}
