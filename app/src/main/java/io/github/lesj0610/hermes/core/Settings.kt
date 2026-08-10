package io.github.lesj0610.hermes.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/** Everything the app remembers between launches. */
/**
 * Which shell to draw.
 *
 * [Auto] is right for almost everyone: a foldable is both device types within
 * one session, split-screen hands a tablet a phone-sized window, and desktop
 * windowing resizes freely. Deciding once at launch from "is this a tablet"
 * gets all three wrong. The manual values exist because the automatic call is
 * about window size rather than taste, and someone may want the other one.
 */
enum class LayoutMode { Auto, Phone, Tablet }

/**
 * What the rail beside the transcript is showing. [None] hides it.
 *
 * There is no session list here: the drawer *is* the session column, and when
 * pinned it occupies the left of the shell. Offering the same list as a rail as
 * well would have meant two places showing one thing.
 */
enum class RailPanel { None, Activity, Cron, Gateway, Dashboard }

/**
 * Reasoning efforts the gateway accepts, plus [Default] for "say nothing".
 *
 * Sending nothing is not the same as sending `none`: `none` disables reasoning
 * for the turn, while omitting the option leaves whatever the server is
 * configured to do. The picker has to be able to express both.
 */
enum class ReasoningEffort(val wire: String?) {
    Default(null),
    Off("none"),
    Minimal("minimal"),
    Low("low"),
    Medium("medium"),
    High("high"),
    XHigh("xhigh"),
}

/** The wire value the API sends for [ReasoningEffort.Off]. */
const val REASONING_OFF = "none"

/**
 * Bounds for [HermesSettings.uiScale]. Kept narrow deliberately: below 0.85 the
 * approval sheet's command text stops being readable, and above 1.4 the tablet
 * shell no longer fits three panes on real hardware.
 */
const val UI_SCALE_MIN = 0.85f
const val UI_SCALE_MAX = 1.40f
const val UI_SCALE_STEP = 0.05f

/**
 * Bounds for the column widths, in dp, shared by the pinned drawer and the rail.
 *
 * The floor keeps a session title readable rather than elided to nothing; the
 * ceiling keeps the transcript — the reason the app exists — from being squeezed
 * into a column narrower than the ones beside it.
 */
const val RAIL_WIDTH_MIN = 220f
const val RAIL_WIDTH_MAX = 480f
const val RAIL_WIDTH_DEFAULT = 300f

data class HermesSettings(
    val baseUrl: String = "",
    val token: String = "",
    val model: String = "",
    /** Provider slug for [model], when it came from the inventory. */
    val provider: String = "",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.Default,
    /** BCP-47 tag, or empty to follow the system locale. See [Language]. */
    val language: String = "",
    val notifyApprovals: Boolean = true,
    val notifyCompletion: Boolean = true,
    val layoutMode: LayoutMode = LayoutMode.Auto,
    /**
     * Multiplier applied to both density and font scale, so text and spacing
     * grow together instead of text alone overflowing fixed-size rows.
     * Clamped to [UI_SCALE_MIN]..[UI_SCALE_MAX].
     */
    val uiScale: Float = 1.0f,
    /** Column widths in dp, adjustable by dragging the dividers. */
    val drawerWidth: Float = RAIL_WIDTH_DEFAULT,
    val railWidth: Float = RAIL_WIDTH_DEFAULT,
    val railPanel: RailPanel = RailPanel.Activity,
    /**
     * Whether the drawer stays open as the shell's left column.
     *
     * Only honoured where the window can hold three columns; a two-column
     * window has both of its slots spoken for by the transcript and the rail,
     * so there the drawer opens over the content instead. The preference is
     * still remembered in that case rather than being rewritten, since the same
     * device may be a foldable that unfolds back into three.
     */
    val drawerPinned: Boolean = true,
    val showStatusBar: Boolean = true,
    /**
     * Dashboard server (`hermes dashboard`, default port 9119). Optional: the
     * conversation works without it. Left blank, the dashboard panel stays
     * hidden rather than failing against a server that is not running.
     */
    val dashboardUrl: String = "",
    val dashboardUsername: String = "",
    val dashboardPassword: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    val dashboardConfigured: Boolean
        get() = dashboardUrl.isNotBlank() &&
            dashboardUsername.isNotBlank() &&
            dashboardPassword.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN_SEALED = stringPreferencesKey("token_sealed")
        val MODEL = stringPreferencesKey("model")
        val PROVIDER = stringPreferencesKey("provider")
        val REASONING = stringPreferencesKey("reasoning_effort")
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFY_APPROVALS = booleanPreferencesKey("notify_approvals")
        val NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion")
        val LAYOUT_MODE = stringPreferencesKey("layout_mode")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val DRAWER_WIDTH = floatPreferencesKey("drawer_width")
        val RAIL_WIDTH = floatPreferencesKey("rail_width")
        val RAIL_PANEL = stringPreferencesKey("rail_panel")
        val DRAWER_PINNED = booleanPreferencesKey("drawer_pinned")
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val DASHBOARD_URL = stringPreferencesKey("dashboard_url")
        val DASHBOARD_USER = stringPreferencesKey("dashboard_username")
        // Sealed with the same Keystore path as the gateway token: this grants
        // access to a management surface with dozens of write routes.
        val DASHBOARD_PASS_SEALED = stringPreferencesKey("dashboard_password_sealed")
    }

    private fun rail(stored: String?, fallback: RailPanel): RailPanel =
        stored?.let { s -> RailPanel.entries.firstOrNull { it.name == s } } ?: fallback

    val settings: Flow<HermesSettings> = context.dataStore.data
        .catch { cause ->
            // A corrupt preferences file must not brick the app; fall back to
            // defaults and let the user re-enter the server details.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            HermesSettings(
                baseUrl = prefs[Keys.BASE_URL].orEmpty(),
                token = SecretStore.unseal(prefs[Keys.TOKEN_SEALED].orEmpty()),
                model = prefs[Keys.MODEL].orEmpty(),
                provider = prefs[Keys.PROVIDER].orEmpty(),
                reasoningEffort = prefs[Keys.REASONING]
                    ?.let { stored -> ReasoningEffort.entries.firstOrNull { it.name == stored } }
                    ?: ReasoningEffort.Default,
                language = prefs[Keys.LANGUAGE].orEmpty(),
                notifyApprovals = prefs[Keys.NOTIFY_APPROVALS] ?: true,
                notifyCompletion = prefs[Keys.NOTIFY_COMPLETION] ?: true,
                layoutMode = prefs[Keys.LAYOUT_MODE]
                    // An unknown value means a downgrade or a hand-edited file;
                    // fall back rather than crash on valueOf.
                    ?.let { stored -> LayoutMode.entries.firstOrNull { it.name == stored } }
                    ?: LayoutMode.Auto,
                uiScale = (prefs[Keys.UI_SCALE] ?: 1.0f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                drawerWidth = (prefs[Keys.DRAWER_WIDTH] ?: RAIL_WIDTH_DEFAULT)
                    .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX),
                railWidth = (prefs[Keys.RAIL_WIDTH] ?: RAIL_WIDTH_DEFAULT)
                    .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX),
                railPanel = rail(prefs[Keys.RAIL_PANEL], RailPanel.Activity),
                drawerPinned = prefs[Keys.DRAWER_PINNED] ?: true,
                showStatusBar = prefs[Keys.SHOW_STATUS_BAR] ?: true,
                dashboardUrl = prefs[Keys.DASHBOARD_URL].orEmpty(),
                dashboardUsername = prefs[Keys.DASHBOARD_USER].orEmpty(),
                dashboardPassword = SecretStore.unseal(prefs[Keys.DASHBOARD_PASS_SEALED].orEmpty()),
            )
        }

    suspend fun current(): HermesSettings = settings.first()

    /**
     * [host] is what the user typed in the address field — a bare host, or one
     * carrying a scheme and path. The port arrives separately because it has
     * its own field; composing them here keeps one canonical URL in storage.
     */
    suspend fun setServer(host: String, port: Int, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = buildEndpointUrl(host, port)
            prefs[Keys.TOKEN_SEALED] = SecretStore.seal(token.trim())
        }
    }

    suspend fun setDashboard(host: String, port: Int, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DASHBOARD_URL] = buildEndpointUrl(host, port)
            prefs[Keys.DASHBOARD_USER] = username.trim()
            prefs[Keys.DASHBOARD_PASS_SEALED] = SecretStore.seal(password)
        }
    }

    /**
     * [provider] is blank when the model was typed rather than picked, which is
     * the case on a gateway that cannot serve the inventory. The gateway
     * honours a bare model on the run route, so the slug is genuinely optional.
     */
    suspend fun setModel(model: String, provider: String = "") {
        context.dataStore.edit {
            it[Keys.MODEL] = model
            it[Keys.PROVIDER] = provider
        }
    }

    suspend fun setReasoningEffort(effort: ReasoningEffort) {
        context.dataStore.edit { it[Keys.REASONING] = effort.name }
    }

    suspend fun setLanguage(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    suspend fun setNotifyApprovals(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_APPROVALS] = enabled }
    }

    suspend fun setNotifyCompletion(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_COMPLETION] = enabled }
    }

    suspend fun setLayoutMode(mode: LayoutMode) {
        context.dataStore.edit { it[Keys.LAYOUT_MODE] = mode.name }
    }

    suspend fun setUiScale(scale: Float) {
        context.dataStore.edit {
            it[Keys.UI_SCALE] = scale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        }
    }

    /** Called once when a divider drag ends, not per frame. */
    suspend fun setColumnWidths(drawerDp: Float, railDp: Float) {
        context.dataStore.edit {
            it[Keys.DRAWER_WIDTH] = drawerDp.coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
            it[Keys.RAIL_WIDTH] = railDp.coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
        }
    }

    suspend fun setRailPanel(panel: RailPanel) {
        context.dataStore.edit { it[Keys.RAIL_PANEL] = panel.name }
    }

    suspend fun setDrawerPinned(pinned: Boolean) {
        context.dataStore.edit { it[Keys.DRAWER_PINNED] = pinned }
    }

    suspend fun setShowStatusBar(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_STATUS_BAR] = show }
    }

    /** Restores every layout choice. Server settings and the API key are untouched. */
    suspend fun resetLayout() {
        context.dataStore.edit {
            it.remove(Keys.RAIL_PANEL)
            it.remove(Keys.DRAWER_PINNED)
            it.remove(Keys.SHOW_STATUS_BAR)
            it.remove(Keys.DRAWER_WIDTH)
            it.remove(Keys.RAIL_WIDTH)
            it.remove(Keys.LAYOUT_MODE)
            it.remove(Keys.UI_SCALE)
        }
    }
}
