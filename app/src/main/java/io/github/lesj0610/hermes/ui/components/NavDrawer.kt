package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.RailPanel
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

@Composable
fun railPanelLabel(panel: RailPanel): String = when (panel) {
    RailPanel.None -> stringResource(R.string.rail_none)
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
 * The drawer: identity, destinations, the session list, and a pinned bottom row.
 *
 * This is the app's session column, whether it is floating over the transcript
 * or docked beside it, which is why the list here carries the search field and
 * the preview line rather than a separate screen doing so. An earlier build had
 * both — a short list in the drawer and a full one behind "see all" — and the
 * second was a detour through a screen showing what the drawer already had open.
 *
 * The bands are fixed: destinations at the top, the list taking the remaining
 * height and scrolling, and the controls pinned at the bottom where a long
 * history cannot push them away.
 *
 * [pinned] is null on a single-column window, where docking is not a concept and
 * the toggle is absent. Where it is a concept but the current width cannot hold
 * it, [pinEnabled] is false: the control stays visible and dimmed, and pressing
 * it says why rather than doing nothing — a control that vanishes as the window
 * narrows is harder to understand than one that explains itself.
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
    onSearch: () -> Unit,
    onNewChat: () -> Unit,
    settingsSelected: Boolean,
    onSettings: () -> Unit,
    pinned: Boolean?,
    pinEnabled: Boolean,
    onTogglePin: () -> Unit,
    arrangeLabel: String?,
    arranging: Boolean,
    onArrange: () -> Unit,
) {
    val colors = LocalRunColors.current
    // Resolved here rather than inside the semantics blocks, which are not
    // composable scopes.
    val newSessionLabel = stringResource(R.string.action_new_session)
    val settingsLabel = stringResource(R.string.nav_settings)
    val searchLabel = stringResource(R.string.sessions_search)
    val pinLabel = stringResource(
        if (pinned == true) R.string.drawer_unpin else R.string.drawer_pin,
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
                        text = listOf(modelLabel, connectionLabel)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            }
            IconButton(onClick = onSearch) {
                SearchIcon(modifier = Modifier.semantics { contentDescription = searchLabel })
            }
            if (pinned != null) {
                IconButton(onClick = onTogglePin) {
                    LayoutIcon(
                        tint = when {
                            !pinEnabled -> colors.muted.copy(alpha = 0.35f)
                            pinned -> MaterialTheme.colorScheme.primary
                            else -> colors.muted
                        },
                        modifier = Modifier.semantics { contentDescription = pinLabel },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.line)

        destinations.forEachIndexed { index, entry ->
            DrawerDestination(entry) { onDestination(index) }
        }

        HorizontalDivider(color = colors.line, modifier = Modifier.padding(top = 6.dp))

        if (sessions.isEmpty()) {
            // weight(1f) here too, so an empty list still holds the bottom row
            // down at the edge instead of letting it float up mid-sheet.
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == selectedSessionId,
                        onClick = { onSession(session) },
                    )
                }
            }
        }

        HorizontalDivider(color = colors.line)
        // Icons, not labels: this row is fixed, tapped by position rather than
        // read, and three words across a 300dp column crowded out the space that
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
                // Only meaningful where there are columns to arrange.
                arrangeLabel?.let { label ->
                    IconButton(onClick = onArrange) {
                        SlidersIcon(
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

