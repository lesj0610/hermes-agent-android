package io.github.lesj0610.hermes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.RAIL_WIDTH_MAX
import io.github.lesj0610.hermes.core.RAIL_WIDTH_MIN
import io.github.lesj0610.hermes.core.RailPanel
import io.github.lesj0610.hermes.core.SystemPermissions
import io.github.lesj0610.hermes.ui.components.PaneDivider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import io.github.lesj0610.hermes.ui.components.ChatIcon
import io.github.lesj0610.hermes.ui.components.ClockIcon
import io.github.lesj0610.hermes.ui.components.DocumentIcon
import io.github.lesj0610.hermes.ui.components.FolderIcon
import io.github.lesj0610.hermes.ui.components.DrawerContent
import io.github.lesj0610.hermes.ui.components.DrawerEntry
import io.github.lesj0610.hermes.ui.components.SessionAction
import io.github.lesj0610.hermes.ui.components.GridIcon
import io.github.lesj0610.hermes.ui.components.ServerIcon
import io.github.lesj0610.hermes.ui.components.SettingsIcon
import io.github.lesj0610.hermes.ui.components.HamburgerIcon
import io.github.lesj0610.hermes.ui.components.RailHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.net.ModelChoice
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.chat.ApprovalSheet
import io.github.lesj0610.hermes.ui.artifacts.ArtifactsPane
import io.github.lesj0610.hermes.ui.projects.ProjectsPane
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.cron.CronPane
import io.github.lesj0610.hermes.ui.dashboard.DashboardPane
import io.github.lesj0610.hermes.ui.gateway.GatewayPane
import io.github.lesj0610.hermes.ui.search.SearchPane
import io.github.lesj0610.hermes.ui.components.StatusBar
import io.github.lesj0610.hermes.ui.components.ToolCard
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.settings.SettingsPane
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesShell(
    viewModel: AppViewModel,
    permissions: PermissionState,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val modelChoices by viewModel.modelChoices.collectAsStateWithLifecycle()
    val serverModel by viewModel.serverModel.collectAsStateWithLifecycle()
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()
    val projectsPayload by viewModel.projects.collectAsStateWithLifecycle()
    val projectsBusy by viewModel.projectsBusy.collectAsStateWithLifecycle()
    val projectsError by viewModel.projectsError.collectAsStateWithLifecycle()
    val sessionNotice by viewModel.sessionNotice.collectAsStateWithLifecycle()
    val artifactScan by viewModel.artifactScan.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val conversing by viewModel.voiceConversing.collectAsStateWithLifecycle()
    val dictation by viewModel.dictation.collectAsStateWithLifecycle()
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val toolsets by viewModel.toolsets.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val dashboardState by viewModel.dashboard.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val dashboardSkills by viewModel.dashboardSkills.collectAsStateWithLifecycle()
    val colors = LocalRunColors.current

    // Absence of a capability report is not a denial: an older gateway that
    // does not answer /v1/capabilities still serves these routes.
    val showCron = capabilities?.jobsAdmin != false
    val showGateway = capabilities?.healthDetailed != false

    val showDashboard = settings.dashboardConfigured
    val railOptions = railPanelOptions(showCron, showGateway, showDashboard)
    val railPanel = effectiveRailPanel(settings.railPanel, showCron, showGateway, showDashboard)

    // Edit mode is transient on purpose: it is a mode you enter, change
    // something in, and leave. Persisting it would greet the next launch with
    // controls the user is done with.
    var editingLayout by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dictate = {
        if (SystemPermissions.canRecordAudio(context)) viewModel.dictate() else onRequestMicrophone()
    }

    // Search takes the whole surface rather than a slot in the drawer, so it is
    // a mode of the shell rather than of any pane.
    var searchOpen by remember { mutableStateOf(false) }

    val railContent: @Composable (RailPanel) -> Unit = { panel ->
        when (panel) {
            RailPanel.None -> Unit
            RailPanel.Activity -> ActivityRail(chat.items)
            RailPanel.Cron -> CronPane(
                jobs = jobs,
                onPause = viewModel::pauseJob,
                onResume = viewModel::resumeJob,
                onRun = viewModel::runJob,
                onDelete = viewModel::deleteJob,
            )
            RailPanel.Gateway -> GatewayPane(
                health = health,
                toolsets = toolsets,
                skills = skills,
            )
            RailPanel.Dashboard -> DashboardPane(
                state = dashboardState,
                profiles = profiles,
                active = activeProfile,
                skills = dashboardSkills,
                onSelectProfile = { viewModel.setActiveProfile(it.name) },
                onToggleSkill = viewModel::toggleSkill,
                onRetry = viewModel::refreshDashboard,
            )
        }
    }

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

        // Column widths live in transient state while a divider is being dragged
        // and are written back once the gesture ends. Persisting per frame would
        // queue a DataStore write per pixel of travel.
        var drawerWidth by remember(settings.drawerWidth) {
            mutableFloatStateOf(settings.drawerWidth)
        }
        var railWidth by remember(settings.railWidth) {
            mutableFloatStateOf(settings.railWidth)
        }
        val commitWidths = { viewModel.setColumnWidths(drawerWidth, railWidth) }

        // Docking is judged against the measured width, not the tier. Forcing
        // tablet mode on a 690dp foldable asks three columns of a window with
        // room for two, and answering by pane count produced three slivers with
        // the transcript wrapping one character per line.
        val widthDp = maxWidth.value
        val pinnable = expanded
        val canDock = pinnable && canDockDrawer(widthDp, drawerWidth)
        val docked = canDock && settings.drawerPinned

        // The rail yields to the drawer, never the transcript. Docking on a
        // two-column window drops the rail rather than squeezing the
        // conversation, and undocking brings it straight back.
        val occupied = if (docked) drawerWidth else 0f
        val showRail = expanded &&
            railPanel != RailPanel.None &&
            railFits(widthDp, occupied, railWidth)

        // Dragging must not be able to starve the transcript either.
        val drawerMax = (widthDp - MIN_CENTER_WIDTH_DP).coerceAtMost(RAIL_WIDTH_MAX)
        val railMax = (widthDp - occupied - MIN_CENTER_WIDTH_DP).coerceAtMost(RAIL_WIDTH_MAX)

        val notices = remember { SnackbarHostState() }
        val pinBlockedNotice = stringResource(R.string.drawer_pin_unavailable)

        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val drawerScope = rememberCoroutineScope()
        val closeDrawer = { drawerScope.launch { drawerState.close() }; Unit }

        // Undocked, the drawer covers part of the window and no more — the
        // transcript stays visible behind the scrim, which is what tells you the
        // conversation is still there and one tap away.
        val sheetWidth = (maxWidth * 0.82f).coerceAtMost(340.dp)

        val destinations = drawerDestinations(showCron, showDashboard)
        val (connColor, connLabel) = connectionStatus(connection)

        // What the next turn will actually run on: the override if one is set,
        // otherwise the model the gateway reports it is on.
        //
        // The last resort is /v1/models, and only when it lists exactly one
        // entry — that is the single virtual alias an OpenAI-compatible client
        // sees, so it names the right thing. With several entries there is no
        // way to tell which one is current, and guessing would put a wrong
        // model name next to the reasoning level.
        val activeModel = settings.model
            .ifBlank { serverModel }
            .ifBlank { models.singleOrNull()?.id.orEmpty() }

        // The composer's picker, with /v1/models standing in when the provider
        // inventory is unavailable.
        //
        // The inventory route builds provider catalogues, fetches pricing and
        // probes custom endpoints — it is slow and it is allowed to fail, and
        // when it did the composer had no models to offer at all. /v1/models is
        // the route that proved the connection a moment earlier, so if anything
        // is listable, it is listed.
        val chatModels = if (modelChoices.isNotEmpty()) {
            modelChoices
        } else {
            models.map { entry ->
                ModelChoice(
                    provider = "",
                    providerLabel = entry.ownedBy.orEmpty(),
                    model = entry.id,
                    reasoning = false,
                )
            }
        }


        // Session actions from the row menu. They live here rather than in the
        // drawer because two of them leave the app's own surfaces — the
        // clipboard and the share sheet — and two need a confirmation the row
        // cannot host.
        var renaming by remember { mutableStateOf<SessionSummary?>(null) }
        var deleting by remember { mutableStateOf<SessionSummary?>(null) }
        val clipboardCopied = stringResource(R.string.session_copied)
        val onSessionAction: (SessionSummary, SessionAction) -> Unit = { session, action ->
            when (action) {
                SessionAction.Rename -> renaming = session
                SessionAction.Pin -> viewModel.setSessionPinned(session.id, !session.pinned)
                SessionAction.CopyId -> {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(session.id, session.id),
                    )
                    drawerScope.launch { notices.showSnackbar(clipboardCopied) }
                }
                SessionAction.Branch -> viewModel.branchSession(session.id)
                SessionAction.Export -> drawerScope.launch {
                    // Built here and handed to the system sheet: the app has no
                    // business deciding where a transcript should end up.
                    val text = viewModel.exportSession(session)
                    if (text != null) {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TITLE, session.title.orEmpty())
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                null,
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                SessionAction.Archive -> viewModel.archiveSession(session.id)
                SessionAction.Delete -> deleting = session
            }
        }

        // Docked or floating, the same contents. Only the frame differs.
        val drawer: @Composable () -> Unit = {
            DrawerContent(
                modelLabel = activeModel,
                connectionLabel = connLabel,
                connectionColor = connColor,
                destinations = destinations.map { target ->
                    DrawerEntry(
                        label = paneLabel(target),
                        selected = pane == target,
                        icon = { tint -> PaneIcon(target, tint) },
                    )
                },
                onDestination = { index ->
                    viewModel.show(destinations[index])
                    if (!docked) closeDrawer()
                },
                sessions = sessions,
                selectedSessionId = chat.sessionId,
                onSessionAction = onSessionAction,
                onSession = { session ->
                    viewModel.openSession(session.id)
                    viewModel.show(Pane.Chat)
                    if (!docked) closeDrawer()
                },
                onSearch = {
                    searchOpen = true
                    if (!docked) closeDrawer()
                },
                onNewChat = {
                    viewModel.openSession(null)
                    viewModel.show(Pane.Chat)
                    if (!docked) closeDrawer()
                },
                settingsSelected = pane == Pane.Settings,
                onSettings = {
                    viewModel.show(Pane.Settings)
                    if (!docked) closeDrawer()
                },
                pinned = if (pinnable) docked else null,
                pinEnabled = canDock,
                onTogglePin = {
                    if (canDock) {
                        viewModel.setDrawerPinned(!settings.drawerPinned)
                        closeDrawer()
                    } else {
                        // Refusing silently would read as a dead button. The
                        // stored preference is left alone: this window is too
                        // narrow, not the user's choice wrong.
                        drawerScope.launch {
                            notices.showSnackbar(pinBlockedNotice)
                        }
                    }
                },
                // Arranging columns is meaningless where there is only one, so
                // the control is absent rather than present and inert.
                arrangeLabel = if (expanded) {
                    stringResource(
                        if (editingLayout) R.string.layout_done else R.string.layout_edit,
                    )
                } else {
                    null
                },
                arranging = editingLayout,
                onArrange = {
                    editingLayout = !editingLayout
                    if (!docked) closeDrawer()
                },
            )
        }

        val body: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = paneLabel(pane),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            ConnectionLine(connection)
                        }
                    },
                    navigationIcon = {
                        // Nothing to open while the drawer is docked — the menu
                        // is already on screen, and a button that reopens what
                        // you are looking at reads as broken.
                        if (!docked) {
                            IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                HamburgerIcon()
                            }
                        }
                    },
                    // No model here: the composer's chip already names it, next
                    // to the reasoning level it applies to, which is where the
                    // decision is actually made. A second copy in the corner
                    // said the same thing further from the point of use.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            val content = Modifier.fillMaxSize().padding(padding)

            if (expanded) {
                // The columns fill the height; the status strip pins to the
                // bottom edge, matching the desktop shell.
                Column(content) {
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.weight(1f)) {
                            if (pane == Pane.Cron) {
                                CronPane(
                                    jobs = jobs,
                                    onPause = viewModel::pauseJob,
                                    onResume = viewModel::resumeJob,
                                    onRun = viewModel::runJob,
                                    onDelete = viewModel::deleteJob,
                                )
                            } else if (pane == Pane.Projects) {
                                ProjectsPane(
                                    payload = projectsPayload,
                                    busy = projectsBusy,
                                    error = projectsError,
                                    dashboardConfigured = settings.dashboardConfigured,
                                    onLoad = viewModel::loadProjects,
                                    onCreate = viewModel::createProject,
                                    onSetActive = viewModel::setActiveProject,
                                    onRename = viewModel::renameProject,
                                    onArchive = viewModel::archiveProject,
                                    onBrowse = viewModel::browseGateway,
                                )
                            } else if (pane == Pane.Artifacts) {
                                ArtifactsPane(
                                    artifacts = artifacts,
                                    scan = artifactScan,
                                    onOpenSession = viewModel::openSession,
                                    onRescan = viewModel::loadArtifacts,
                                )
                            } else if (pane == Pane.Gateway) {
                                GatewayPane(health = health, toolsets = toolsets, skills = skills)
                            } else if (pane == Pane.Settings) {
                                SettingsPane(
                                    settings = settings,
                                    connection = connection,
                                    dashboardState = dashboardState,
                                    models = models,
                                    onSaveServer = viewModel::saveServer,
                                    onSaveDashboard = viewModel::saveDashboard,
                                    onSelectModel = viewModel::setModel,
                                    onSelectLanguage = viewModel::setLanguage,
                                    onToggleApprovals = viewModel::setNotifyApprovals,
                                    onToggleCompletion = viewModel::setNotifyCompletion,
                                    onSelectLayoutMode = viewModel::setLayoutMode,
                                    onSetUiScale = viewModel::setUiScale,
                                    permissions = permissions,
                                    onRequestNotifications = onRequestNotifications,
                                    onRequestBackground = onRequestBackground,
                                    activeModel = activeModel,
                                    health = health,
                                    toolsets = toolsets,
                                    agentSkills = skills,
                                    profiles = profiles,
                                    activeProfile = activeProfile,
                                    dashboardSkills = dashboardSkills,
                                    onSelectProfile = { viewModel.setActiveProfile(it.name) },
                                    onToggleSkill = viewModel::toggleSkill,
                                    onRetryDashboard = viewModel::refreshDashboard,
                                )
                            } else {
                                ChatPane(
                                    state = chat,
                                    onSend = viewModel::send,
                                    onStop = viewModel::stop,
                                    onDismissError = viewModel::dismissError,
                                    modelLabel = activeModel,
                                    modelChoices = chatModels,
                                    onSelectModel = viewModel::setModelChoice,
                                    effort = settings.reasoningEffort,
                                    onSelectEffort = viewModel::setReasoningEffort,
                                    voiceAvailable = viewModel.voiceAvailable,
                                    voiceState = voiceState,
                                    conversing = conversing,
                                    dictation = dictation,
                                    onDictate = dictate,
                                    onDictationConsumed = viewModel::consumeDictation,
                                    onToggleConversation = viewModel::toggleConversation,
                                )
                            }
                        }

                        if (showRail) {
                            PaneDivider(
                                onDelta = { delta ->
                                    railWidth = (railWidth - delta)
                                        .coerceIn(RAIL_WIDTH_MIN, railMax.coerceAtLeast(RAIL_WIDTH_MIN))
                                },
                                onCommit = commitWidths,
                            )
                            RailHost(
                                panel = railPanel,
                                editing = editingLayout,
                                onCycle = {
                                    viewModel.setRailPanel(nextRailPanel(railPanel, railOptions))
                                },
                                onHide = { viewModel.setRailPanel(RailPanel.None) },
                                modifier = Modifier.width(railWidth.dp),
                            ) {
                                railContent(railPanel)
                            }
                        }
                    }

                    if (editingLayout) {
                        LayoutEditBar(
                            settings = settings,
                            railHidden = railPanel == RailPanel.None,
                            onShowRail = { viewModel.setRailPanel(RailPanel.Activity) },
                            onToggleStatusBar = viewModel::setShowStatusBar,
                            onReset = viewModel::resetLayout,
                            onDone = { editingLayout = false },
                        )
                    }

                    if (settings.showStatusBar) {
                        StatusBar(chat = chat, connection = connection, model = activeModel)
                    }
                }
            } else {
                when (pane) {
                    Pane.Chat -> ChatPane(
                        state = chat,
                        onSend = viewModel::send,
                        onStop = viewModel::stop,
                        onDismissError = viewModel::dismissError,
                        modifier = content,
                        modelLabel = activeModel,
                        modelChoices = chatModels,
                        onSelectModel = viewModel::setModelChoice,
                        effort = settings.reasoningEffort,
                        onSelectEffort = viewModel::setReasoningEffort,
                        voiceAvailable = viewModel.voiceAvailable,
                        voiceState = voiceState,
                        conversing = conversing,
                        dictation = dictation,
                        onDictate = dictate,
                        onDictationConsumed = viewModel::consumeDictation,
                        onToggleConversation = viewModel::toggleConversation,
                    )
                    Pane.Projects -> ProjectsPane(
                        payload = projectsPayload,
                        busy = projectsBusy,
                        error = projectsError,
                        dashboardConfigured = settings.dashboardConfigured,
                        onLoad = viewModel::loadProjects,
                        onCreate = viewModel::createProject,
                        onSetActive = viewModel::setActiveProject,
                        onRename = viewModel::renameProject,
                        onArchive = viewModel::archiveProject,
                        onBrowse = viewModel::browseGateway,
                        modifier = content,
                    )
                    Pane.Artifacts -> ArtifactsPane(
                        artifacts = artifacts,
                        scan = artifactScan,
                        onOpenSession = viewModel::openSession,
                        onRescan = viewModel::loadArtifacts,
                        modifier = content,
                    )
                    Pane.Cron -> CronPane(
                        jobs = jobs,
                        onPause = viewModel::pauseJob,
                        onResume = viewModel::resumeJob,
                        onRun = viewModel::runJob,
                        onDelete = viewModel::deleteJob,
                        modifier = content,
                    )
                    Pane.Gateway -> GatewayPane(
                        health = health,
                        toolsets = toolsets,
                        skills = skills,
                        modifier = content,
                    )
                    Pane.Dashboard -> DashboardPane(
                        state = dashboardState,
                        profiles = profiles,
                        active = activeProfile,
                        skills = dashboardSkills,
                        onSelectProfile = { viewModel.setActiveProfile(it.name) },
                        onToggleSkill = viewModel::toggleSkill,
                        onRetry = viewModel::refreshDashboard,
                        modifier = content,
                    )
                    Pane.Settings -> SettingsPane(
                        settings = settings,
                        connection = connection,
                        dashboardState = dashboardState,
                        models = models,
                        onSaveServer = viewModel::saveServer,
                        onSaveDashboard = viewModel::saveDashboard,
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
                        activeModel = activeModel,
                        health = health,
                        toolsets = toolsets,
                        agentSkills = skills,
                        profiles = profiles,
                        activeProfile = activeProfile,
                        dashboardSkills = dashboardSkills,
                        onSelectProfile = { viewModel.setActiveProfile(it.name) },
                        onToggleSkill = viewModel::toggleSkill,
                        onRetryDashboard = viewModel::refreshDashboard,
                    )
                }
            }
        }
        }

        if (docked) {
            // The drawer *is* the left column here, not something drawn over
            // one. That is the whole point of pinning: three columns, with the
            // menu as the first of them, the way the desktop shell reads.
            // The Row paints its own background: nothing else does at this
            // level, and any pixel it leaves unclaimed shows the bare window.
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(
                    Modifier
                        .width(drawerWidth.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    drawer()
                }
                PaneDivider(
                    onDelta = { delta ->
                        drawerWidth = (drawerWidth + delta)
                            .coerceIn(RAIL_WIDTH_MIN, drawerMax.coerceAtLeast(RAIL_WIDTH_MIN))
                    },
                    onCommit = commitWidths,
                )
                Box(Modifier.weight(1f)) { body() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.width(sheetWidth),
                    ) {
                        drawer()
                    }
                },
            ) {
                body()
            }
        }

        // Over everything, including a docked drawer: search covers the whole
        // surface, and results that appeared beside the list they came from
        // would be two answers to one question.
        if (searchOpen) {
            BackHandler { searchOpen = false }
            SearchPane(
                sessions = sessions,
                selectedSessionId = chat.sessionId,
                onSessionAction = onSessionAction,
                onSelect = { session ->
                    viewModel.openSession(session.id)
                    viewModel.show(Pane.Chat)
                    searchOpen = false
                },
                onClose = { searchOpen = false },
            )
        }


        // Renaming and deleting need a surface of their own: one takes typing,
        // and the other is the only irreversible thing this app can do to
        // someone's history.
        renaming?.let { session ->
            var draft by remember(session.id) { mutableStateOf(session.title.orEmpty()) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.session_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameSession(session.id, draft.trim())
                        renaming = null
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { renaming = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        deleting?.let { session ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.session_delete_title)) },
                // Says what is destroyed and names the reversible alternative.
                // The route removes the messages and the transcript files on the
                // agent's host, and nothing in either client can undo that.
                text = { Text(stringResource(R.string.session_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteSession(session.id)
                        deleting = null
                    }) {
                        Text(stringResource(R.string.session_delete), color = colors.failed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // A failed action says so rather than leaving the list looking unchanged
        // for no stated reason.
        sessionNotice?.let { message ->
            val text = stringResource(R.string.session_action_failed, message)
            LaunchedEffect(message) {
                notices.showSnackbar(text)
                viewModel.clearSessionNotice()
            }
        }

        // Outside the Scaffold on purpose: the notice that explains a refused
        // dock is raised from inside the drawer, and a host nested in the
        // content would surface it underneath the sheet that triggered it.
        SnackbarHost(
            hostState = notices,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )

        // The sheet sits above whichever layout is showing: an approval blocks
        // the run regardless of which pane has focus.
        chat.pendingApproval?.let { approval ->
            ApprovalSheet(approval = approval, onChoice = viewModel::respondToApproval)
        }
    }
    }
}

/**
 * Controls that belong to the layout rather than to the rail: bringing a hidden
 * rail back, the status bar, and the way out of the mode.
 *
 * A hidden rail has no header to press, so restoring it has to live here —
 * otherwise hiding it would be a one-way door.
 */
@Composable
private fun LayoutEditBar(
    settings: HermesSettings,
    railHidden: Boolean,
    onShowRail: () -> Unit,
    onToggleStatusBar: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalRunColors.current
    HorizontalDivider(color = colors.line)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.panelRaised)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.layout_title),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(end = 6.dp),
        )
        if (railHidden) {
            TextButton(onClick = onShowRail) { Text(stringResource(R.string.layout_show_rail)) }
        }
        TextButton(onClick = { onToggleStatusBar(!settings.showStatusBar) }) {
            Text(
                stringResource(
                    if (settings.showStatusBar) R.string.layout_status_bar_hide
                    else R.string.layout_status_bar_show,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) { Text(stringResource(R.string.layout_reset)) }
        TextButton(onClick = onDone) { Text(stringResource(R.string.layout_done)) }
    }
}

@Composable
private fun ConnectionLine(connection: Connection) {
    val (color, label) = connectionStatus(connection)
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * A pane's glyph, for the drawer's destination rows.
 *
 * Each one names what the pane holds rather than what it is called, so the row
 * still reads at a glance in a language the icon set was not drawn for.
 */
@Composable
private fun PaneIcon(pane: Pane, tint: Color) = when (pane) {
    Pane.Chat -> ChatIcon(tint = tint)
    Pane.Projects -> FolderIcon(tint = tint)
    Pane.Artifacts -> DocumentIcon(tint = tint)
    Pane.Cron -> ClockIcon(tint = tint)
    Pane.Gateway -> ServerIcon(tint = tint)
    Pane.Dashboard -> GridIcon(tint = tint)
    Pane.Settings -> SettingsIcon(tint = tint)
}

/** A pane's name, used by both the title bar and the drawer's destination rows. */
@Composable
private fun paneLabel(pane: Pane): String = when (pane) {
    Pane.Settings -> stringResource(R.string.settings_title)
    Pane.Cron -> stringResource(R.string.cron_title)
    Pane.Gateway -> stringResource(R.string.gateway_title)
    Pane.Dashboard -> stringResource(R.string.dashboard_title)
    Pane.Chat -> stringResource(R.string.nav_chat)
    Pane.Artifacts -> stringResource(R.string.artifacts_title)
    Pane.Projects -> stringResource(R.string.projects_title)
}

/** Colour and wording for a connection state, shared by the bar and the drawer. */
@Composable
private fun connectionStatus(connection: Connection): Pair<Color, String> {
    val colors = LocalRunColors.current
    return when (connection) {
        is Connection.Connected -> colors.completed to stringResource(R.string.connection_connected)
        Connection.Checking -> colors.muted to stringResource(R.string.connection_checking)
        Connection.NotConfigured -> colors.awaiting to stringResource(R.string.connection_not_configured)
        Connection.Unauthorized -> colors.failed to stringResource(R.string.connection_unauthorized)
        is Connection.Unreachable -> colors.failed to stringResource(R.string.connection_unreachable)
    }
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
