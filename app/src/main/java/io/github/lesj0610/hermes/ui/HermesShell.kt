package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.RAIL_WIDTH_MAX
import io.github.lesj0610.hermes.core.RAIL_WIDTH_MIN
import io.github.lesj0610.hermes.ui.components.PaneDivider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.ui.chat.ApprovalSheet
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.components.StatusBar
import io.github.lesj0610.hermes.ui.components.ToolCard
import io.github.lesj0610.hermes.ui.sessions.SessionsPane
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.settings.SettingsPane
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesShell(
    viewModel: AppViewModel,
    permissions: PermissionState,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val colors = LocalRunColors.current

    // UI scale is applied outside the measurement below on purpose. Scaling the
    // density changes how many dp the window is worth, so enlarging the UI
    // correctly reports less room and can drop a borderline window back to the
    // single-pane shell — which is what the user asked for by scaling up.
    val baseDensity = LocalDensity.current
    val scaled = remember(baseDensity, settings.uiScale) {
        Density(
            density = baseDensity.density * settings.uiScale,
            fontScale = baseDensity.fontScale * settings.uiScale,
        )
    }

    CompositionLocalProvider(LocalDensity provides scaled) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = resolveShellLayout(
            widthDp = maxWidth.value.toInt(),
            heightDp = maxHeight.value.toInt(),
            mode = settings.layoutMode,
        )
        val expanded = layout != ShellLayout.Single

        // Rail widths live in transient state while a divider is being dragged
        // and are written back once the gesture ends. Persisting per frame would
        // queue a DataStore write per pixel of travel.
        var sessionRail by remember(settings.sessionRailWidth) {
            mutableFloatStateOf(settings.sessionRailWidth)
        }
        var activityRail by remember(settings.activityRailWidth) {
            mutableFloatStateOf(settings.activityRailWidth)
        }
        val commitRails = { viewModel.setRailWidths(sessionRail, activityRail) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when {
                                    expanded -> stringResource(R.string.app_name)
                                    pane == Pane.Sessions -> stringResource(R.string.sessions_title)
                                    pane == Pane.Settings -> stringResource(R.string.settings_title)
                                    else -> stringResource(R.string.nav_chat)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            ConnectionLine(connection)
                        }
                    },
                    navigationIcon = {
                        if (!expanded && pane != Pane.Sessions) {
                            TextButton(onClick = { viewModel.show(Pane.Sessions) }) {
                                Text(stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = {
                        settings.model.takeIf { it.isNotBlank() }?.let { model ->
                            Text(
                                text = model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // A run belongs to a session, so starting a fresh one is
                        // reachable wherever the session list is.
                        if (expanded || pane == Pane.Sessions) {
                            TextButton(onClick = { viewModel.openSession(null) }) {
                                Text(stringResource(R.string.action_new_session))
                            }
                        }
                        TextButton(onClick = { viewModel.show(Pane.Settings) }) {
                            Text(stringResource(R.string.nav_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            val content = Modifier.fillMaxSize().padding(padding)

            if (expanded) {
                // Three panes fill the height; the status strip pins to the
                // bottom edge, matching the desktop shell.
                Column(content) {
                    Row(Modifier.weight(1f)) {
                        SessionsPane(
                            sessions = sessions,
                            selectedId = chat.sessionId,
                            onSelect = viewModel::openSession,
                            modifier = Modifier.width(sessionRail.dp),
                        )
                        PaneDivider(
                            onDelta = { delta ->
                                sessionRail = (sessionRail + delta)
                                    .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
                            },
                            onCommit = commitRails,
                        )

                        Column(Modifier.weight(1f)) {
                            if (pane == Pane.Settings) {
                                SettingsPane(
                                    settings = settings,
                                    connection = connection,
                                    models = models,
                                    onSaveServer = viewModel::saveServer,
                                    onSelectModel = viewModel::setModel,
                                    onSelectLanguage = viewModel::setLanguage,
                                    onToggleApprovals = viewModel::setNotifyApprovals,
                                    onToggleCompletion = viewModel::setNotifyCompletion,
                                    onSelectLayoutMode = viewModel::setLayoutMode,
                                    onSetUiScale = viewModel::setUiScale,
                                    permissions = permissions,
                                    onRequestNotifications = onRequestNotifications,
                                    onRequestBackground = onRequestBackground,
                                )
                            } else {
                                ChatPane(
                                    state = chat,
                                    onSend = viewModel::send,
                                    onStop = viewModel::stop,
                                    onDismissError = viewModel::dismissError,
                                )
                            }
                        }

                        // The activity rail is the first thing to go when the
                        // window is only medium-wide: it is the least essential
                        // of the three, and the transcript needs the room more.
                        if (layout == ShellLayout.Triple) {
                            PaneDivider(
                                onDelta = { delta ->
                                    activityRail = (activityRail - delta)
                                        .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
                                },
                                onCommit = commitRails,
                            )
                            ActivityRail(chat.items, Modifier.width(activityRail.dp))
                        }
                    }

                    StatusBar(chat = chat, connection = connection, model = settings.model)
                }
            } else {
                when (pane) {
                    Pane.Sessions -> SessionsPane(
                        sessions = sessions,
                        selectedId = chat.sessionId,
                        onSelect = viewModel::openSession,
                        modifier = content,
                    )
                    Pane.Chat -> ChatPane(
                        state = chat,
                        onSend = viewModel::send,
                        onStop = viewModel::stop,
                        onDismissError = viewModel::dismissError,
                        modifier = content,
                    )
                    Pane.Settings -> SettingsPane(
                        settings = settings,
                        connection = connection,
                        models = models,
                        onSaveServer = viewModel::saveServer,
                        onSelectModel = viewModel::setModel,
                        onSelectLanguage = viewModel::setLanguage,
                        onToggleApprovals = viewModel::setNotifyApprovals,
                        onToggleCompletion = viewModel::setNotifyCompletion,
                        onSelectLayoutMode = viewModel::setLayoutMode,
                        onSetUiScale = viewModel::setUiScale,
                        permissions = permissions,
                        onRequestNotifications = onRequestNotifications,
                        onRequestBackground = onRequestBackground,
                        modifier = content,
                    )
                }
            }
        }

        // The sheet sits above whichever layout is showing: an approval blocks
        // the run regardless of which pane has focus.
        chat.pendingApproval?.let { approval ->
            ApprovalSheet(approval = approval, onChoice = viewModel::respondToApproval)
        }
    }
    }
}

@Composable
private fun ConnectionLine(connection: Connection) {
    val colors = LocalRunColors.current
    val (color, label) = when (connection) {
        is Connection.Connected -> colors.completed to stringResource(R.string.connection_connected)
        Connection.Checking -> colors.muted to stringResource(R.string.connection_checking)
        Connection.NotConfigured -> colors.awaiting to stringResource(R.string.connection_not_configured)
        Connection.Unauthorized -> colors.failed to stringResource(R.string.connection_unauthorized)
        is Connection.Unreachable -> colors.failed to stringResource(R.string.connection_unreachable)
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * Tablet-only right rail.
 *
 * The desktop app puts files, a terminal and review here. None of those exist
 * over HTTP — they are Electron-local — so the rail shows what the gateway does
 * expose: the tool calls of the open session, newest last.
 */
@Composable
private fun ActivityRail(items: List<TranscriptItem>, modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    val tools = items.filterIsInstance<TranscriptItem.ToolCall>()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(
            text = stringResource(R.string.rail_activity),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(14.dp),
        )
        HorizontalDivider(color = colors.line)

        if (tools.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.rail_activity_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(tools, key = { it.key }) { tool -> ToolCard(tool) }
            }
        }
    }
}
