package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.RailPanel
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * The three-line menu affordance, drawn rather than imported.
 *
 * Material's icon artifacts are a separate dependency, and this app needs
 * exactly one glyph from them.
 */
@Composable
fun HamburgerIcon(modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    Column(
        modifier.size(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.muted),
            )
            if (index < 2) Box(Modifier.height(4.dp))
        }
    }
}

@Composable
fun railPanelLabel(panel: RailPanel): String = when (panel) {
    RailPanel.None -> androidx.compose.ui.res.stringResource(R.string.rail_none)
    RailPanel.Sessions -> androidx.compose.ui.res.stringResource(R.string.sessions_title)
    RailPanel.Activity -> androidx.compose.ui.res.stringResource(R.string.rail_activity)
    RailPanel.Cron -> androidx.compose.ui.res.stringResource(R.string.cron_title)
    RailPanel.Gateway -> androidx.compose.ui.res.stringResource(R.string.gateway_title)
    RailPanel.Dashboard -> androidx.compose.ui.res.stringResource(R.string.dashboard_title)
}

/**
 * Drawer contents: identity, destinations, the session list, and a pinned
 * bottom row.
 *
 * The shape follows what a chat app's drawer is actually for. Destinations are
 * few and fixed at the top; the session list takes the remaining height and
 * scrolls, because switching conversations is the thing done most often; and
 * "new chat" plus "settings" stay pinned at the bottom where they are reachable
 * without scrolling past a long history.
 *
 * An earlier version listed sessions as a single destination alongside the rest,
 * which buried the one list the drawer exists to show.
 */
@Composable
fun DrawerContent(
    modelLabel: String,
    connectionLabel: String,
    connectionColor: androidx.compose.ui.graphics.Color,
    destinations: List<Pair<String, Boolean>>,
    onDestination: (Int) -> Unit,
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onSession: (SessionSummary) -> Unit,
    onAllSessions: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    arrangeLabel: String?,
    onArrange: () -> Unit,
) {
    val colors = LocalRunColors.current

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp, end = 16.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.app_name),
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

        destinations.forEachIndexed { index, (label, selected) ->
            DrawerDestination(label = label, selected = selected) { onDestination(index) }
        }

        HorizontalDivider(color = colors.line, modifier = Modifier.padding(top = 6.dp))
        DrawerSection(androidx.compose.ui.res.stringResource(R.string.sessions_title))

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
                            ?: androidx.compose.ui.res.stringResource(R.string.sessions_untitled),
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
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.drawer_all_sessions),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAllSessions() }
                        .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                )
            }
        }

        HorizontalDivider(color = colors.line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onNewChat) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_new_session))
            }
            Row {
                // Only meaningful when there are rails to arrange.
                arrangeLabel?.let {
                    TextButton(onClick = onArrange) { Text(it) }
                }
                TextButton(onClick = onSettings) {
                    Text(androidx.compose.ui.res.stringResource(R.string.nav_settings))
                }
            }
        }
    }
}

/** One destination row. */
@Composable
fun DrawerDestination(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRunColors.current
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        selected = selected,
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
