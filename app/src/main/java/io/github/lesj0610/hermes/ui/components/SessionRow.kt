package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * One session, as it appears in the drawer and in search results.
 *
 * The dot carries state, because docked beside the transcript this list is
 * permanently in view and a run needing attention has to read at a glance
 * without stealing focus from the conversation.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    val dotColor = when {
        session.endReason.isNullOrBlank() && session.endedAt.isNullOrBlank() -> colors.running
        session.endReason == "cancelled" -> colors.muted
        else -> colors.completed
    }

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatusDot(dotColor, Modifier.padding(top = 5.dp), size = 6)
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.sessions_untitled),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = session.preview?.takeIf { it.isNotBlank() }
                ?: session.toolCallCount?.let {
                    pluralStringResource(R.plurals.sessions_tool_count, it, it)
                }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
