package io.github.lesj0610.hermes.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * The session rail.
 *
 * One dot per row carries state, because on a tablet this list sits beside the
 * transcript permanently and a row that needs attention has to read at a glance
 * without stealing focus from the conversation.
 */
@Composable
fun SessionsPane(
    sessions: List<SessionSummary>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var query by remember { mutableStateOf("") }

    // Titles are often absent on fresh sessions, so the preview line has to be
    // searchable too or half the list is unreachable by name.
    val visible = remember(sessions, query) {
        if (query.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                val haystack = listOfNotNull(session.title, session.preview, session.model)
                haystack.any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.sessions_search)) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
        )
        HorizontalDivider(color = colors.line)

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sessions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedId,
                    onClick = { onSelect(session.id) },
                )
                HorizontalDivider(color = colors.line)
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalRunColors.current
    val dotColor = when {
        session.endReason.isNullOrBlank() && session.endedAt.isNullOrBlank() -> colors.running
        session.endReason == "cancelled" -> colors.muted
        else -> colors.completed
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.padding(start = 0.dp) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(dotColor, Modifier.padding(top = 5.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.sessions_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = session.preview?.takeIf { it.isNotBlank() }
                ?: session.toolCallCount?.let { pluralStringResource(R.plurals.sessions_tool_count, it, it) }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        session.model?.let { model ->
            Text(
                text = model,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}
