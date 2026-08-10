package io.github.lesj0610.hermes.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.components.SearchIcon
import io.github.lesj0610.hermes.ui.components.SessionRow
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Search, as its own page rather than a field wedged into the drawer.
 *
 * A drawer that grows a text box pushes the list it is meant to filter down the
 * screen, and on a phone the keyboard then covers what is left. Given the whole
 * surface, results have room and the field can sit at the bottom, next to the
 * thumb and above the keyboard rather than behind it.
 *
 * The query lives here: it is meaningful only while this page is open, and
 * leaving it in the drawer meant a filter could survive out of sight.
 */
@Composable
fun SearchPane(
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onSelect: (SessionSummary) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Titles are often absent on fresh sessions, so the preview line has to be
    // searchable too or half the list is unreachable by name.
    val results = remember(sessions, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            sessions.filter { session ->
                listOfNotNull(session.title, session.preview, session.model)
                    .any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                query.isBlank() -> SearchHint(R.string.search_hint)
                results.isEmpty() -> SearchHint(R.string.search_no_results)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            onClick = { onSelect(session) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = colors.line)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.sessions_search)) },
                leadingIcon = { SearchIcon() },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = colors.panelRaised,
                    focusedContainerColor = colors.panelRaised,
                ),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                colors = IconButtonDefaults.iconButtonColors(containerColor = colors.panelRaised),
            ) {
                CloseIcon()
            }
        }
    }
}

@Composable
private fun SearchHint(textRes: Int) {
    val colors = LocalRunColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelRaised),
            contentAlignment = Alignment.Center,
        ) {
            SearchIcon(tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** An ✕, for leaving the page. */
@Composable
private fun CloseIcon() {
    val colors = LocalRunColors.current
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val pad = 3.dp.toPx()
        val stroke = 1.8.dp.toPx()
        drawLine(
            colors.muted,
            androidx.compose.ui.geometry.Offset(pad, pad),
            androidx.compose.ui.geometry.Offset(size.width - pad, size.height - pad),
            stroke,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            colors.muted,
            androidx.compose.ui.geometry.Offset(size.width - pad, pad),
            androidx.compose.ui.geometry.Offset(pad, size.height - pad),
            stroke,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
