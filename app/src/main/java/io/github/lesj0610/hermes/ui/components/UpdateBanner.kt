package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import io.github.lesj0610.hermes.data.UpdateState
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * One line above the transcript when a newer release exists.
 *
 * Shown once per version: dismissing it records that version, and the launch
 * check stays quiet about it afterwards. The update section in Settings still
 * lists it, so dismissing hides the announcement rather than the update.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current

    when (state) {
        is UpdateState.Available -> Banner(modifier) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.update_available, state.release.version),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                Button(onClick = onUpdate) { Text(stringResource(R.string.update_download)) }
            }
        }

        is UpdateState.Downloading -> Banner(modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.update_downloading,
                        (state.fraction * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                // Determinate: GitHub reports the asset size, so the bar is a
                // real fraction rather than a spinner pretending to be one.
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is UpdateState.Failed -> Banner(modifier) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (state.reason) {
                            UpdateState.Reason.Download -> R.string.update_failed_download
                            UpdateState.Reason.Signature -> R.string.update_failed_signature
                            UpdateState.Reason.Permission -> R.string.update_needs_permission
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.reason == UpdateState.Reason.Permission) {
                    Button(onClick = onGrant) { Text(stringResource(R.string.update_grant)) }
                } else {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                }
            }
        }

        // Idle, Checking and Ready say nothing: a check that finds nothing is
        // not news, and Ready is immediately followed by the system installer.
        else -> Unit
    }
}

@Composable
private fun Banner(modifier: Modifier, content: @Composable () -> Unit) {
    val colors = LocalRunColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.panelRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}
