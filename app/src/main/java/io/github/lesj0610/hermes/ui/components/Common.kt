package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.data.UiError
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/** Resolves an engine-side error into translated text at render time. */
@Composable
fun uiErrorText(error: UiError): String = when (error) {
    UiError.Unauthorized -> stringResource(R.string.connection_unauthorized_help)
    UiError.RunFailed -> stringResource(R.string.error_run_failed)
    is UiError.Raw -> error.text
}

/** A run-state dot. Colour is the whole message, so it is never the accent by accident. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/**
 * A single tool invocation.
 *
 * State is carried by the left stripe rather than an icon: the stripe survives
 * being glanced at from across a room and does not compete with the text.
 */
@Composable
fun ToolCard(item: TranscriptItem.ToolCall, modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    val accent = when (item.state) {
        ToolState.Running -> colors.running
        ToolState.AwaitingApproval -> colors.awaiting
        ToolState.Completed -> colors.completed
        ToolState.Failed -> colors.failed
    }
    val label = when (item.state) {
        ToolState.Running -> stringResource(R.string.tool_running)
        ToolState.AwaitingApproval -> stringResource(R.string.tool_awaiting)
        ToolState.Completed -> stringResource(R.string.tool_completed)
        ToolState.Failed -> stringResource(R.string.tool_failed)
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(8.dp)),
    ) {
        // Stripe + body. The stripe is a filled box rather than a border side so
        // it hugs the card edge exactly; IntrinsicSize.Min makes it match the
        // body's height whatever the preview text does.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(Modifier.padding(start = 9.dp, top = 7.dp, end = 9.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.tool,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.durationSeconds
                            ?.let { "$label · " + stringResource(R.string.tool_duration, it) }
                            ?: label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                val body = item.error?.takeIf { it.isNotBlank() } ?: item.preview
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        if (item.state == ToolState.Running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = colors.line,
            )
        }
    }
}
