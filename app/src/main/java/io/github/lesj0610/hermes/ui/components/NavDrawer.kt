package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.RailPanel
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

@Composable
fun railPanelLabel(panel: RailPanel): String = when (panel) {
    RailPanel.None -> stringResource(R.string.rail_none)
    RailPanel.Sessions -> stringResource(R.string.sessions_title)
    RailPanel.Activity -> stringResource(R.string.rail_activity)
    RailPanel.Cron -> stringResource(R.string.cron_title)
    RailPanel.Gateway -> stringResource(R.string.gateway_title)
    RailPanel.Dashboard -> stringResource(R.string.dashboard_title)
}

/**
 * One row in the drawer's destination group.
 *
 * The icon is a lambda taking its tint rather than a finished composable, so a
 * row can be drawn selected without the caller having to know which colour the
 * drawer uses for that.
 */
data class DrawerEntry(
    val label: String,
    val selected: Boolean,
    val icon: @Composable (Color) -> Unit,
)

/**
 * Drawer contents: identity, destinations, the session list, and a pinned
 * bottom row.
 *
 * The shape follows what a chat app's drawer is actually for. Destinations are
 * few and fixed at the top; the session list takes the remaining height and
 * scrolls, because switching conversations is the thing done most often; and
 * new session, arrange and settings stay pinned at the bottom where they are
 * reachable without scrolling past a long history.
 *
 * An earlier version listed sessions as a single destination alongside the rest,
 * which buried the one list the drawer exists to show.
 */
@Composable
fun DrawerContent(
    modelLabel: String,
    connectionLabel: String,
    connectionColor: Color,
    destinations: List<DrawerEntry>,
    onDestination: (Int) -> Unit,
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onSession: (SessionSummary) -> Unit,
    onAllSessions: () -> Unit,
    onNewChat: () -> Unit,
    settingsSelected: Boolean,
    onSettings: () -> Unit,
    arrangeLabel: String?,
    arranging: Boolean,
    onArrange: () -> Unit,
) {
    val colors = LocalRunColors.current
    // Resolved here rather than inside the semantics blocks, which are not
    // composable scopes.
    val newSessionLabel = stringResource(R.string.action_new_session)
    val settingsLabel = stringResource(R.string.nav_settings)

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp, end = 16.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                StatusDot(connectionColor, size = 6)
                Text(
                    text = listOf(modelLabel, connectionLabel).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        }
        HorizontalDivider(color = colors.line)

        destinations.forEachIndexed { index, entry ->
            DrawerDestination(entry) { onDestination(index) }
        }

        HorizontalDivider(color = colors.line, modifier = Modifier.padding(top = 6.dp))
        DrawerSection(stringResource(R.string.sessions_title))

        // weight(1f) so the history scrolls inside the drawer instead of pushing
        // the bottom row off-screen once it grows.
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(sessions, key = { it.id }) { session ->
                val running = session.endedAt.isNullOrBlank() && session.endReason.isNullOrBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSession(session) }
                        .padding(start = 20.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (running) colors.running else colors.muted, size = 6)
                    Text(
                        text = session.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.sessions_untitled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (session.id == selectedSessionId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The rows above carry a title and nothing else. Preview text, tool
            // counts and end reasons live on the full screen, which this reaches
            // — otherwise that detail would have nowhere left to be seen.
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAllSessions() }
                        .padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ListIcon(tint = colors.muted, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.drawer_all_sessions),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            }
        }

        HorizontalDivider(color = colors.line)
        // Icons, not labels: this row is fixed, tapped by position rather than
        // read, and three words across a 300dp sheet crowded out the space that
        // makes the row read as pinned.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNewChat) {
                NewSessionIcon(
                    modifier = Modifier.semantics { contentDescription = newSessionLabel },
                )
            }
            Row {
                // Only meaningful where there are rails to arrange.
                arrangeLabel?.let { label ->
                    IconButton(onClick = onArrange) {
                        LayoutIcon(
                            tint = if (arranging) MaterialTheme.colorScheme.primary else colors.muted,
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
                IconButton(onClick = onSettings) {
                    SettingsIcon(
                        tint = if (settingsSelected) MaterialTheme.colorScheme.primary else colors.muted,
                        modifier = Modifier.semantics { contentDescription = settingsLabel },
                    )
                }
            }
        }
    }
}

/** One destination row: icon, then label. */
@Composable
fun DrawerDestination(
    entry: DrawerEntry,
    onClick: () -> Unit,
) {
    val colors = LocalRunColors.current
    val tint = if (entry.selected) MaterialTheme.colorScheme.primary else colors.muted
    NavigationDrawerItem(
        icon = { entry.icon(tint) },
        label = { Text(entry.label, style = MaterialTheme.typography.bodyMedium) },
        selected = entry.selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.panelRaised,
            unselectedContainerColor = MaterialTheme.colorScheme.background,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Section label inside the drawer. */
@Composable
fun DrawerSection(title: String) {
    val colors = LocalRunColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}
