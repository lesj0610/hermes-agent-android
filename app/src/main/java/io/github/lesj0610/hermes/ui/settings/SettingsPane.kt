package io.github.lesj0610.hermes.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.DASHBOARD_DEFAULT_PORT
import io.github.lesj0610.hermes.core.GATEWAY_DEFAULT_PORT
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.coercePort
import io.github.lesj0610.hermes.core.defaultDashboardHost
import io.github.lesj0610.hermes.core.parseEndpoint
import io.github.lesj0610.hermes.core.Language
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.UI_SCALE_MAX
import io.github.lesj0610.hermes.core.UI_SCALE_MIN
import io.github.lesj0610.hermes.core.UI_SCALE_STEP
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.ui.Connection
import io.github.lesj0610.hermes.ui.DashboardState
import io.github.lesj0610.hermes.ui.components.StatusDot
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
    dashboardState: DashboardState,
    models: List<ModelEntry>,
    permissions: PermissionState,
    onSaveServer: (String, Int, String) -> Unit,
    onSaveDashboard: (String, Int, String, String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onToggleApprovals: (Boolean) -> Unit,
    onToggleCompletion: (Boolean) -> Unit,
    onSelectLayoutMode: (LayoutMode) -> Unit,
    onSetUiScale: (Float) -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current

    val gateway = remember(settings.baseUrl) {
        parseEndpoint(settings.baseUrl, GATEWAY_DEFAULT_PORT)
    }
    var host by remember(gateway) { mutableStateOf(gateway.host) }
    var port by remember(gateway) { mutableStateOf(gateway.port.toString()) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }

    // The dashboard host is prefilled from the gateway's when unset — same box,
    // different port is the usual arrangement. Only a suggestion: nothing is
    // saved until Save is pressed, and both fields stay editable because the
    // addresses can legitimately differ (reverse proxy, tunnelled loopback).
    val dash = remember(settings.dashboardUrl) {
        parseEndpoint(settings.dashboardUrl, DASHBOARD_DEFAULT_PORT)
    }
    var dashHost by remember(dash, settings.baseUrl) {
        mutableStateOf(dash.host.ifBlank { defaultDashboardHost(settings.baseUrl) })
    }
    var dashPort by remember(dash) { mutableStateOf(dash.port.toString()) }
    var dashUser by remember(settings.dashboardUsername) { mutableStateOf(settings.dashboardUsername) }
    var dashPassword by remember(settings.dashboardPassword) { mutableStateOf(settings.dashboardPassword) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionBanner(connection)

        Group(stringResource(R.string.settings_group_server)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.width(110.dp),
                    label = { Text(stringResource(R.string.settings_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            // States the fact without steering the choice: which transport is
            // appropriate depends on the network this gateway sits on, and only
            // the operator knows that.
            if (!host.trim().startsWith("https://", ignoreCase = true) && host.isNotBlank()) {
                Text(
                    text = stringResource(R.string.settings_cleartext_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.awaiting,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // The address the client will actually call. Shown because a saved
            // value that differs from what was typed is otherwise invisible,
            // and that difference is exactly what makes "it just won't connect"
            // hard to diagnose.
            settings.baseUrl.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.settings_effective_url, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = { onSaveServer(host, coercePort(port, GATEWAY_DEFAULT_PORT), token) },
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }

        Group(stringResource(R.string.settings_group_dashboard)) {
            Text(
                text = stringResource(R.string.settings_dashboard_why),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // The dashboard had no status anywhere in settings, so a saved
            // configuration gave no sign of whether it worked. Its state is
            // independent of the gateway's — one can be fine while the other
            // is down — so it needs its own line rather than sharing the
            // connection banner above.
            DashboardStatusLine(dashboardState)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dashHost,
                    onValueChange = { dashHost = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.settings_dashboard_url)) },
                    placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dashPort,
                    onValueChange = { dashPort = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.width(110.dp),
                    label = { Text(stringResource(R.string.settings_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                value = dashUser,
                onValueChange = { dashUser = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.settings_dashboard_user)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = dashPassword,
                onValueChange = { dashPassword = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.settings_dashboard_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = {
                    onSaveDashboard(
                        dashHost,
                        coercePort(dashPort, DASHBOARD_DEFAULT_PORT),
                        dashUser,
                        dashPassword,
                    )
                },
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

        Group(stringResource(R.string.settings_group_display)) {
            Text(
                text = stringResource(R.string.settings_layout),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            SelectableRow(
                label = stringResource(R.string.settings_layout_auto),
                selected = settings.layoutMode == LayoutMode.Auto,
                onClick = { onSelectLayoutMode(LayoutMode.Auto) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_layout_phone),
                selected = settings.layoutMode == LayoutMode.Phone,
                onClick = { onSelectLayoutMode(LayoutMode.Phone) },
            )
            SelectableRow(
                label = stringResource(R.string.settings_layout_tablet),
                selected = settings.layoutMode == LayoutMode.Tablet,
                onClick = { onSelectLayoutMode(LayoutMode.Tablet) },
            )

            HorizontalDivider(color = colors.line)

            ScaleRow(
                scale = settings.uiScale,
                onChange = onSetUiScale,
            )
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

/**
 * Dashboard reachability, stated plainly including the failure reason.
 *
 * Kept compact rather than a full banner: the dashboard is optional, and a
 * second large status block would imply the app is broken when it simply has
 * nothing configured.
 */
@Composable
private fun DashboardStatusLine(state: DashboardState) {
    val colors = LocalRunColors.current
    val (color, label, detail) = when (state) {
        DashboardState.Off -> Triple(
            colors.muted,
            stringResource(R.string.dashboard_status_off),
            null,
        )
        DashboardState.Connecting -> Triple(
            colors.muted,
            stringResource(R.string.connection_checking),
            null,
        )
        DashboardState.Ready -> Triple(
            colors.completed,
            stringResource(R.string.dashboard_status_ready),
            null,
        )
        is DashboardState.Failed -> Triple(
            colors.failed,
            stringResource(R.string.dashboard_failed),
            state.message.takeIf { it.isNotBlank() },
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(color, Modifier.padding(top = 5.dp), size = 7)
        Column {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
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
            // Keep the platform's own reason. Swallowing it was hiding the
            // difference between a wrong address, a blocked port and a refused
            // connection — all of which read as "unreachable" otherwise.
            listOfNotNull(
                stringResource(R.string.connection_unreachable_help),
                connection.detail?.takeIf { it.isNotBlank() },
            ).joinToString("\n"),
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

/**
 * UI scale stepper.
 *
 * Stepper rather than a slider: the useful range is narrow and a slider on a
 * surface that itself rescales as you drag is unpleasant to aim at.
 */
@Composable
private fun ScaleRow(scale: Float, onChange: (Float) -> Unit) {
    val colors = LocalRunColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_ui_scale),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_ui_scale_why),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        OutlinedButton(
            onClick = { onChange(scale - UI_SCALE_STEP) },
            enabled = scale > UI_SCALE_MIN + 0.001f,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("−") }

        Text(
            text = "${(scale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.Center,
        )

        OutlinedButton(
            onClick = { onChange(scale + UI_SCALE_STEP) },
            enabled = scale < UI_SCALE_MAX - 0.001f,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("+") }
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
