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
 * What a side rail is showing.
 *
 * Both rails draw from the same set, which is why there is no separate "swap
 * sides" control: putting the session list on the right is just choosing it for
 * the right rail. [None] hides that rail entirely.
 */
enum class RailPanel { None, Sessions, Activity, Cron, Gateway, Dashboard }

/** Which side of the transcript a rail sits on. */
enum class RailSide { Left, Right }

/**
 * Bounds for [HermesSettings.uiScale]. Kept narrow deliberately: below 0.85 the
 * approval sheet's command text stops being readable, and above 1.4 the tablet
 * shell no longer fits three panes on real hardware.
 */
const val UI_SCALE_MIN = 0.85f
const val UI_SCALE_MAX = 1.40f
const val UI_SCALE_STEP = 0.05f

/**
 * Bounds for the multi-pane rail widths, in dp.
 *
 * The floor keeps a session title readable rather than elided to nothing; the
 * ceiling keeps the transcript — the reason the app exists — from being squeezed
 * into a column narrower than the rails beside it.
 */
const val RAIL_WIDTH_MIN = 220f
const val RAIL_WIDTH_MAX = 480f
const val RAIL_WIDTH_DEFAULT = 300f

data class HermesSettings(
    val baseUrl: String = "",
    val token: String = "",
    val model: String = "",
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
    /** Multi-pane rail widths in dp, adjustable by dragging the dividers. */
    val sessionRailWidth: Float = RAIL_WIDTH_DEFAULT,
    val activityRailWidth: Float = RAIL_WIDTH_DEFAULT,
    val leftRail: RailPanel = RailPanel.Sessions,
    val rightRail: RailPanel = RailPanel.Activity,
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
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFY_APPROVALS = booleanPreferencesKey("notify_approvals")
        val NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion")
        val LAYOUT_MODE = stringPreferencesKey("layout_mode")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val RAIL_SESSIONS = floatPreferencesKey("rail_sessions_width")
        val RAIL_ACTIVITY = floatPreferencesKey("rail_activity_width")
        val LEFT_RAIL = stringPreferencesKey("left_rail")
        val RIGHT_RAIL = stringPreferencesKey("right_rail")
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
                language = prefs[Keys.LANGUAGE].orEmpty(),
                notifyApprovals = prefs[Keys.NOTIFY_APPROVALS] ?: true,
                notifyCompletion = prefs[Keys.NOTIFY_COMPLETION] ?: true,
                layoutMode = prefs[Keys.LAYOUT_MODE]
                    // An unknown value means a downgrade or a hand-edited file;
                    // fall back rather than crash on valueOf.
                    ?.let { stored -> LayoutMode.entries.firstOrNull { it.name == stored } }
                    ?: LayoutMode.Auto,
                uiScale = (prefs[Keys.UI_SCALE] ?: 1.0f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                sessionRailWidth = (prefs[Keys.RAIL_SESSIONS] ?: RAIL_WIDTH_DEFAULT)
                    .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX),
                activityRailWidth = (prefs[Keys.RAIL_ACTIVITY] ?: RAIL_WIDTH_DEFAULT)
                    .coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX),
                leftRail = rail(prefs[Keys.LEFT_RAIL], RailPanel.Sessions),
                rightRail = rail(prefs[Keys.RIGHT_RAIL], RailPanel.Activity),
                showStatusBar = prefs[Keys.SHOW_STATUS_BAR] ?: true,
                dashboardUrl = prefs[Keys.DASHBOARD_URL].orEmpty(),
                dashboardUsername = prefs[Keys.DASHBOARD_USER].orEmpty(),
                dashboardPassword = SecretStore.unseal(prefs[Keys.DASHBOARD_PASS_SEALED].orEmpty()),
            )
        }

    suspend fun current(): HermesSettings = settings.first()

    suspend fun setServer(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[Keys.TOKEN_SEALED] = SecretStore.seal(token.trim())
        }
    }

    suspend fun setDashboard(url: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DASHBOARD_URL] = url.trim().trimEnd('/')
            prefs[Keys.DASHBOARD_USER] = username.trim()
            prefs[Keys.DASHBOARD_PASS_SEALED] = SecretStore.seal(password)
        }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[Keys.MODEL] = model }
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
    suspend fun setRailWidths(sessionDp: Float, activityDp: Float) {
        context.dataStore.edit {
            it[Keys.RAIL_SESSIONS] = sessionDp.coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
            it[Keys.RAIL_ACTIVITY] = activityDp.coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
        }
    }

    suspend fun setRailPanel(side: RailSide, panel: RailPanel) {
        context.dataStore.edit {
            it[if (side == RailSide.Left) Keys.LEFT_RAIL else Keys.RIGHT_RAIL] = panel.name
        }
    }

    suspend fun setShowStatusBar(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_STATUS_BAR] = show }
    }

    /** Restores every layout choice. Server settings and the API key are untouched. */
    suspend fun resetLayout() {
        context.dataStore.edit {
            it.remove(Keys.LEFT_RAIL)
            it.remove(Keys.RIGHT_RAIL)
            it.remove(Keys.SHOW_STATUS_BAR)
            it.remove(Keys.RAIL_SESSIONS)
            it.remove(Keys.RAIL_ACTIVITY)
            it.remove(Keys.LAYOUT_MODE)
            it.remove(Keys.UI_SCALE)
        }
    }
}
