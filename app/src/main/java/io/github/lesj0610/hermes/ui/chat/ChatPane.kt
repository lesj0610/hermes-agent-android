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

@Composable
fun ChatPane(
    state: ChatState,
    onSend: (String) -> Unit,
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
    effort: ReasoningEffort = ReasoningEffort.Default,
    onSelectEffort: (ReasoningEffort) -> Unit = {},
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
        RuntimeBar(
            modelLabel = modelLabel,
            modelChoices = modelChoices,
            onSelectModel = onSelectModel,
            effort = effort,
            onSelectEffort = onSelectEffort,
        )
        Composer(enabled = !state.isBusy, onSend = onSend)
    }
}

/**
 * The model and reasoning-effort choices, directly above the input.
 *
 * They sit here rather than in settings because both are per-turn decisions —
 * a cheap model for a quick question, xhigh for something hard — and the
 * gateway accepts both on the run request. Burying them a screen away would
 * make the choice cost more than the turn it applies to.
 */
@Composable
private fun RuntimeBar(
    modelLabel: String,
    modelChoices: List<ModelChoice>,
    onSelectModel: (ModelChoice) -> Unit,
    effort: ReasoningEffort,
    onSelectEffort: (ReasoningEffort) -> Unit,
) {
    val colors = LocalRunColors.current
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // The chip is inert rather than absent when the gateway cannot serve
            // the inventory: the model in use is still worth showing.
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
                ReasoningEffort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(effortLabel(option)) },
                        onClick = {
                            onSelectEffort(option)
                            effortOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun effortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.Default -> stringResource(R.string.effort_default)
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
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 5,
            shape = RoundedCornerShape(22.dp),
        )
        Button(
            onClick = {
                onSend(draft)
                draft = ""
            },
            enabled = enabled && draft.isNotBlank(),
        ) {
            Text(stringResource(R.string.chat_send))
        }
    }
}
