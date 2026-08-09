package io.github.lesj0610.hermes.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.lesj0610.hermes.core.Graph
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.UiError
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.SessionSummary

/** How the gateway is currently reachable. Drives the banner in settings and the rail header. */
sealed interface Connection {
    data object NotConfigured : Connection
    data object Checking : Connection
    data class Connected(val version: String?, val latencyMs: Int) : Connection
    data object Unauthorized : Connection
    data class Unreachable(val detail: String?) : Connection
}

/** Which pane the user is looking at. On a tablet several are visible at once. */
enum class Pane { Sessions, Chat, Settings }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = Graph.get(app)

    val settings: StateFlow<HermesSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, HermesSettings())

    val chat: StateFlow<ChatState> = graph.runEngine.state

    private val _connection = MutableStateFlow<Connection>(Connection.Checking)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _pane = MutableStateFlow(Pane.Sessions)
    val pane: StateFlow<Pane> = _pane.asStateFlow()

    init {
        refresh()
    }

    fun show(pane: Pane) {
        _pane.value = pane
    }

    fun refresh() {
        viewModelScope.launch {
            val current = graph.settings.current()
            if (!current.isConfigured) {
                _connection.value = Connection.NotConfigured
                return@launch
            }

            _connection.value = Connection.Checking
            val startedAt = System.nanoTime()
            val health = runCatching { graph.api.health() }
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000).toInt()

            health.onFailure { cause ->
                _connection.value = Connection.Unreachable(cause.message)
                return@launch
            }

            // /health is unauthenticated, so reachability alone proves nothing
            // about the key. Probing an authenticated route is what separates
            // "server is down" from "key is wrong" — two very different fixes.
            runCatching { graph.api.models() }
                .onSuccess { models ->
                    _models.value = models
                    _connection.value = Connection.Connected(health.getOrNull()?.version, elapsedMs)
                    loadSessions()
                }
                .onFailure { cause ->
                    _connection.value = if (cause is HermesUnauthorizedException) {
                        Connection.Unauthorized
                    } else {
                        Connection.Unreachable(cause.message)
                    }
                }
        }
    }

    private suspend fun loadSessions() {
        runCatching { graph.api.sessions() }
            .onSuccess { page -> _sessions.value = page.data.filterNot { it.archived } }
    }

    fun openSession(sessionId: String?) {
        _pane.value = Pane.Chat
        viewModelScope.launch { graph.runEngine.openSession(sessionId) }
    }

    fun send(prompt: String) {
        viewModelScope.launch {
            val model = graph.settings.current().model.takeIf { it.isNotBlank() }
            graph.runEngine.send(prompt, model)
        }
    }

    fun respondToApproval(choice: String) = graph.runEngine.respondToApproval(choice)

    fun stop() = graph.runEngine.stop()

    fun dismissError() = graph.runEngine.clearError()

    fun saveServer(baseUrl: String, token: String) {
        viewModelScope.launch {
            graph.settings.setServer(baseUrl, token)
            refresh()
        }
    }

    fun setModel(model: String) {
        viewModelScope.launch { graph.settings.setModel(model) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { graph.settings.setLanguage(tag) }
    }

    fun setNotifyApprovals(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setNotifyApprovals(enabled) }
    }

    fun setNotifyCompletion(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setNotifyCompletion(enabled) }
    }

    /** Exposed for the settings screen so it can show why a save did not take. */
    fun currentError(): UiError? = chat.value.error
}
