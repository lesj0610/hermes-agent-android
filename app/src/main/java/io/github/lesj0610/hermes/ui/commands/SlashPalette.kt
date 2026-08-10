package io.github.lesj0610.hermes.ui.commands

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * The command list that appears when the message starts with a slash.
 *
 * Sits above the composer rather than taking the screen: what is being typed
 * stays visible, because the filter is the typing.
 */
@Composable
fun SlashPalette(
    commands: List<SlashCommand>,
    loading: Boolean,
    error: String?,
    onPick: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp)),
    ) {
        when {
            error != null -> Hint(error, colors.failed)
            loading && commands.isEmpty() -> Hint(
                stringResource(R.string.connection_checking), colors.muted,
            )
            commands.isEmpty() -> Hint(stringResource(R.string.commands_none), colors.muted)
            else -> LazyColumn(
                // Capped so the palette never pushes the composer off screen;
                // the list scrolls inside instead.
                Modifier.heightIn(max = 280.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(commands, key = { it.name }) { command ->
                    CommandRow(command, onPick)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(14.dp),
    )
}

@Composable
private fun CommandRow(command: SlashCommand, onPick: (SlashCommand) -> Unit) {
    val colors = LocalRunColors.current
    val runnable = command.ability != CommandAbility.Unavailable

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = runnable) { onPick(command) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = command.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (runnable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    colors.muted.copy(alpha = 0.55f)
                },
                maxLines = 1,
            )
            Text(
                text = command.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted.copy(alpha = if (runnable) 1f else 0.45f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The reason, not just a dimmed row: "why can't I tap this" is the
        // question a greyed-out control always raises.
        if (!runnable) {
            Text(
                text = stringResource(R.string.commands_desktop_only),
                style = MaterialTheme.typography.labelSmall,
                color = colors.awaiting.copy(alpha = 0.7f),
            )
        }
    }
}
