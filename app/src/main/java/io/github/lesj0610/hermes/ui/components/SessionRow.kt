package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/** What the row's own menu can do to a session. */
enum class SessionAction { Rename, Pin, CopyId, Branch, Export, Archive, Delete }

/**
 * One session, as it appears in the drawer and in search results.
 *
 * The dot carries state, because docked beside the transcript this list is
 * permanently in view and a run needing attention has to read at a glance
 * without stealing focus from the conversation.
 *
 * The menu opens from the trailing button or from a long press. Both, because
 * the drawer is also a docked column on a tablet, where a long press is not the
 * gesture anyone reaches for, and a phone list is where a visible button
 * crowds a title that is already eliding.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: ((SessionAction) -> Unit)? = null,
) {
    val colors = LocalRunColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val dotColor = when {
        session.endReason.isNullOrBlank() && session.endedAt.isNullOrBlank() -> colors.running
        session.endReason == "cancelled" -> colors.muted
        else -> colors.completed
    }

    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (onAction != null) menuOpen = true },
            )
            .padding(start = 18.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StatusDot(dotColor, Modifier.padding(top = 5.dp), size = 6)
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.pinned) PinIcon(modifier = Modifier.size(12.dp))
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
            }
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

        if (onAction != null) {
            Box {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { menuOpen = true })
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MoreIcon(modifier = Modifier.size(16.dp))
                }
                SessionMenu(
                    expanded = menuOpen,
                    pinned = session.pinned,
                    onDismiss = { menuOpen = false },
                    onAction = {
                        menuOpen = false
                        onAction(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onAction: (SessionAction) -> Unit,
) {
    val colors = LocalRunColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_rename)) },
            leadingIcon = { PencilIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.Rename) },
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (pinned) R.string.session_unpin else R.string.session_pin,
                    ),
                )
            },
            leadingIcon = { PinIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.Pin) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_copy_id)) },
            leadingIcon = { CopyIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.CopyId) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_branch)) },
            leadingIcon = { BranchIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.Branch) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_export)) },
            leadingIcon = { ExportIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.Export) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_archive)) },
            leadingIcon = { ArchiveIcon(modifier = Modifier.size(17.dp)) },
            onClick = { onAction(SessionAction.Archive) },
        )
        // Last, and the only one coloured: on this route delete removes the
        // messages and the on-disk transcript, and neither this app nor the
        // desktop can bring them back.
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.session_delete),
                    color = colors.failed,
                )
            },
            // Tinted to match its label: the glyph is the part seen first, and
            // a neutral bin beside red text reads as a different action.
            leadingIcon = { TrashIcon(modifier = Modifier.size(17.dp), tint = colors.failed) },
            onClick = { onAction(SessionAction.Delete) },
        )
    }
}
