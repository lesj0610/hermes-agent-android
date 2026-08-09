package io.github.lesj0610.hermes.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.Language
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.ui.Connection
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/** Live grant state, re-read whenever the screen resumes. */
data class PermissionState(
    val canNotify: Boolean = true,
    val batteryExempt: Boolean = false,
)

@Composable
fun SettingsPane(
    settings: HermesSettings,
    connection: Connection,
    models: List<ModelEntry>,
    permissions: PermissionState,
    onSaveServer: (String, String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onToggleApprovals: (Boolean) -> Unit,
    onToggleCompletion: (Boolean) -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionBanner(connection)

        Group(stringResource(R.string.settings_group_server)) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_base_url)) },
                placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { onSaveServer(baseUrl, token) },
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }

        Group(stringResource(R.string.settings_group_agent)) {
            (connection as? Connection.Connected)?.version?.let { version ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
                HorizontalDivider(color = colors.line)
            }
            Text(
                text = stringResource(R.string.settings_model),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            SelectableRow(
                label = stringResource(R.string.settings_model_default),
                selected = settings.model.isBlank(),
                onClick = { onSelectModel("") },
            )
            models.forEach { model ->
                SelectableRow(
                    label = model.id,
                    mono = true,
                    selected = settings.model == model.id,
                    onClick = { onSelectModel(model.id) },
                )
            }
        }

        Group(stringResource(R.string.settings_group_language)) {
            SelectableRow(
                label = stringResource(R.string.settings_language_system),
                selected = settings.language == Language.SYSTEM,
                onClick = { onSelectLanguage(Language.SYSTEM) },
            )
            Language.SUPPORTED.forEach { option ->
                SelectableRow(
                    label = option.nativeName,
                    selected = settings.language == option.tag,
                    onClick = { onSelectLanguage(option.tag) },
                )
            }
        }

        Group(stringResource(R.string.settings_group_notifications)) {
            ToggleRow(
                label = stringResource(R.string.settings_notify_approvals),
                checked = settings.notifyApprovals,
                onCheckedChange = onToggleApprovals,
            )
            HorizontalDivider(color = colors.line)
            ToggleRow(
                label = stringResource(R.string.settings_notify_completion),
                checked = settings.notifyCompletion,
                onCheckedChange = onToggleCompletion,
            )
        }

        Group(stringResource(R.string.settings_group_permissions)) {
            PermissionRow(
                label = stringResource(R.string.permission_notifications),
                explanation = stringResource(R.string.permission_notifications_why),
                granted = permissions.canNotify,
                onGrant = onRequestNotifications,
            )
            HorizontalDivider(color = colors.line)
            PermissionRow(
                label = stringResource(R.string.permission_background),
                explanation = stringResource(R.string.permission_background_why),
                granted = permissions.batteryExempt,
                onGrant = onRequestBackground,
            )
        }
    }
}

@Composable
private fun ConnectionBanner(connection: Connection) {
    val colors = LocalRunColors.current
    val (accent, title, detail) = when (connection) {
        is Connection.Connected -> Triple(
            colors.completed,
            stringResource(R.string.connection_connected),
            stringResource(R.string.connection_latency, connection.latencyMs),
        )
        Connection.Checking -> Triple(colors.muted, stringResource(R.string.connection_checking), null)
        Connection.NotConfigured -> Triple(
            colors.awaiting,
            stringResource(R.string.connection_not_configured),
            stringResource(R.string.connection_not_configured_help),
        )
        Connection.Unauthorized -> Triple(
            colors.failed,
            stringResource(R.string.connection_unauthorized),
            stringResource(R.string.connection_unauthorized_help),
        )
        is Connection.Unreachable -> Triple(
            colors.failed,
            stringResource(R.string.connection_unreachable),
            // The tunnel is the usual culprit, so name it before the raw cause.
            stringResource(R.string.connection_unreachable_help),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = accent)
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val colors = LocalRunColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panel)
                .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    mono: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A grant the app needs, with the reason stated before the button.
 *
 * The reason matters: both of these look optional from the outside, and a user
 * who denies them gets an app that silently stalls mid-run rather than one that
 * visibly loses a feature.
 */
@Composable
private fun PermissionRow(
    label: String,
    explanation: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val colors = LocalRunColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        if (granted) {
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.labelSmall,
                color = colors.completed,
            )
        } else {
            Button(onClick = onGrant) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
