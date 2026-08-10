package io.github.lesj0610.hermes.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Gateway state.
 *
 * Toolsets and skills live here rather than in panels of their own: they are
 * read-only lists with nothing to act on, which makes them context about the
 * server rather than a destination worth navigating to.
 *
 * The three cards are also exposed individually, because settings shows them as
 * separate pages behind separate rows. Sharing the composables rather than
 * copying them keeps one rendering of each, so a fix lands in both places.
 */
@Composable
fun GatewayPane(
    health: DetailedHealth?,
    toolsets: List<Toolset>,
    skills: List<Skill>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GatewayStateCard(health)
        ToolsetsCard(toolsets)
        AgentSkillsCard(skills)
    }
}

/** What the gateway process is doing right now. */
@Composable
fun GatewayStateCard(health: DetailedHealth?) {
    val colors = LocalRunColors.current
    Card(stringResource(R.string.gateway_state)) {
        val running = health?.gatewayBusy == true
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (running) colors.running else colors.completed, size = 7)
            Text(
                text = health?.gatewayState ?: health?.status.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Field(stringResource(R.string.gateway_active_agents), health?.activeAgents?.toString())
        Field(stringResource(R.string.settings_version), health?.version)
        Field(stringResource(R.string.gateway_pid), health?.pid?.toString())
        // Present only while the gateway is winding down; noise otherwise.
        health?.exitReason?.takeIf { it.isNotBlank() }?.let {
            Field(stringResource(R.string.gateway_exit_reason), it)
        }
    }
}

/** The tool groups the agent can reach, and whether each is live. */
@Composable
fun ToolsetsCard(toolsets: List<Toolset>) {
    val colors = LocalRunColors.current
    run {
        val enabledCount = toolsets.count { it.enabled }
        Card(pluralStringResource(R.plurals.gateway_toolsets, enabledCount, enabledCount)) {
            if (toolsets.isEmpty()) {
                Text(
                    text = stringResource(R.string.gateway_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            toolsets.forEachIndexed { index, toolset ->
                if (index > 0) HorizontalDivider(color = colors.line)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(
                        when {
                            !toolset.configured -> colors.awaiting
                            toolset.enabled -> colors.completed
                            else -> colors.muted
                        },
                        size = 6,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = toolset.label?.takeIf { it.isNotBlank() } ?: toolset.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        toolset.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }
                    if (toolset.tools.isNotEmpty()) {
                        Text(
                            text = toolset.tools.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                }
            }
        }
    }
}

/** Skills the agent itself reports, as a plain list — nothing to act on here. */
@Composable
fun AgentSkillsCard(skills: List<Skill>) {
    val colors = LocalRunColors.current
    Card(pluralStringResource(R.plurals.gateway_skills, skills.size, skills.size)) {
        if (skills.isEmpty()) {
            Text(
                text = stringResource(R.string.gateway_none),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Text(
            text = skills.joinToString("  ·  ") { it.name },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.muted,
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val colors = LocalRunColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(11.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val colors = LocalRunColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
