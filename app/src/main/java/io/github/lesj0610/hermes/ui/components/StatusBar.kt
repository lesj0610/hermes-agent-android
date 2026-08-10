package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.ui.Connection
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import java.util.Locale

/**
 * Tablet status bar, mirroring the desktop shell's own bottom strip.
 *
 * Carries the four items from the desktop statusbar that survive the trip over
 * HTTP: gateway health, the running timer, context usage, and the active model.
 * The desktop's other items (cron, agents, terminal, command center, approval
 * mode) have no representation in the api_server surface, so they are absent
 * rather than faked.
 */
@Composable
fun StatusBar(
    chat: ChatState,
    connection: Connection,
    model: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current

    // Ticks only while a run is in flight, so an idle tablet is not repainting
    // once a second for nothing.
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chat.runStartedAtMillis) {
        val startedAt = chat.runStartedAtMillis
        if (startedAt == null) {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
            delay(1000)
        }
    }

    Column {
        HorizontalDivider(color = colors.line)
        Row(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val (healthColor, healthLabel) = when (connection) {
                is Connection.Connected -> colors.completed to
                    stringResource(R.string.connection_latency, connection.latencyMs)
                Connection.Checking -> colors.muted to stringResource(R.string.connection_checking)
                Connection.NotConfigured -> colors.awaiting to
                    stringResource(R.string.connection_not_configured)
                Connection.Unauthorized -> colors.failed to
                    stringResource(R.string.connection_unauthorized)
                is Connection.Unreachable -> colors.failed to
                    stringResource(R.string.connection_unreachable)
            }

            StatusDot(healthColor, size = 7)
            StatusItem(healthLabel)

            when (val phase = chat.phase) {
                is RunPhase.Running, is RunPhase.AwaitingApproval, is RunPhase.Stopping -> {
                    val label = when (phase) {
                        is RunPhase.AwaitingApproval -> stringResource(R.string.tool_awaiting)
                        is RunPhase.Stopping -> stringResource(R.string.chat_stopping)
                        else -> stringResource(R.string.tool_running)
                    }
                    StatusItem("$label · ${formatElapsed(elapsedSeconds)}", accent = true)
                }
                RunPhase.Idle -> Unit
            }

            chat.lastUsage?.let { usage ->
                StatusItem(
                    stringResource(
                        R.string.status_tokens,
                        usage.inputTokens,
                        usage.outputTokens,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))

            // Omitted while unknown rather than labelled a default: the bar is a
            // readout of what is in effect, and a name it cannot supply is
            // better left off than replaced by one that names no model.
            if (model.isNotBlank()) StatusItem(model)
        }
    }
}

@Composable
private fun StatusItem(text: String, accent: Boolean = false) {
    val colors = LocalRunColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else colors.muted,
    )
}

private fun formatElapsed(seconds: Long): String =
    if (seconds < 60) {
        "${seconds}s"
    } else {
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
