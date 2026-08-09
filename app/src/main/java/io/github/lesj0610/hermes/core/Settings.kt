package io.github.lesj0610.hermes.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/** Everything the app remembers between launches. */
data class HermesSettings(
    val baseUrl: String = "",
    val token: String = "",
    val model: String = "",
    /** BCP-47 tag, or empty to follow the system locale. See [LanguageOption]. */
    val language: String = "",
    val notifyApprovals: Boolean = true,
    val notifyCompletion: Boolean = true,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN_SEALED = stringPreferencesKey("token_sealed")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFY_APPROVALS = booleanPreferencesKey("notify_approvals")
        val NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion")
    }

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
            )
        }

    suspend fun current(): HermesSettings = settings.first()

    suspend fun setServer(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[Keys.TOKEN_SEALED] = SecretStore.seal(token.trim())
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
}
