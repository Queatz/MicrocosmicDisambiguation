package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.queatz.microcosmicdisambiguation.HomeUiState
import com.queatz.microcosmicdisambiguation.data.SituationEntity

@Composable
fun HomeScreen(
    state: HomeUiState,
    onStudy: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var situation by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "What's going on?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Describe a real-life situation to study it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = situation,
            onValueChange = { situation = it },
            placeholder = { Text("IRL situation", style = MaterialTheme.typography.headlineSmall) },
            textStyle = MaterialTheme.typography.headlineSmall,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            enabled = !state.isGenerating,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onStudy(situation) }),
        )

        Button(
            onClick = { onStudy(situation) },
            enabled = situation.isNotBlank() && !state.isGenerating,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("Generating…", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Study", style = MaterialTheme.typography.titleMedium)
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            state.error?.let { error ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.history.isNotEmpty()) {
                Text(
                    text = "Previous sessions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            }
        }

        if (state.history.isNotEmpty()) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: SituationEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete session?") },
            text = { Text("This will remove \"${item.title.ifBlank { item.situation }}\" permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onLongClick()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteDialog = true },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val icon = item.icon
            if (!icon.isNullOrBlank()) {
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
    }
}
