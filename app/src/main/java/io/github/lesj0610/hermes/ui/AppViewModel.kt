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
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.RailPanel
import io.github.lesj0610.hermes.core.RailSide
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.UiError
import io.github.lesj0610.hermes.net.Capabilities
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.Job
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset

/** How the gateway is currently reachable. Drives the banner in settings and the rail header. */
sealed interface Connection {
    data object NotConfigured : Connection
    data object Checking : Connection
    data class Connected(val version: String?, val latencyMs: Int) : Connection
    data object Unauthorized : Connection
    data class Unreachable(val detail: String?) : Connection
}

/** Which pane the user is looking at. On a tablet several are visible at once. */
enum class Pane { Sessions, Chat, Cron, Gateway, Settings }

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

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _health = MutableStateFlow<DetailedHealth?>(null)
    val health: StateFlow<DetailedHealth?> = _health.asStateFlow()

    private val _toolsets = MutableStateFlow<List<Toolset>>(emptyList())
    val toolsets: StateFlow<List<Toolset>> = _toolsets.asStateFlow()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /**
     * What the connected gateway says it supports. Null until the first probe.
     * Panels consult this so an older server hides features instead of showing
     * a screen that only produces 404s.
     */
    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

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
                    loadOperational()
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

    /**
     * Capability probe first, then only the calls it says are worth making.
     * Every one of these is optional — a failure leaves its panel empty rather
     * than breaking the connection, because none of them is needed to hold a
     * conversation.
     */
    private suspend fun loadOperational() {
        val caps = runCatching { graph.api.capabilities() }.getOrNull()
        _capabilities.value = caps

        if (caps?.jobsAdmin != false) {
            runCatching { graph.api.jobs() }.onSuccess { _jobs.value = it }
        }
        if (caps?.healthDetailed != false) {
            runCatching { graph.api.healthDetailed() }.onSuccess { _health.value = it }
        }
        runCatching { graph.api.toolsets() }.onSuccess { _toolsets.value = it }
        runCatching { graph.api.skills() }.onSuccess { _skills.value = it }
    }

    private fun jobAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
            // The server owns job state, so re-read rather than guessing what
            // the action did to it.
            runCatching { graph.api.jobs() }.onSuccess { _jobs.value = it }
        }
    }

    fun pauseJob(job: Job) = jobAction { graph.api.pauseJob(job.id) }

    fun resumeJob(job: Job) = jobAction { graph.api.resumeJob(job.id) }

    fun runJob(job: Job) = jobAction { graph.api.runJob(job.id) }

    fun deleteJob(job: Job) = jobAction { graph.api.deleteJob(job.id) }

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

    fun setLayoutMode(mode: LayoutMode) {
        viewModelScope.launch { graph.settings.setLayoutMode(mode) }
    }

    fun setUiScale(scale: Float) {
        viewModelScope.launch { graph.settings.setUiScale(scale) }
    }

    fun setRailWidths(sessionDp: Float, activityDp: Float) {
        viewModelScope.launch { graph.settings.setRailWidths(sessionDp, activityDp) }
    }

    fun setRailPanel(side: RailSide, panel: RailPanel) {
        viewModelScope.launch { graph.settings.setRailPanel(side, panel) }
    }

    fun setShowStatusBar(show: Boolean) {
        viewModelScope.launch { graph.settings.setShowStatusBar(show) }
    }

    fun resetLayout() {
        viewModelScope.launch { graph.settings.resetLayout() }
    }

    /** Exposed for the settings screen so it can show why a save did not take. */
    fun currentError(): UiError? = chat.value.error
}
