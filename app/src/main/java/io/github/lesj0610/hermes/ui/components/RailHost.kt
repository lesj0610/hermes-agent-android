package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.RailPanel
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
 * Wraps a rail's content, adding the editing controls when layout edit mode is
 * on.
 *
 * The strip only exists while editing. A permanent header on every rail would
 * cost vertical space on the screens that have least of it, to serve a change
 * made once and then left alone for months.
 */
@Composable
fun RailHost(
    panel: RailPanel,
    editing: Boolean,
    onCycle: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalRunColors.current

    Column(modifier) {
        if (editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panelRaised)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = railPanelLabel(panel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCycle) {
                    Text(
                        text = stringResource(R.string.layout_swap_panel),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onHide) {
                    Text(
                        text = stringResource(R.string.layout_hide),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            HorizontalDivider(color = colors.line)
        }
        content()
    }
}
