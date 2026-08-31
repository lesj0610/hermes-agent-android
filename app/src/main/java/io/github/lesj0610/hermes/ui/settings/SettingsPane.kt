package io.github.lesj0610.hermes.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.BuildConfig
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.DASHBOARD_DEFAULT_PORT
import io.github.lesj0610.hermes.core.GATEWAY_DEFAULT_PORT
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.Language
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.UI_SCALE_MAX
import io.github.lesj0610.hermes.core.UI_SCALE_MIN
import io.github.lesj0610.hermes.core.UI_SCALE_STEP
import io.github.lesj0610.hermes.core.coercePort
import io.github.lesj0610.hermes.core.defaultDashboardHost
import io.github.lesj0610.hermes.core.parseEndpoint
import io.github.lesj0610.hermes.data.UpdateState
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.ui.Connection
import io.github.lesj0610.hermes.ui.DashboardState
import io.github.lesj0610.hermes.ui.components.ChevronIcon
import io.github.lesj0610.hermes.ui.components.StatusDot
import io.github.lesj0610.hermes.ui.dashboard.DashboardSkillsCard
import io.github.lesj0610.hermes.ui.dashboard.DashboardUnavailable
import io.github.lesj0610.hermes.ui.dashboard.ProfilesCard
import io.github.lesj0610.hermes.ui.gateway.AgentSkillsCard
import io.github.lesj0610.hermes.ui.gateway.GatewayStateCard
import io.github.lesj0610.hermes.ui.gateway.ToolsetsCard
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import kotlin.math.roundToInt

/** Live grant state, re-read whenever the screen resumes. */
data class PermissionState(
    val canNotify: Boolean = true,
    val batteryExempt: Boolean = false,
)

/**
 * The pages settings can be on. `null` is the hub.
 *
 * Settings used to be one long scroll with every field in it, and everything
 * about the server lived in destinations of its own — a gateway page and a
 * workspace page that were lists with no shape. Both are the same kind of thing:
 * something you set up once and then check on. So they are one place now, and
 * each subject gets a page instead of a section of a scroll, which is what makes
 * the hub readable at a glance.
 */
private enum class SettingsSection {
    Gateway, Dashboard, Model, Profiles, Skills, Toolsets, ServerState,
    Display, Language, Notifications, Permissions, Update,
}

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
    /** App updates. Defaulted so previews render without an update check. */
    updateState: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onGrantInstall: () -> Unit = {},
    onToggleUpdateChecks: (Boolean) -> Unit = {},
    /** The model in effect, resolved the same way the composer's chip resolves it. */
    activeModel: String = "",
    health: DetailedHealth? = null,
    toolsets: List<Toolset> = emptyList(),
    agentSkills: List<Skill> = emptyList(),
    profiles: List<Profile> = emptyList(),
    activeProfile: ActiveProfile? = null,
    dashboardSkills: List<DashboardSkill> = emptyList(),
    onSelectProfile: (Profile) -> Unit = {},
    onToggleSkill: (DashboardSkill) -> Unit = {},
    onRetryDashboard: () -> Unit = {},
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }

    // System back returns to the hub before it leaves settings. Without this the
    // only way out of a subpage is the arrow, and back would drop the user out
    // of the screen entirely from three levels of context in.
    BackHandler(enabled = section != null) { section = null }

    if (section == null) {
        SettingsHub(
            settings = settings,
            connection = connection,
            dashboardState = dashboardState,
            permissions = permissions,
            activeModel = activeModel,
            health = health,
            toolsets = toolsets,
            dashboardSkills = dashboardSkills,
            activeProfile = activeProfile,
            updateState = updateState,
            onOpen = { section = it },
            modifier = modifier,
        )
        return
    }

    SubPage(
        title = sectionTitle(section!!),
        onBack = { section = null },
        modifier = modifier,
    ) {
        when (section!!) {
            SettingsSection.Gateway -> GatewaySection(settings, connection, onSaveServer)
            SettingsSection.Dashboard -> DashboardSection(settings, dashboardState, onSaveDashboard)
            SettingsSection.Model -> ModelSection(settings, models, onSelectModel)
            SettingsSection.Profiles -> if (dashboardState is DashboardState.Ready) {
                ProfilesCard(profiles, activeProfile, onSelectProfile)
            } else {
                DashboardUnavailable(dashboardState, onRetryDashboard, Modifier.fillMaxWidth())
            }
            SettingsSection.Skills -> if (dashboardState is DashboardState.Ready) {
                DashboardSkillsCard(dashboardSkills, onToggleSkill)
            } else {
                DashboardUnavailable(dashboardState, onRetryDashboard, Modifier.fillMaxWidth())
            }
            SettingsSection.Toolsets -> ToolsetsCard(toolsets)
            SettingsSection.ServerState -> {
                GatewayStateCard(health)
                AgentSkillsCard(agentSkills)
            }
            SettingsSection.Display -> DisplaySection(settings, onSelectLayoutMode, onSetUiScale)
            SettingsSection.Language -> LanguageSection(settings, onSelectLanguage)
            SettingsSection.Notifications -> NotificationsSection(
                settings, onToggleApprovals, onToggleCompletion,
            )
            SettingsSection.Update -> UpdateSection(
                settings = settings,
                state = updateState,
                onCheck = onCheckUpdate,
                onDownload = onDownloadUpdate,
                onGrant = onGrantInstall,
                onToggleChecks = onToggleUpdateChecks,
            )
            SettingsSection.Permissions -> PermissionsSection(
                permissions, onRequestNotifications, onRequestBackground,
            )
        }
    }
}

@Composable
private fun sectionTitle(section: SettingsSection): String = when (section) {
    SettingsSection.Gateway -> stringResource(R.string.settings_group_server)
    SettingsSection.Dashboard -> stringResource(R.string.settings_group_dashboard)
    SettingsSection.Model -> stringResource(R.string.settings_model)
    SettingsSection.Profiles -> stringResource(R.string.dashboard_profiles)
    SettingsSection.Skills -> stringResource(R.string.settings_row_skills)
    SettingsSection.Toolsets -> stringResource(R.string.settings_row_toolsets)
    SettingsSection.ServerState -> stringResource(R.string.settings_row_server_state)
    SettingsSection.Display -> stringResource(R.string.settings_group_display)
    SettingsSection.Language -> stringResource(R.string.settings_group_language)
    SettingsSection.Notifications -> stringResource(R.string.settings_group_notifications)
    SettingsSection.Permissions -> stringResource(R.string.settings_group_permissions)
    SettingsSection.Update -> stringResource(R.string.update_section)
}

// ── hub ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsHub(
    settings: HermesSettings,
    connection: Connection,
    dashboardState: DashboardState,
    permissions: PermissionState,
    activeModel: String,
    health: DetailedHealth?,
    toolsets: List<Toolset>,
    dashboardSkills: List<DashboardSkill>,
    activeProfile: ActiveProfile?,
    updateState: UpdateState,
    onOpen: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gateway = remember(settings.baseUrl) {
        parseEndpoint(settings.baseUrl, GATEWAY_DEFAULT_PORT)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionBanner(connection)

        Group(stringResource(R.string.settings_group_connection)) {
            NavRow(
                label = stringResource(R.string.settings_group_server),
                value = settings.baseUrl.takeIf { it.isNotBlank() }
                    ?: gateway.host.takeIf { it.isNotBlank() },
                onClick = { onOpen(SettingsSection.Gateway) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            NavRow(
                label = stringResource(R.string.settings_group_dashboard),
                value = dashboardSummary(dashboardState),
                onClick = { onOpen(SettingsSection.Dashboard) },
            )
        }

        // Everything the server reports about itself. Read-only, which is why it
        // sits apart from the fields above that change what the app talks to.
        if (health != null || toolsets.isNotEmpty()) {
            Group(stringResource(R.string.gateway_title)) {
                if (health != null) {
                    NavRow(
                        label = stringResource(R.string.settings_row_server_state),
                        value = health.gatewayState ?: health.status,
                        onClick = { onOpen(SettingsSection.ServerState) },
                    )
                }
                if (health != null && toolsets.isNotEmpty()) {
                    HorizontalDivider(color = LocalRunColors.current.line)
                }
                if (toolsets.isNotEmpty()) {
                    NavRow(
                        label = stringResource(R.string.settings_row_toolsets),
                        value = stringResource(
                            R.string.settings_sub_toolsets,
                            toolsets.count { it.enabled },
                            toolsets.size,
                        ),
                        onClick = { onOpen(SettingsSection.Toolsets) },
                    )
                }
            }
        }

        // Hidden entirely when no dashboard is configured: these two rows are
        // the dashboard's, and offering them against nothing was most of what
        // made the old workspace screen read as a list of dead ends.
        if (dashboardState != DashboardState.Off) {
            Group(stringResource(R.string.dashboard_title)) {
                NavRow(
                    label = stringResource(R.string.dashboard_profiles),
                    value = activeProfile?.active,
                    onClick = { onOpen(SettingsSection.Profiles) },
                )
                HorizontalDivider(color = LocalRunColors.current.line)
                NavRow(
                    label = stringResource(R.string.settings_row_skills),
                    value = dashboardSkills
                        .count { it.enabled }
                        .takeIf { dashboardSkills.isNotEmpty() }
                        ?.let { stringResource(R.string.settings_sub_skills, it) },
                    onClick = { onOpen(SettingsSection.Skills) },
                )
            }
        }

        Group(stringResource(R.string.settings_group_app)) {
            NavRow(
                label = stringResource(R.string.settings_model),
                value = activeModel.takeIf { it.isNotBlank() },
                onClick = { onOpen(SettingsSection.Model) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            NavRow(
                label = stringResource(R.string.settings_group_display),
                value = stringResource(
                    R.string.settings_sub_display,
                    layoutModeLabel(settings.layoutMode),
                    (settings.uiScale * 100).roundToInt(),
                ),
                onClick = { onOpen(SettingsSection.Display) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            NavRow(
                label = stringResource(R.string.settings_group_language),
                value = languageLabel(settings.language),
                onClick = { onOpen(SettingsSection.Language) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            NavRow(
                label = stringResource(R.string.settings_group_notifications),
                value = stringResource(
                    R.string.settings_sub_on_count,
                    listOf(settings.notifyApprovals, settings.notifyCompletion).count { it },
                ),
                onClick = { onOpen(SettingsSection.Notifications) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            val missing = listOf(permissions.canNotify, permissions.batteryExempt).count { !it }
            NavRow(
                label = stringResource(R.string.settings_group_permissions),
                value = if (missing == 0) {
                    stringResource(R.string.settings_sub_permissions_ok)
                } else {
                    stringResource(R.string.settings_sub_permissions_missing, missing)
                },
                warn = missing > 0,
                onClick = { onOpen(SettingsSection.Permissions) },
            )
            HorizontalDivider(color = LocalRunColors.current.line)
            NavRow(
                label = stringResource(R.string.update_section),
                // The row carries the news, so a new release is visible here
                // even after the banner has been dismissed.
                value = (updateState as? UpdateState.Available)
                    ?.let { stringResource(R.string.update_available, it.release.version) }
                    ?: stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
                onClick = { onOpen(SettingsSection.Update) },
            )
        }
    }
}

/**
 * App updates.
 *
 * The app downloads a release and asks Android to install it; Android decides.
 * There is no silent update and no path that skips the system's confirmation —
 * the button below raises the same installer a file manager would.
 */
@Composable
private fun UpdateSection(
    settings: HermesSettings,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onGrant: () -> Unit,
    onToggleChecks: (Boolean) -> Unit,
) {
    val colors = LocalRunColors.current

    Group(stringResource(R.string.update_section)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                is UpdateState.Checking -> Text(
                    text = stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )

                is UpdateState.Downloading -> Text(
                    text = stringResource(
                        R.string.update_downloading,
                        (state.fraction * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )

                is UpdateState.Available -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }

                is UpdateState.Failed ->
                    if (state.reason == UpdateState.Reason.Permission) {
                        Button(onClick = onGrant) { Text(stringResource(R.string.update_grant)) }
                    } else {
                        OutlinedButton(onClick = onCheck) {
                            Text(stringResource(R.string.update_check))
                        }
                    }

                else -> OutlinedButton(onClick = onCheck) {
                    Text(stringResource(R.string.update_check))
                }
            }
        }

        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        when (state) {
            is UpdateState.Available -> {
                Text(
                    text = stringResource(R.string.update_available, state.release.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.release.notes.isNotBlank()) {
                    // The release notes as published, not reformatted: this is
                    // the only place the user reads what changed.
                    Text(
                        text = state.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            is UpdateState.Failed -> Text(
                text = stringResource(
                    when (state.reason) {
                        UpdateState.Reason.Download -> R.string.update_failed_download
                        UpdateState.Reason.Signature -> R.string.update_failed_signature
                        UpdateState.Reason.Permission -> R.string.update_needs_permission
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )

            else -> Unit
        }
    }

    Group(stringResource(R.string.settings_group_app)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_auto),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.update_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            Switch(checked = settings.updateChecks, onCheckedChange = onToggleChecks)
        }
        HorizontalDivider(color = colors.line)
        Text(
            text = stringResource(R.string.update_source),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun dashboardSummary(state: DashboardState): String = when (state) {
    DashboardState.Off -> stringResource(R.string.dashboard_status_off)
    DashboardState.Connecting -> stringResource(R.string.connection_checking)
    DashboardState.Ready -> stringResource(R.string.dashboard_status_ready)
    is DashboardState.Failed -> stringResource(R.string.dashboard_failed)
}

@Composable
private fun layoutModeLabel(mode: LayoutMode): String = when (mode) {
    LayoutMode.Auto -> stringResource(R.string.settings_layout_auto)
    LayoutMode.Phone -> stringResource(R.string.settings_layout_phone)
    LayoutMode.Tablet -> stringResource(R.string.settings_layout_tablet)
}

@Composable
private fun languageLabel(tag: String): String =
    Language.SUPPORTED.firstOrNull { it.tag == tag }?.nativeName
        ?: stringResource(R.string.settings_language_system)

/**
 * A hub row: what it is on the left, what it is currently set to on the right.
 *
 * The value is the point. A row that only names a screen makes the user open
 * every one of them to find out what is configured — which is what the flat
 * lists this replaced forced them to do.
 */
@Composable
private fun NavRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    warn: Boolean = false,
) {
    val colors = LocalRunColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        value?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) colors.awaiting else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        ChevronIcon(modifier = Modifier.size(16.dp))
    }
}

/** A subject's own page, with the way back. */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalRunColors.current
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(modifier = Modifier.size(18.dp), pointLeft = true)
            }
            Text(text = title, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider(color = colors.line)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

// ── sections ──────────────────────────────────────────────────────────────

@Composable
private fun GatewaySection(
    settings: HermesSettings,
    connection: Connection,
    onSaveServer: (String, Int, String) -> Unit,
) {
    val colors = LocalRunColors.current
    val gateway = remember(settings.baseUrl) {
        parseEndpoint(settings.baseUrl, GATEWAY_DEFAULT_PORT)
    }
    var host by remember(gateway) { mutableStateOf(gateway.host) }
    var port by remember(gateway) { mutableStateOf(gateway.port.toString()) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }

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
}

@Composable
private fun DashboardSection(
    settings: HermesSettings,
    dashboardState: DashboardState,
    onSaveDashboard: (String, Int, String, String) -> Unit,
) {
    val colors = LocalRunColors.current
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
}

@Composable
private fun ModelSection(
    settings: HermesSettings,
    models: List<ModelEntry>,
    onSelectModel: (String) -> Unit,
) {
    Group(stringResource(R.string.settings_model)) {
        // Named for what it does rather than for a value: choosing it sends no
        // model with the request, leaving the gateway on whatever it is
        // configured for.
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
}

@Composable
private fun DisplaySection(
    settings: HermesSettings,
    onSelectLayoutMode: (LayoutMode) -> Unit,
    onSetUiScale: (Float) -> Unit,
) {
    val colors = LocalRunColors.current
    Group(stringResource(R.string.settings_layout)) {
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

        ScaleRow(scale = settings.uiScale, onChange = onSetUiScale)
    }
}

@Composable
private fun LanguageSection(settings: HermesSettings, onSelectLanguage: (String) -> Unit) {
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
}

@Composable
private fun NotificationsSection(
    settings: HermesSettings,
    onToggleApprovals: (Boolean) -> Unit,
    onToggleCompletion: (Boolean) -> Unit,
) {
    val colors = LocalRunColors.current
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
}

@Composable
private fun PermissionsSection(
    permissions: PermissionState,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    val colors = LocalRunColors.current
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
