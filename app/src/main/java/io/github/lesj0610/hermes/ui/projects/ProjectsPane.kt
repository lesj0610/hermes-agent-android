package io.github.lesj0610.hermes.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.FsEntry
import io.github.lesj0610.hermes.net.Project
import io.github.lesj0610.hermes.net.ProjectsPayload
import io.github.lesj0610.hermes.ui.components.ArchiveIcon
import io.github.lesj0610.hermes.ui.components.CheckIcon
import io.github.lesj0610.hermes.ui.components.ChevronIcon
import io.github.lesj0610.hermes.ui.components.MoreIcon
import io.github.lesj0610.hermes.ui.components.PencilIcon
import io.github.lesj0610.hermes.ui.components.FolderIcon
import io.github.lesj0610.hermes.ui.components.PlusIcon
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import kotlinx.coroutines.launch

/**
 * Named, multi-folder workspaces — the desktop's Projects, on the phone.
 *
 * These are not stored here. They live in the connected profile's
 * `projects.db` and are reached over the dashboard's JSON-RPC socket, so a
 * project created here is the one the desktop opens, and its sessions group
 * under it there. The gateway's own HTTP surface has no projects route, which
 * is why this screen needs a configured dashboard and says so plainly when it
 * has none rather than presenting an empty list.
 */
@Composable
fun ProjectsPane(
    payload: ProjectsPayload,
    busy: Boolean,
    error: String?,
    dashboardConfigured: Boolean,
    onLoad: () -> Unit,
    onCreate: (name: String, idea: String, folders: List<String>) -> Unit,
    onSetActive: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onBrowse: suspend (String) -> List<FsEntry>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }

    // Same rule as the session list: no refresh button, re-read at the moments
    // the list goes stale. This effect covers both — it fires when the pane is
    // first composed, which is when it becomes visible, and again whenever the
    // app returns to the foreground while it is still on screen. Projects are
    // made on the desktop too, and that is exactly the time away this catches.
    //
    // Scoped to the pane rather than to the shell on purpose: a load here is a
    // ticket mint plus a WebSocket handshake, which is not worth spending while
    // nobody is looking at projects.
    LifecycleResumeEffect(dashboardConfigured) {
        if (dashboardConfigured) onLoad()
        onPauseOrDispose { }
    }
    BackHandler(enabled = creating) { creating = false }

    if (!dashboardConfigured) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.projects_needs_dashboard),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(28.dp),
            )
        }
        return
    }

    if (creating) {
        NewProject(
            onCancel = { creating = false },
            onCreate = { name, idea, folders ->
                onCreate(name, idea, folders)
                creating = false
            },
            onBrowse = onBrowse,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.failed,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Archived projects stay out of the list but are not deleted — the
        // server keeps them, and this screen has no delete on purpose: losing a
        // workspace from a phone by mistap is not recoverable from here.
        val live = payload.projects.filterNot { it.archived }

        if (live.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (busy) R.string.connection_checking else R.string.projects_empty,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        } else {
            // The pull is the escape hatch for a change made elsewhere while
            // this screen was already open.
            PullToRefreshBox(
                isRefreshing = busy,
                onRefresh = onLoad,
                modifier = Modifier.weight(1f),
            ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(live, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        active = project.id == payload.activeId,
                        onClick = { onSetActive(project.id) },
                        onAction = { action ->
                            when (action) {
                                ProjectAction.Activate -> onSetActive(project.id)
                                ProjectAction.Rename -> renaming = project
                                ProjectAction.Archive -> onArchive(project.id, true)
                            }
                        },
                    )
                }
            }
            }
        }

        HorizontalDivider(color = colors.line)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { creating = true }, enabled = !busy) {
                // Tinted explicitly: the icon set defaults to the muted colour,
                // which on a filled button reads as a disabled control.
                PlusIcon(
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = stringResource(R.string.projects_new),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }

    renaming?.let { project ->
        var draft by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.projects_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(project.id, draft.trim())
                        renaming = null
                    },
                    enabled = draft.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** What a project row's own menu can do. */
private enum class ProjectAction { Activate, Rename, Archive }

@Composable
private fun ProjectRow(
    project: Project,
    active: Boolean,
    onClick: () -> Unit,
    onAction: (ProjectAction) -> Unit,
) {
    val colors = LocalRunColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .border(1.dp, if (active) colors.completed else colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (active) colors.completed else colors.muted, size = 7)
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                Text(
                    text = stringResource(R.string.dashboard_profile_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.completed,
                )
            }
            Box {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    MoreIcon(modifier = Modifier.size(15.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_activate)) },
                        leadingIcon = { CheckIcon(modifier = Modifier.size(17.dp)) },
                        enabled = !active,
                        onClick = {
                            menuOpen = false
                            onAction(ProjectAction.Activate)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_rename)) },
                        leadingIcon = { PencilIcon(modifier = Modifier.size(17.dp)) },
                        onClick = {
                            menuOpen = false
                            onAction(ProjectAction.Rename)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_archive)) },
                        leadingIcon = { ArchiveIcon(modifier = Modifier.size(17.dp)) },
                        onClick = {
                            menuOpen = false
                            onAction(ProjectAction.Archive)
                        },
                    )
                }
            }
        }

        project.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        // Every folder, not just the primary: which directories a project spans
        // is the thing that distinguishes two similarly named ones.
        project.folders.forEach { folder ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderIcon(modifier = Modifier.size(13.dp))
                Text(
                    text = folder.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (folder.isPrimary) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        colors.muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The create form: a name, folders on the gateway, and the idea.
 *
 * The idea is saved as IDEA.md in the first folder, which is what the desktop
 * dialog does with the same field — the file is how the agent reads it later,
 * so the label says where it goes rather than leaving it as a description that
 * lives only in a database row.
 */
@Composable
private fun NewProject(
    onCancel: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit,
    onBrowse: suspend (String) -> List<FsEntry>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var name by remember { mutableStateOf("") }
    var idea by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(listOf<String>()) }
    var browsing by remember { mutableStateOf(false) }

    if (browsing) {
        FolderBrowser(
            onBrowse = onBrowse,
            onCancel = { browsing = false },
            onPick = { path ->
                if (path !in folders) folders = folders + path
                browsing = false
            },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.projects_new),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.projects_new_why),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.projects_name)) },
            singleLine = true,
        )

        Text(
            text = stringResource(R.string.projects_folders),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
        if (folders.isEmpty()) {
            Text(
                text = stringResource(R.string.projects_no_folders),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        folders.forEachIndexed { index, path ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderIcon(modifier = Modifier.size(14.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        colors.muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { folders = folders.filterIndexed { i, _ -> i != index } }) {
                    Text("×")
                }
            }
        }
        OutlinedButton(onClick = { browsing = true }) {
            PlusIcon(modifier = Modifier.size(15.dp))
            Text(
                text = stringResource(R.string.projects_add_folder),
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        OutlinedTextField(
            value = idea,
            onValueChange = { idea = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.projects_idea)) },
            placeholder = { Text(stringResource(R.string.projects_idea_hint)) },
            minLines = 3,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.projects_cancel)) }
            Button(
                onClick = { onCreate(name.trim(), idea.trim(), folders) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.projects_create))
            }
        }
    }
}

/**
 * Browses the gateway host's filesystem.
 *
 * Not an Android picker: the paths a project records belong to the machine the
 * agent runs on, and this phone's storage is not that machine. Starting at the
 * root rather than at a guessed home, because the server does not tell us which
 * home it would be.
 */
@Composable
private fun FolderBrowser(
    onBrowse: suspend (String) -> List<FsEntry>,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf(listOf<FsEntry>()) }
    var loading by remember { mutableStateOf(false) }

    fun go(target: String) {
        path = target
        loading = true
        scope.launch {
            entries = onBrowse(target).filter { it.isDirectory }
            loading = false
        }
    }

    LaunchedEffect(Unit) { go("/") }
    BackHandler { onCancel() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(modifier = Modifier.size(17.dp), pointLeft = true)
            }
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onPick(path) }, enabled = path != "/") {
                Text(stringResource(R.string.projects_pick_here))
            }
        }
        HorizontalDivider(color = colors.line)

        if (path != "/") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { go(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    text = "..",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                )
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.connection_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { go(entry.path) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolderIcon(modifier = Modifier.size(15.dp))
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ChevronIcon(modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
