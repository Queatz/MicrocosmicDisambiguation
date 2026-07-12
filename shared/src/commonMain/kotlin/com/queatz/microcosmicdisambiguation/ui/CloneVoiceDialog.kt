package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.queatz.microcosmicdisambiguation.audio.SpeechRecognizer
import com.queatz.microcosmicdisambiguation.audio.Wav
import com.queatz.microcosmicdisambiguation.data.ClonedVoice
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.resources.Res
import com.queatz.microcosmicdisambiguation.resources.cancel
import com.queatz.microcosmicdisambiguation.resources.clone_voice_create
import com.queatz.microcosmicdisambiguation.resources.clone_voice_enrolling
import com.queatz.microcosmicdisambiguation.resources.clone_voice_error
import com.queatz.microcosmicdisambiguation.resources.clone_voice_hint
import com.queatz.microcosmicdisambiguation.resources.clone_voice_language
import com.queatz.microcosmicdisambiguation.resources.clone_voice_name
import com.queatz.microcosmicdisambiguation.resources.clone_voice_name_placeholder
import com.queatz.microcosmicdisambiguation.resources.clone_voice_permission_needed
import com.queatz.microcosmicdisambiguation.resources.clone_voice_record
import com.queatz.microcosmicdisambiguation.resources.clone_voice_recording
import com.queatz.microcosmicdisambiguation.resources.clone_voice_sample_ready
import com.queatz.microcosmicdisambiguation.resources.clone_voice_stop
import com.queatz.microcosmicdisambiguation.resources.clone_voice_success
import com.queatz.microcosmicdisambiguation.resources.clone_voice_title
import com.queatz.microcosmicdisambiguation.resources.clone_voice_upload
import com.queatz.microcosmicdisambiguation.resources.close
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The lifecycle phases of the clone-a-voice flow, driving which controls and messages are shown. */
private enum class ClonePhase { Idle, Enrolling, Success, Error }

/**
 * Lets the learner train ("clone") a custom AI voice for a chosen language by recording a short
 * sample or uploading an existing audio file, then registers it with CosyVoice voice enrollment via
 * [onEnroll]. Success and any enrollment error are surfaced inline, so the outcome is always visible.
 *
 * @param defaultLanguage the language pre-selected when the dialog opens (usually the study target)
 * @param createRecognizer builds a [SpeechRecognizer] biased toward the given language, used to
 *   capture the recorded sample's raw PCM audio
 * @param onEnroll performs the (network) enrollment, returning the created [ClonedVoice] or the error
 * @param onDismiss closes the dialog
 */
@Composable
fun CloneVoiceDialog(
    defaultLanguage: Language,
    createRecognizer: (Language) -> SpeechRecognizer,
    onEnroll: suspend (name: String, language: Language, audio: ByteArray, mimeType: String) -> Result<ClonedVoice>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val micPermission = rememberMicPermission()
    val audioPicker = rememberAudioPicker()
    val permissionNeeded = stringResource(Res.string.clone_voice_permission_needed)

    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(defaultLanguage) }
    var sample by remember { mutableStateOf<PickedAudio?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(ClonePhase.Idle) }
    var message by remember { mutableStateOf("") }

    // A fresh recognizer per selected language, biased toward that language's ASR code. Recreating
    // it when the language changes shuts down the previous one via this effect's onDispose.
    val recognizer = remember(language) { createRecognizer(language) }
    DisposableEffect(recognizer) {
        recognizer.onListeningChanged = { listening -> isRecording = listening }
        recognizer.onSessionComplete = { audio, _ ->
            if (audio.isNotEmpty()) {
                sample = PickedAudio(Wav.pcmToWav(audio), "audio/wav")
            }
            isRecording = false
        }
        recognizer.onError = { error ->
            isRecording = false
            message = error
            phase = ClonePhase.Error
        }
        onDispose {
            recognizer.onSessionComplete = null
            recognizer.onListeningChanged = null
            recognizer.onError = null
            recognizer.shutdown()
        }
    }

    val enrolling = phase == ClonePhase.Enrolling
    val succeeded = phase == ClonePhase.Success

    AlertDialog(
        onDismissRequest = { if (!enrolling) onDismiss() },
        title = { Text(stringResource(Res.string.clone_voice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(Res.string.clone_voice_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CloneLanguageSelector(
                    language = language,
                    enabled = !enrolling && !succeeded && !isRecording,
                    onSelect = { language = it },
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    shape = RoundedCornerShape(24.dp),
                    label = { Text(stringResource(Res.string.clone_voice_name)) },
                    placeholder = { Text(stringResource(Res.string.clone_voice_name_placeholder)) },
                    singleLine = true,
                    enabled = !enrolling && !succeeded,
                    modifier = Modifier.fillMaxWidth(),
                )

                val onToggleRecording = {
                    if (isRecording) {
                        recognizer.stop()
                    } else {
                        message = ""
                        if (phase == ClonePhase.Error) phase = ClonePhase.Idle
                        micPermission.request(
                            onGranted = { recognizer.start() },
                            onDenied = {
                                message = permissionNeeded
                                phase = ClonePhase.Error
                            },
                        )
                    }
                }
                val onPickUpload = {
                    message = ""
                    if (phase == ClonePhase.Error) phase = ClonePhase.Idle
                    audioPicker.pick { picked -> sample = picked }
                }

                // Below a narrow-phone width, the record and upload buttons no longer fit side by
                // side without truncating their labels, so they stack vertically instead.
                BoxWithConstraints {
                    if (maxWidth < 360.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CloneSampleButton(
                                icon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                text = if (isRecording) {
                                    stringResource(Res.string.clone_voice_stop)
                                } else {
                                    stringResource(Res.string.clone_voice_record)
                                },
                                enabled = !enrolling && !succeeded,
                                onClick = onToggleRecording,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            CloneSampleButton(
                                icon = Icons.Default.Upload,
                                text = stringResource(Res.string.clone_voice_upload),
                                enabled = !enrolling && !succeeded && !isRecording,
                                onClick = onPickUpload,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CloneSampleButton(
                                icon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                text = if (isRecording) {
                                    stringResource(Res.string.clone_voice_stop)
                                } else {
                                    stringResource(Res.string.clone_voice_record)
                                },
                                enabled = !enrolling && !succeeded,
                                onClick = onToggleRecording,
                                modifier = Modifier.weight(1f),
                            )
                            CloneSampleButton(
                                icon = Icons.Default.Upload,
                                text = stringResource(Res.string.clone_voice_upload),
                                enabled = !enrolling && !succeeded && !isRecording,
                                onClick = onPickUpload,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                when {
                    isRecording -> StatusLine(
                        text = stringResource(Res.string.clone_voice_recording),
                        color = MaterialTheme.colorScheme.primary,
                    )

                    succeeded -> StatusLine(
                        text = stringResource(Res.string.clone_voice_success),
                        color = MaterialTheme.colorScheme.primary,
                        leading = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )

                    phase == ClonePhase.Error -> StatusLine(
                        text = stringResource(Res.string.clone_voice_error, message),
                        color = MaterialTheme.colorScheme.error,
                    )

                    enrolling -> StatusLine(
                        text = stringResource(Res.string.clone_voice_enrolling),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        leading = {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )

                    sample != null -> StatusLine(
                        text = stringResource(Res.string.clone_voice_sample_ready),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.close))
                }
            } else {
                TextButton(
                    onClick = {
                        val picked = sample ?: return@TextButton
                        phase = ClonePhase.Enrolling
                        message = ""
                        scope.launch {
                            onEnroll(name, language, picked.bytes, picked.mimeType).fold(
                                onSuccess = { phase = ClonePhase.Success },
                                onFailure = {
                                    message = it.message ?: ""
                                    phase = ClonePhase.Error
                                },
                            )
                        }
                    },
                    enabled = sample != null && !enrolling && !isRecording,
                ) {
                    Text(stringResource(Res.string.clone_voice_create))
                }
            }
        },
        dismissButton = {
            if (!succeeded) {
                TextButton(onClick = onDismiss, enabled = !enrolling) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        },
    )
}

/**
 * The record/upload sample buttons in [CloneVoiceDialog], factored out so they can be laid out
 * either side by side ([Modifier.weight] in a [Row]) or stacked ([Modifier.fillMaxWidth] in a
 * [Column]) depending on the available width, without duplicating their content.
 */
@Composable
private fun CloneSampleButton(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StatusLine(
    text: String,
    color: Color,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun CloneLanguageSelector(
    language: Language,
    enabled: Boolean,
    onSelect: (Language) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${language.flag} ${language.displayName}",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clone_voice_language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Language.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text("${entry.flag} ${entry.displayName}") },
                    onClick = {
                        expanded = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}
