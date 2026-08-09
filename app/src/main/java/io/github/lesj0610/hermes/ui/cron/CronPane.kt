package io.github.lesj0610.hermes.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.net.Job
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Scheduled jobs.
 *
 * The one panel beyond the conversation that earns its place on a phone: it is
 * the only part of the gateway surface you can actually operate remotely —
 * pause a job, resume it, fire it now — rather than merely inspect.
 *
 * Creating and editing jobs is not here. Composing a cron expression and a
 * prompt on a phone keyboard is worse than doing it where the job was written,
 * and the API for it is available whenever that changes.
 */
@Composable
fun CronPane(
    jobs: List<Job>,
    onPause: (Job) -> Unit,
    onResume: (Job) -> Unit,
    onRun: (Job) -> Unit,
    onDelete: (Job) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var pendingDelete by remember { mutableStateOf<Job?>(null) }

    if (jobs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.cron_empty),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(jobs, key = { it.id }) { job ->
            JobCard(
                job = job,
                onPause = { onPause(job) },
                onResume = { onResume(job) },
                onRun = { onRun(job) },
                onDelete = { pendingDelete = job },
            )
        }
    }

    // Deleting a schedule is not undoable from here, so it asks first.
    pendingDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.cron_delete_title)) },
            text = { Text(stringResource(R.string.cron_delete_body, job.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(job)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.failed),
                ) { Text(stringResource(R.string.cron_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.error_dismiss))
                }
            },
            containerColor = colors.panel,
        )
    }
}

@Composable
private fun JobCard(
    job: Job,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalRunColors.current
    val stateColor = if (job.isPaused) colors.muted else colors.completed

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(stateColor, size = 7)
            Text(
                text = job.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            job.deliverLabel?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }

        job.scheduleLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.muted,
            )
        }

        job.prompt?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRun) { Text(stringResource(R.string.cron_run_now)) }
            if (job.isPaused) {
                TextButton(onClick = onResume) { Text(stringResource(R.string.cron_resume)) }
            } else {
                TextButton(onClick = onPause) { Text(stringResource(R.string.cron_pause)) }
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.failed),
            ) { Text(stringResource(R.string.cron_delete)) }
        }
    }
}
