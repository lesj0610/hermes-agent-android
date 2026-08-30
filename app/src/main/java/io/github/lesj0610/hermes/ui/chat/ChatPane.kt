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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.ui.components.ToolCard
import io.github.lesj0610.hermes.ui.markdown.MarkdownText
import io.github.lesj0610.hermes.ui.components.uiErrorText
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Switch
import io.github.lesj0610.hermes.core.REASONING_SCALE
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
import io.github.lesj0610.hermes.ui.components.StopIcon
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.ui.text.style.TextAlign
import io.github.lesj0610.hermes.core.Attachments
import io.github.lesj0610.hermes.ui.components.CameraIcon
import io.github.lesj0610.hermes.ui.components.ChevronIcon
import io.github.lesj0610.hermes.ui.components.PaperclipIcon
import io.github.lesj0610.hermes.ui.components.PhotoIcon
import io.github.lesj0610.hermes.ui.commands.SlashCommand
import io.github.lesj0610.hermes.ui.commands.SlashPalette
import io.github.lesj0610.hermes.ui.commands.filterCommands
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
    /**
     * The slash palette. Empty by default so a preview renders a composer
     * without standing up the catalogue.
     */
    commands: List<SlashCommand> = emptyList(),
    commandsLoading: Boolean = false,
    commandsError: String? = null,
    onSlashOpened: () -> Unit = {},
    onCommand: (SlashCommand) -> Unit = {},
) {
    val colors = LocalRunColors.current
    val listState = rememberLazyListState()

    // Follow the tail while the agent is talking.
    //
    // Keyed on the last item's length as well as the count: a streaming reply
    // grows inside one item, so watching the count alone meant the view sat
    // still through the entire answer and only jumped when a tool card or the
    // next message arrived.
    val tailSize = state.items.lastOrNull().let { last ->
        when (last) {
            is TranscriptItem.AssistantText -> last.text.length
            is TranscriptItem.Reasoning -> last.text.length
            is TranscriptItem.ToolCall -> (last.preview?.length ?: 0) + last.state.ordinal
            else -> 0
        }
    }
    // Only when the tail is already in view. Someone who scrolled up to read an
    // earlier tool result should not be dragged back down every time a token
    // lands.
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(state.items.size, tailSize) {
        if (state.items.isEmpty() || !following) return@LaunchedEffect
        // Not animated: deltas land faster than an animation completes, and
        // each new one cancelled the last, which stalled the scroll mid-way.
        listState.scrollToItem(state.items.lastIndex)
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

        HorizontalDivider(color = colors.line)
        Composer(
            // Typing stays available during a run: that is what turns the
            // action button back into Send, and a disabled box could not.
            enabled = true,
            busy = state.isBusy,
            stopping = state.phase is RunPhase.Stopping,
            onStop = onStop,
            onSend = onSend,
            commands = commands,
            commandsLoading = commandsLoading,
            commandsError = commandsError,
            onSlashOpened = onSlashOpened,
            onCommand = onCommand,
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
 * The level, named the way the agent names it.
 *
 * One label for both the chip and the menu. Two spellings of one setting —
 * "Min" on the chip, "Minimal" in the list — read as two different things, and
 * neither gains anything over the value the gateway itself logs.
 */
@Composable
private fun effortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.Off -> stringResource(R.string.effort_off)
    ReasoningEffort.Minimal -> stringResource(R.string.effort_minimal)
    ReasoningEffort.Low -> stringResource(R.string.effort_low)
    ReasoningEffort.Medium -> stringResource(R.string.effort_medium)
    ReasoningEffort.High -> stringResource(R.string.effort_high)
    ReasoningEffort.XHigh -> stringResource(R.string.effort_xhigh)
    ReasoningEffort.Max -> stringResource(R.string.effort_max)
    ReasoningEffort.Ultra -> stringResource(R.string.effort_ultra)
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

        // Markdown, because that is what the agent writes. Drawn flat, a reply
        // showed its punctuation instead of its structure.
        //
        // The caret still rides on the text rather than being a sibling: it has
        // to sit after the last character, wherever the last block put it.
        is TranscriptItem.AssistantText -> MarkdownText(
            text = if (item.streaming) item.text + " ▉" else item.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )

        is TranscriptItem.Reasoning -> {
            // Collapsed by default, the way the desktop treats
            // display.sections.thinking. Reasoning is how the answer was
            // reached, not the answer, and left open it pushes the reply off
            // the screen — on a phone that is the whole screen.
            var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panel)
                    .clickable { expanded = !expanded }
                    .padding(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChevronIcon(
                        modifier = Modifier
                            .size(13.dp)
                            .rotate(if (expanded) 90f else 0f),
                    )
                    Text(
                        text = stringResource(R.string.chat_reasoning),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    // One line closed, so the row says what the block is about
                    // rather than being a bare disclosure triangle.
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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
private fun Composer(
    enabled: Boolean,
    busy: Boolean,
    stopping: Boolean,
    onStop: () -> Unit,
    onSend: (String, List<String>) -> Unit,
    commands: List<SlashCommand>,
    commandsLoading: Boolean,
    commandsError: String?,
    onSlashOpened: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
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
    var runtimeOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    // Whether the runtime menu is showing its top level or the model list. One
    // menu with two faces rather than two anchors, so the model and the level
    // it applies to are never open at the same time saying different things.
    var pickingModel by remember { mutableStateOf(false) }

    // Held as decoded content rather than as Uris: the permission granted by
    // the picker is not guaranteed to outlive the pick, and the request needs
    // the bytes inline anyway.
    var attachments by remember { mutableStateOf(listOf<Attachment>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val attachLabel = stringResource(R.string.chat_attach)
    val removeLabel = stringResource(R.string.chat_attachment_remove)
    val binaryNotice = stringResource(R.string.chat_attach_binary)
    val failedNotice = stringResource(R.string.chat_attach_failed)
    val noCameraNotice = stringResource(R.string.chat_attach_no_camera)

    val photos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Decoding and re-encoding a photo is far too slow for the main thread.
        scope.launch {
            val encoded = withContext(Dispatchers.IO) {
                uris.mapNotNull { Attachments.toDataUrl(context, it) }
            }
            attachments = attachments + encoded.map { Attachment.Image(it) }
        }
    }

    // Where the camera app is told to write. Held across the launch because the
    // contract reports only success, not the location it wrote to.
    var captureTarget by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget ?: return@rememberLauncherForActivityResult
        captureTarget = null
        if (!saved) return@rememberLauncherForActivityResult
        scope.launch {
            val encoded = withContext(Dispatchers.IO) { Attachments.toDataUrl(context, target) }
            if (encoded == null) notice = failedNotice else attachments = attachments + Attachment.Image(encoded)
        }
    }

    val documents = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val doc = withContext(Dispatchers.IO) { Attachments.readDocument(context, uri) }
            if (doc == null) {
                // Not "failed": the common case is a PDF or an archive, and the
                // reason it cannot go is that the run route has no file part —
                // saying so is more use than saying the read broke.
                notice = binaryNotice
            } else {
                attachments = attachments + Attachment.Document(doc)
            }
        }
    }
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    // Resolved out here: a semantics block is not a composable scope.
    val dictateLabel = stringResource(R.string.voice_dictate)
    val conversationLabel = stringResource(R.string.voice_conversation)
    val sendLabel = stringResource(R.string.chat_send)
    val stopLabel = stringResource(R.string.chat_stop)

    // Dictation lands in the box rather than being sent, so a misheard word can
    // be fixed before it costs a turn. Appended, so it adds to whatever was
    // already typed instead of discarding it.
    LaunchedEffect(dictation) {
        dictation?.let { heard ->
            draft = if (draft.isBlank()) heard else "$draft $heard"
            onDictationConsumed()
        }
    }

    // A leading slash is the palette's trigger, the way it is on the desktop.
    // Only leading: a slash inside a sentence is a path or a date, not a
    // command, and popping a list over those would fight normal typing.
    val slashQuery = draft.takeIf { it.startsWith("/") && !it.contains(' ') }
    LaunchedEffect(slashQuery != null) { if (slashQuery != null) onSlashOpened() }

    if (slashQuery != null) {
        SlashPalette(
            commands = filterCommands(commands, slashQuery),
            loading = commandsLoading,
            error = commandsError?.let { key ->
                when (key) {
                    "dashboard-required" -> stringResource(R.string.commands_needs_dashboard)
                    else -> key
                }
            },
            onPick = { command ->
                draft = ""
                onCommand(command)
            },
            modifier = Modifier.padding(bottom = 8.dp),
        )
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
        notice?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { notice = null }
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.failed,
                )
            }
        }

        if (attachments.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(attachments) { index, attachment ->
                    AttachmentChip(
                        attachment = attachment,
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
            Box {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) {
                            notice = null
                            attachOpen = true
                        }
                        .semantics { contentDescription = attachLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    PlusIcon(modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_camera)) },
                        leadingIcon = { CameraIcon() },
                        onClick = {
                            attachOpen = false
                            // Stamped rather than fixed, so a second shot in the
                            // same message does not overwrite the first.
                            val target = runCatching {
                                Attachments.newCameraTarget(context, System.currentTimeMillis())
                            }.getOrNull()
                            if (target == null) {
                                notice = failedNotice
                            } else {
                                captureTarget = target
                                // A device can report a camera and still have no
                                // app that answers the intent.
                                if (runCatching { camera.launch(target) }.isFailure) {
                                    captureTarget = null
                                    notice = noCameraNotice
                                }
                            }
                        },
                        enabled = hasCamera,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_photo)) },
                        leadingIcon = { PhotoIcon() },
                        onClick = {
                            attachOpen = false
                            photos.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_file)) },
                        leadingIcon = { PaperclipIcon() },
                        onClick = {
                            attachOpen = false
                            // Everything, not text/* — providers report plenty of
                            // readable files as octet-stream, and a filter here
                            // would hide them. What is readable is decided after
                            // the pick, by looking at the bytes.
                            documents.launch(arrayOf("*/*"))
                        },
                    )
                }
            }

            // Model and level on one chip, because they are one decision: the
            // level means nothing without knowing which model it is being asked
            // of. Split across two chips they also took the whole row on a
            // phone, leaving no space for the mic.
            Box {
                RuntimeChip(
                    // "model · Med", the desktop's own status format. The
                    // separator matters at a glance: two words with a space
                    // between them read as one long model name.
                    label = listOf(modelLabel, effortLabel(effort))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    enabled = true,
                    onClick = {
                        pickingModel = false
                        runtimeOpen = true
                    },
                )
                DropdownMenu(
                    expanded = runtimeOpen,
                    onDismissRequest = { runtimeOpen = false },
                ) {
                    if (pickingModel) {
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
                                trailingIcon = {
                                    if (choice.model == modelLabel) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    onSelectModel(choice)
                                    pickingModel = false
                                    runtimeOpen = false
                                },
                            )
                        }
                    } else {
                        // Omitted rather than shown inert when the gateway does
                        // not serve the inventory: a row that opens an empty
                        // list is worse than no row.
                        if (modelChoices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.model_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (modelLabel.isNotBlank()) {
                                            Text(
                                                text = modelLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.muted,
                                            )
                                        }
                                    }
                                },
                                trailingIcon = { Text("›", color = colors.muted) },
                                onClick = { pickingModel = true },
                            )
                            HorizontalDivider(color = colors.line)
                        }
                        // Thinking is a switch, not the bottom rung of the
                        // scale. Off means reasoning is disabled — a different
                        // statement from "reason as little as possible" — and
                        // mixing the two put a state that turns the feature off
                        // in a list of how hard to work.
                        val thinking = effort != ReasoningEffort.Off
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.effort_thinking)) },
                            trailingIcon = {
                                Switch(
                                    checked = thinking,
                                    onCheckedChange = { on ->
                                        onSelectEffort(
                                            if (on) ReasoningEffort.DEFAULT else ReasoningEffort.Off,
                                        )
                                    },
                                )
                            },
                            onClick = {
                                onSelectEffort(
                                    if (thinking) ReasoningEffort.Off else ReasoningEffort.DEFAULT,
                                )
                            },
                        )
                        HorizontalDivider(color = colors.line)
                        Text(
                            text = stringResource(R.string.effort_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                        REASONING_SCALE.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(effortLabel(option)) },
                                enabled = thinking,
                                trailingIcon = {
                                    if (option == effort) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    onSelectEffort(option)
                                    runtimeOpen = false
                                },
                            )
                        }
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

            // One circular button carrying whichever action is actually next.
            //
            // A draft always wins: typing during a run is how you say "not
            // that, this", so the button becomes Send the moment there is
            // something to send and returns to Stop when the box is emptied
            // again. With nothing typed and nothing running, the next thing
            // you do is talk.
            //
            // Stop used to be a bar of its own above the composer, which sat
            // on top of the conversation for the whole run — the reply was
            // covered by the control for interrupting it.
            val hasDraft = draft.isNotBlank() || attachments.isNotEmpty()
            val filled = hasDraft || busy || conversing
            FilledIconButton(
                onClick = {
                    when {
                        hasDraft -> {
                            onSend(
                                composeMessage(draft, attachments),
                                attachments.filterIsInstance<Attachment.Image>().map { it.dataUrl },
                            )
                            draft = ""
                            attachments = emptyList()
                            notice = null
                        }
                        busy -> onStop()
                        else -> onToggleConversation()
                    }
                },
                // Only a stop already in flight is inert; everything else is
                // actionable.
                enabled = !(busy && !hasDraft && stopping),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (filled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        colors.panelRaised
                    },
                ),
            ) {
                val tint = if (filled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    colors.muted
                }
                when {
                    hasDraft -> SendIcon(
                        tint = tint,
                        modifier = Modifier.semantics { contentDescription = sendLabel },
                    )
                    busy -> StopIcon(
                        tint = tint,
                        modifier = Modifier.semantics { contentDescription = stopLabel },
                    )
                    else -> WaveformIcon(
                        tint = tint,
                        modifier = Modifier.semantics { contentDescription = conversationLabel },
                    )
                }
            }
        }
    }
}

/**
 * Something staged for the next message.
 *
 * The two arms travel differently and cannot be collapsed: an image goes as an
 * `image_url` content part, while a document has no part to go as — the run
 * route rejects `file`/`input_file` — so its text is folded into the message
 * body instead.
 */
private sealed interface Attachment {
    data class Image(val dataUrl: String) : Attachment
    data class Document(val doc: Attachments.Document) : Attachment
}

/**
 * The text actually sent: each document, delimited and named, then whatever was
 * typed.
 *
 * The typed line comes last because it is the instruction and the documents are
 * the material — a question placed above a 2000-line log is a question the
 * model has forgotten by the time it reaches the end.
 */
private fun composeMessage(draft: String, attachments: List<Attachment>): String {
    val documents = attachments.filterIsInstance<Attachment.Document>()
    if (documents.isEmpty()) return draft
    return buildString {
        documents.forEach { attached ->
            val doc = attached.doc
            append("--- attached file: ${doc.name} ---\n")
            append(doc.text)
            if (!doc.text.endsWith("\n")) append("\n")
            if (doc.truncated) {
                append("--- truncated at ${Attachments.MAX_DOC_BYTES} bytes ---\n")
            }
            append("--- end of ${doc.name} ---\n\n")
        }
        append(draft)
    }
}

/** One staged attachment, with the control that removes it. */
@Composable
private fun AttachmentChip(
    attachment: Attachment,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    val colors = LocalRunColors.current
    // Decoded from the data URL rather than from the original Uri: that is what
    // will actually be sent, so the thumbnail shows the downscaling that
    // happened rather than the picture as it sits on disk.
    val bitmap = remember(attachment) {
        val image = attachment as? Attachment.Image ?: return@remember null
        runCatching {
            val encoded = image.dataUrl.substringAfter("base64,", "")
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
        } else if (attachment is Attachment.Document) {
            // The name, not a generic file glyph: two logs attached together
            // are otherwise indistinguishable, and removing the wrong one is
            // only noticed after the turn has been spent.
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelRaised)
                    .padding(4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PaperclipIcon(modifier = Modifier.size(14.dp))
                Text(
                    text = attachment.doc.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
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
