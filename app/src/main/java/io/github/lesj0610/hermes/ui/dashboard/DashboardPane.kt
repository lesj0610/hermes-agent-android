package io.github.lesj0610.hermes.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.ui.DashboardState
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * The dashboard-backed panel: profiles and skills.
 *
 * Only the parts of the dashboard that are worth operating from a phone. Its
 * git, MCP and tool-config surfaces are editing tasks that belong on a keyboard,
 * and duplicating cron or sessions here would leave two answers to the same
 * question when the gateway already serves both.
 */
@Composable
fun DashboardPane(
    state: DashboardState,
    profiles: List<Profile>,
    active: ActiveProfile?,
    skills: List<DashboardSkill>,
    onSelectProfile: (Profile) -> Unit,
    onToggleSkill: (DashboardSkill) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is DashboardState.Ready) {
        DashboardUnavailable(state, onRetry, modifier.fillMaxSize())
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfilesCard(profiles, active, onSelectProfile)
        DashboardSkillsCard(skills, onToggleSkill)
    }
}

/**
 * Why the dashboard has nothing to show, and what to do about it.
 *
 * Shared with settings so a dashboard that is off or unreachable says the same
 * thing wherever its content would have been.
 */
@Composable
fun DashboardUnavailable(
    state: DashboardState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (state) {
                    DashboardState.Off -> stringResource(R.string.dashboard_not_configured)
                    DashboardState.Connecting -> stringResource(R.string.connection_checking)
                    is DashboardState.Failed -> stringResource(R.string.dashboard_failed)
                    DashboardState.Ready -> ""
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state is DashboardState.Failed && state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            if (state is DashboardState.Failed) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.dashboard_retry)) }
            }
        }
    }
}

/** The profiles the dashboard offers, and which one new work will use. */
@Composable
fun ProfilesCard(
    profiles: List<Profile>,
    active: ActiveProfile?,
    onSelectProfile: (Profile) -> Unit,
) {
    val colors = LocalRunColors.current
    Group(stringResource(R.string.dashboard_profiles)) {
        // The server distinguishes the sticky default from what the running
        // process is scoped to, and so does this: switching one does not
        // retarget the other, and hiding that would be a lie.
        active?.let {
            Text(
                text = stringResource(R.string.dashboard_profile_running, it.current),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        profiles.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(color = colors.line)
                val isActive = profile.name == active?.active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(profile) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (isActive) colors.completed else colors.muted, size = 7)
                    Column(Modifier.weight(1f)) {
                        Text(text = profile.name, style = MaterialTheme.typography.bodyMedium)
                        profile.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isActive) {
                        Text(
                            text = stringResource(R.string.dashboard_profile_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.completed,
                        )
                    }
                }
            }
    }
}

/** Skills the dashboard can turn on and off. */
@Composable
fun DashboardSkillsCard(
    skills: List<DashboardSkill>,
    onToggleSkill: (DashboardSkill) -> Unit,
) {
    val colors = LocalRunColors.current
    Group(stringResource(R.string.dashboard_skills, skills.count { it.enabled })) {
            skills.forEachIndexed { index, skill ->
                if (index > 0) HorizontalDivider(color = colors.line)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = skill.name, style = MaterialTheme.typography.bodyMedium)
                        val subtitle = skill.description?.takeIf { it.isNotBlank() }
                            ?: skill.provenance
                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { onToggleSkill(skill) },
                    )
                }
            }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
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
