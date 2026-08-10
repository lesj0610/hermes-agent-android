package io.github.lesj0610.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.ui.components.ToolCard
import io.github.lesj0610.hermes.ui.components.uiErrorText
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import io.github.lesj0610.hermes.core.ReasoningEffort
import io.github.lesj0610.hermes.net.ModelChoice
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.lesj0610.hermes.ui.components.MicIcon
import io.github.lesj0610.hermes.ui.components.WaveformIcon
import io.github.lesj0610.hermes.voice.VoiceState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import io.github.lesj0610.hermes.ui.components.SendIcon
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.lesj0610.hermes.core.Attachments
import io.github.lesj0610.hermes.ui.components.PlusIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatPane(
    state: ChatState,
    onSend: (String, List<String>) -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The per-turn runtime controls. Defaulted so the previews and screenshot
     * harness can render a transcript without standing up a picker.
     */
    modelLabel: String = "",
    modelChoices: List<ModelChoice> = emptyList(),
    onSelectModel: (ModelChoice) -> Unit = {},
    effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    onSelectEffort: (ReasoningEffort) -> Unit = {},
    /**
     * Voice. Absent by default so a preview renders the transcript without a
     * recognizer; [voiceAvailable] is false on a device with no speech service,
     * and the controls are then omitted rather than failing on tap.
     */
    voiceAvailable: Boolean = false,
    voiceState: VoiceState = VoiceState.Idle,
    conversing: Boolean = false,
    dictation: String? = null,
    onDictate: () -> Unit = {},
    onDictationConsumed: () -> Unit = {},
    onToggleConversation: () -> Unit = {},
) {
    val colors = LocalRunColors.current
    val listState = rememberLazyListState()

    // Follow the tail while the agent is talking. Only when new items land, so
    // it never fights a user who has scrolled up to read something.
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        state.error?.let { error ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiErrorText(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.failed,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.error_dismiss))
                }
            }
            HorizontalDivider(color = colors.line)
        }

        if (state.items.isEmpty()) {
            EmptyTranscript(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(state.items, key = { it.key }) { item -> TranscriptRow(item) }
            }
        }

        if (state.isBusy) {
            StopBar(
                stopping = state.phase is RunPhase.Stopping,
                onStop = onStop,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        HorizontalDivider(color = colors.line)
        Composer(
            enabled = !state.isBusy,
            onSend = onSend,
            modelLabel = modelLabel,
            modelChoices = modelChoices,
            onSelectModel = onSelectModel,
            effort = effort,
            onSelectEffort = onSelectEffort,
            voiceAvailable = voiceAvailable,
            voiceState = voiceState,
            conversing = conversing,
            dictation = dictation,
            onDictate = onDictate,
            onDictationConsumed = onDictationConsumed,
            onToggleConversation = onToggleConversation,
        )
    }
}

/**
 * The value alone. The word "reasoning" belongs on the menu that opens, not
 * repeated on a chip that is already showing the setting it names — the chip
 * has to fit beside the model on a phone.
 */
@Composable
private fun effortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.Off -> stringResource(R.string.effort_off)
    ReasoningEffort.Minimal -> stringResource(R.string.effort_minimal)
    ReasoningEffort.Low -> stringResource(R.string.effort_low)
    ReasoningEffort.Medium -> stringResource(R.string.effort_medium)
    ReasoningEffort.High -> stringResource(R.string.effort_high)
    ReasoningEffort.XHigh -> stringResource(R.string.effort_xhigh)
}

@Composable
private fun RuntimeChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalRunColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TranscriptRow(item: TranscriptItem) {
    val colors = LocalRunColors.current
    when (item) {
        is TranscriptItem.UserText -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .background(colors.panelRaised)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }

        is TranscriptItem.AssistantText -> Text(
            // A trailing block while streaming stands in for a caret; Compose
            // has no cursor primitive for non-editable text.
            text = if (item.streaming) item.text + " ▉" else item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is TranscriptItem.Reasoning -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.panel)
                .padding(10.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_reasoning),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        is TranscriptItem.ToolCall -> ToolCard(item)

        is TranscriptItem.Failure -> Text(
            text = uiErrorText(item.error),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.failed,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.failed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }
}

@Composable
private fun EmptyTranscript(modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.chat_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StopBar(stopping: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    OutlinedButton(
        onClick = onStop,
        enabled = !stopping,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.failed),
    ) {
        Text(stringResource(if (stopping) R.string.chat_stopping else R.string.chat_stop))
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    onSend: (String, List<String>) -> Unit,
    modelLabel: String,
    modelChoices: List<ModelChoice>,
    onSelectModel: (ModelChoice) -> Unit,
    effort: ReasoningEffort,
    onSelectEffort: (ReasoningEffort) -> Unit,
    voiceAvailable: Boolean,
    voiceState: VoiceState,
    conversing: Boolean,
    dictation: String?,
    onDictate: () -> Unit,
    onDictationConsumed: () -> Unit,
    onToggleConversation: () -> Unit,
) {
    val colors = LocalRunColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }

    // Held as data URLs rather than as Uris: the permission granted by the
    // picker is not guaranteed to outlive the pick, and the request needs the
    // bytes inline anyway.
    var attachments by remember { mutableStateOf(listOf<String>()) }
    val attachLabel = stringResource(R.string.chat_attach)
    val removeLabel = stringResource(R.string.chat_attachment_remove)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Decoding and re-encoding a photo is far too slow for the main thread.
        scope.launch {
            val encoded = withContext(Dispatchers.IO) {
                uris.mapNotNull { Attachments.toDataUrl(context, it) }
            }
            attachments = attachments + encoded
        }
    }
    // Resolved out here: a semantics block is not a composable scope.
    val dictateLabel = stringResource(R.string.voice_dictate)
    val conversationLabel = stringResource(R.string.voice_conversation)
    val sendLabel = stringResource(R.string.chat_send)

    // Dictation lands in the box rather than being sent, so a misheard word can
    // be fixed before it costs a turn. Appended, so it adds to whatever was
    // already typed instead of discarding it.
    LaunchedEffect(dictation) {
        dictation?.let { heard ->
            draft = if (draft.isBlank()) heard else "$draft $heard"
            onDictationConsumed()
        }
    }

    // One surface holding the input and everything that acts on it. Splitting
    // the runtime pickers into a strip of their own read as a separate toolbar
    // that happened to sit above the composer, rather than as part of the
    // message being written.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.panel)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
    ) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(attachments) { index, dataUrl ->
                    AttachmentThumbnail(
                        dataUrl = dataUrl,
                        removeLabel = removeLabel,
                        onRemove = {
                            attachments = attachments.filterIndexed { i, _ -> i != index }
                        },
                    )
                }
            }
        }

        // No border of its own: the container already is the field's outline,
        // and a second one inside it reads as a box in a box.
        TextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // A bordered square rather than a bare glyph: it is the only
            // control in the row that opens something outside the app, and an
            // outline is what makes it read as a button rather than as a mark
            // beside the chips.
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                    .semantics { contentDescription = attachLabel },
                contentAlignment = Alignment.Center,
            ) {
                PlusIcon(modifier = Modifier.size(18.dp))
            }

            Box {
                // Inert rather than absent when the gateway cannot serve the
                // inventory: the model in use is still worth showing.
                RuntimeChip(
                    label = modelLabel.ifBlank { stringResource(R.string.settings_model_default) },
                    enabled = modelChoices.isNotEmpty(),
                    onClick = { modelsOpen = true },
                )
                DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                    modelChoices.forEach { choice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(choice.model, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = choice.providerLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.muted,
                                    )
                                }
                            },
                            onClick = {
                                onSelectModel(choice)
                                modelsOpen = false
                            },
                        )
                    }
                }
            }

            Box {
                RuntimeChip(
                    label = effortLabel(effort),
                    enabled = true,
                    onClick = { effortOpen = true },
                )
                DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                    Text(
                        text = stringResource(R.string.effort_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                    ReasoningEffort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = effortLabel(option),
                                    color = if (option == effort) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                onSelectEffort(option)
                                effortOpen = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (voiceAvailable) {
                IconButton(onClick = onDictate, enabled = enabled) {
                    MicIcon(
                        tint = if (voiceState == VoiceState.Listening) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            colors.muted
                        },
                        modifier = Modifier.semantics { contentDescription = dictateLabel },
                    )
                }
            }

            // One circular button, showing whichever action the draft implies:
            // an empty box means the next thing you do is talk, a filled one
            // means send. Two permanent buttons made the emptier of them look
            // disabled half the time.
            val hasDraft = draft.isNotBlank() || attachments.isNotEmpty()
            FilledIconButton(
                onClick = {
                    if (hasDraft) {
                        onSend(draft, attachments)
                        draft = ""
                        attachments = emptyList()
                    } else {
                        onToggleConversation()
                    }
                },
                enabled = enabled || !hasDraft,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (hasDraft || conversing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        colors.panelRaised
                    },
                ),
            ) {
                val tint = if (hasDraft || conversing) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    colors.muted
                }
                if (hasDraft) {
                    SendIcon(
                        tint = tint,
                        modifier = Modifier.semantics { contentDescription = sendLabel },
                    )
                } else {
                    WaveformIcon(
                        tint = tint,
                        modifier = Modifier.semantics { contentDescription = conversationLabel },
                    )
                }
            }
        }
    }
}

/**
 * One attached image, with the control that removes it.
 *
 * Decoded from the data URL rather than from the original Uri: that is what
 * will actually be sent, so the thumbnail shows the downscaling that happened
 * rather than the picture as it sits on disk.
 */
@Composable
private fun AttachmentThumbnail(
    dataUrl: String,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    val colors = LocalRunColors.current
    val bitmap = remember(dataUrl) {
        runCatching {
            val encoded = dataUrl.substringAfter("base64,", "")
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    Box(Modifier.size(56.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelRaised),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.panel)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "\u00d7",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
