package io.github.lesj0610.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.PendingApproval
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Localized label for a server-supplied choice.
 *
 * The set of choices is never computed here — the server decides which of
 * `once`, `session`, `always`, `deny` apply, and an unrecognized value is shown
 * verbatim rather than dropped, so a newer policy still renders a usable button.
 */
@Composable
private fun labelFor(choice: String): String = when (choice) {
    "once" -> stringResource(R.string.approval_choice_once)
    "session" -> stringResource(R.string.approval_choice_session)
    "always" -> stringResource(R.string.approval_choice_always)
    "deny" -> stringResource(R.string.approval_choice_deny)
    else -> choice
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    approval: PendingApproval,
    onChoice: (String) -> Unit,
) {
    val colors = LocalRunColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        // Dismissal is not a decision. The run stays blocked until a choice is
        // sent, so swiping away must not silently deny or approve.
        onDismissRequest = {},
        sheetState = sheetState,
        containerColor = colors.panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = stringResource(R.string.approval_title),
                style = MaterialTheme.typography.labelSmall,
                color = colors.awaiting,
            )
            Text(
                text = stringResource(R.string.approval_subtitle),
                style = MaterialTheme.typography.titleMedium,
            )

            approval.command?.takeIf { it.isNotBlank() }?.let { command ->
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(11.dp),
                )
            }

            approval.choices.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { choice ->
                        ChoiceButton(
                            choice = choice,
                            onClick = { onChoice(choice) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a lone trailing button at half width instead of
                    // stretching it across the row.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(choice: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    when (choice) {
        "once" -> Button(onClick = onClick, modifier = modifier) { Text(labelFor(choice)) }
        "deny" -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.failed),
        ) { Text(labelFor(choice)) }
        else -> OutlinedButton(onClick = onClick, modifier = modifier) { Text(labelFor(choice)) }
    }
}
