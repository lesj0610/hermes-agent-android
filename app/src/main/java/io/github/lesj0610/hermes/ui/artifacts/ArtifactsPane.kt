package io.github.lesj0610.hermes.ui.artifacts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.ui.ArtifactScan
import io.github.lesj0610.hermes.ui.components.DocumentIcon
import io.github.lesj0610.hermes.ui.components.LinkIcon
import io.github.lesj0610.hermes.ui.components.PhotoIcon
import io.github.lesj0610.hermes.ui.components.RefreshIcon
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Everything the recent runs produced, in one list.
 *
 * The gateway has no artifacts route, so this is assembled from the sessions'
 * own messages — the same way the desktop client builds its artifacts view.
 * That has one consequence worth stating on screen rather than hiding: a file
 * path in a transcript is a path *on the gateway's machine*, so the phone can
 * open links and remote images but not a local file. Those are shown with their
 * path, which can be copied, instead of behind a control that would fail.
 */
@Composable
fun ArtifactsPane(
    artifacts: List<Artifact>,
    scan: ArtifactScan,
    onOpenSession: (String) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    val context = LocalContext.current
    var filter by remember { mutableStateOf<ArtifactKind?>(null) }

    // Scanned on first open rather than at launch: it is a burst of history
    // reads, and the app should not spend them on a screen nobody opened.
    LaunchedEffect(Unit) {
        if (scan.total == 0 && !scan.running) onRescan()
    }

    val shown = remember(artifacts, filter) {
        if (filter == null) artifacts else artifacts.filter { it.kind == filter }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                label = stringResource(R.string.artifacts_all),
                selected = filter == null,
                onClick = { filter = null },
            )
            ArtifactKind.entries.forEach { kind ->
                FilterChip(
                    label = kindLabel(kind),
                    selected = filter == kind,
                    onClick = { filter = kind },
                )
            }
        }

        // What was actually read. A list assembled from twenty of two hundred
        // sessions looks identical to a complete one, and the difference is the
        // whole reason something might be missing from it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    scan.running -> stringResource(
                        R.string.artifacts_scanning, scan.scanned, scan.total,
                    )
                    scan.failed > 0 -> stringResource(
                        R.string.artifacts_scanned_failed, scan.total, scan.available, scan.failed,
                    )
                    else -> stringResource(
                        R.string.artifacts_scanned, scan.total, scan.available,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            // An icon, not a worded button: the label sat next to a line of
            // text that is already prose, and two runs of words competing on
            // one row is what made the row read as a sentence with a link in
            // it rather than as a readout with a control.
            val rescanLabel = stringResource(R.string.artifacts_rescan)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable(enabled = !scan.running, onClick = onRescan)
                    .semantics { contentDescription = rescanLabel },
                contentAlignment = Alignment.Center,
            ) {
                RefreshIcon(
                    modifier = Modifier.size(18.dp),
                    // Dimmed while the scan runs, so the control shows it is
                    // busy rather than looking ignored.
                    tint = if (scan.running) colors.muted.copy(alpha = 0.35f) else colors.muted,
                )
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (scan.running) {
                        stringResource(R.string.connection_checking)
                    } else {
                        stringResource(R.string.artifacts_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { artifact ->
                ArtifactRow(
                    artifact = artifact,
                    onOpen = { open(context, artifact) },
                    onOpenSession = { onOpenSession(artifact.sessionId) },
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: ArtifactKind): String = when (kind) {
    ArtifactKind.Image -> stringResource(R.string.artifacts_images)
    ArtifactKind.File -> stringResource(R.string.artifacts_files)
    ArtifactKind.Link -> stringResource(R.string.artifacts_links)
}

@Composable
private fun ArtifactRow(
    artifact: Artifact,
    onOpen: () -> Unit,
    onOpenSession: () -> Unit,
) {
    val colors = LocalRunColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(11.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (artifact.kind) {
                ArtifactKind.Image -> PhotoIcon(modifier = Modifier.size(16.dp))
                ArtifactKind.File -> DocumentIcon(modifier = Modifier.size(16.dp))
                ArtifactKind.Link -> LinkIcon(modifier = Modifier.size(16.dp))
            }
            Text(
                text = artifact.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = artifact.value,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artifact.sessionTitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onOpenSession),
            )
            if (!artifact.remote) {
                // Says where the file is instead of offering to open it. The
                // path belongs to the gateway's filesystem, and the API server
                // serves no file route, so there is nothing for the phone to
                // fetch — tapping copies the path.
                Text(
                    text = stringResource(R.string.artifacts_on_gateway),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.awaiting,
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalRunColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) MaterialTheme.colorScheme.primary else colors.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.panelRaised else colors.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/**
 * Opens what can be opened, copies what cannot.
 *
 * A gateway-local path has nowhere to go: it names a file on another machine and
 * the API server exposes no route to read it. Copying is the useful action —
 * it is what gets pasted into the next message.
 */
private fun open(context: Context, artifact: Artifact) {
    if (artifact.remote) {
        val intent = Intent(Intent.ACTION_VIEW, artifact.value.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.exceptionOrNull() !is ActivityNotFoundException) {
            return
        }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(artifact.label, artifact.value))
}
