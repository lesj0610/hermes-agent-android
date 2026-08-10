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
import io.github.lesj0610.hermes.core.ReasoningEffort
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.UiError
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.Capabilities
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.Job
import io.github.lesj0610.hermes.net.ModelChoice
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.voice.VoiceController
import io.github.lesj0610.hermes.voice.VoiceState
import java.util.Locale

/** How the gateway is currently reachable. Drives the banner in settings and the rail header. */
sealed interface Connection {
    data object NotConfigured : Connection
    data object Checking : Connection
    data class Connected(val version: String?, val latencyMs: Int) : Connection
    data object Unauthorized : Connection
    data class Unreachable(val detail: String?) : Connection
}

/** Which pane the user is looking at. On a tablet several are visible at once. */
enum class Pane { Chat, Cron, Gateway, Dashboard, Settings }

/** State of the optional dashboard connection, which is independent of the gateway's. */
sealed interface DashboardState {
    /** No dashboard configured — the panel stays hidden rather than erroring. */
    data object Off : DashboardState
    data object Connecting : DashboardState
    data object Ready : DashboardState
    data class Failed(val message: String) : DashboardState
}

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

    /**
     * Every model the gateway can route to, flattened from the provider
     * inventory. Empty on a gateway that cannot serve it, which is why the
     * picker falls back to [models].
     */
    private val _modelChoices = MutableStateFlow<List<ModelChoice>>(emptyList())
    val modelChoices: StateFlow<List<ModelChoice>> = _modelChoices.asStateFlow()

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

    private val _dashboard = MutableStateFlow<DashboardState>(DashboardState.Off)
    val dashboard: StateFlow<DashboardState> = _dashboard.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<ActiveProfile?>(null)
    val activeProfile: StateFlow<ActiveProfile?> = _activeProfile.asStateFlow()

    private val _dashboardSkills = MutableStateFlow<List<DashboardSkill>>(emptyList())
    val dashboardSkills: StateFlow<List<DashboardSkill>> = _dashboardSkills.asStateFlow()

    // Opens on the conversation, not on a list of them. The session list is in
    // the drawer, one gesture away, so landing on it first put a screen between
    // the user and the thing they opened the app to do.
    private val _pane = MutableStateFlow(Pane.Chat)
    val pane: StateFlow<Pane> = _pane.asStateFlow()

    // ── voice ─────────────────────────────────────────────────────────────

    /**
     * Speech in and out, both on the device: the gateway has no audio route, so
     * a spoken turn becomes text before it is sent and the reply becomes speech
     * after it arrives.
     */
    private val voice = VoiceController(app)
    val voiceState: StateFlow<VoiceState> = voice.state
    val voiceConversing: StateFlow<Boolean> = voice.conversing
    val voiceAvailable: Boolean get() = voice.available

    /**
     * A dictated utterance waiting to be dropped into the composer. Null once
     * the composer has taken it, so the same text is not inserted twice.
     */
    private val _dictation = MutableStateFlow<String?>(null)
    val dictation: StateFlow<String?> = _dictation.asStateFlow()

    init {
        refresh()
        refreshDashboard()

        voice.onTranscript = { text ->
            // In a spoken conversation the turn goes straight out; dictation
            // fills the box instead, so what was heard can be corrected before
            // it is sent.
            if (voice.conversing.value) send(text) else _dictation.value = text
        }

        // Read each completed reply aloud while a conversation is running. Only
        // on completion: speaking the stream would restart the utterance on
        // every delta.
        viewModelScope.launch {
            var wasBusy = false
            graph.runEngine.state.collect { state ->
                if (wasBusy && !state.isBusy && voice.conversing.value) {
                    val reply = state.items
                        .filterIsInstance<io.github.lesj0610.hermes.data.TranscriptItem.AssistantText>()
                        .lastOrNull()
                        ?.text
                    if (reply != null) voice.speak(reply, voiceLocale())
                }
                wasBusy = state.isBusy
            }
        }
    }

    /** Follows the app's language so Korean is transcribed and spoken as Korean. */
    private fun voiceLocale(): Locale =
        settings.value.language.takeIf { it.isNotBlank() }
            ?.let { Locale.forLanguageTag(it) }
            ?: Locale.getDefault()

    fun dictate() = voice.listen(voiceLocale())

    fun consumeDictation() { _dictation.value = null }

    fun toggleConversation() {
        if (voice.conversing.value) voice.stopConversation() else voice.startConversation(voiceLocale())
    }

    /** The microphone was just granted, so act on the tap that triggered the ask. */
    fun onMicrophoneGranted() = dictate()

    override fun onCleared() {
        voice.release()
        super.onCleared()
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

        // The inventory is enrichment, not a requirement: a gateway that cannot
        // build it leaves the picker on whatever /v1/models reported.
        runCatching { graph.api.modelOptions() }.onSuccess { payload ->
            _modelChoices.value = payload.providers.flatMap { row ->
                val slug = row.slug.orEmpty()
                row.models.map { model ->
                    ModelChoice(
                        provider = slug,
                        providerLabel = row.name ?: slug,
                        model = model,
                        reasoning = row.capabilities[model]?.reasoning ?: false,
                    )
                }
            }
        }
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
            val current = graph.settings.current()
            graph.runEngine.send(
                prompt = prompt,
                model = current.model.takeIf { it.isNotBlank() },
                provider = current.provider.takeIf { it.isNotBlank() },
                effort = current.reasoningEffort.wire,
            )
        }
    }

    fun respondToApproval(choice: String) = graph.runEngine.respondToApproval(choice)

    fun stop() = graph.runEngine.stop()

    fun dismissError() = graph.runEngine.clearError()

    fun saveServer(host: String, port: Int, token: String) {
        viewModelScope.launch {
            graph.settings.setServer(host, port, token)
            refresh()
        }
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        viewModelScope.launch { graph.settings.setReasoningEffort(effort) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { graph.settings.setModel(model) }
    }

    /** Picked from the inventory, so the provider slug travels with the model. */
    fun setModelChoice(choice: ModelChoice) {
        viewModelScope.launch { graph.settings.setModel(choice.model, choice.provider) }
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

    /**
     * Connects to the dashboard, if one is configured.
     *
     * Runs separately from [refresh] on purpose: the dashboard is a different
     * server that may be down while the gateway is fine, and a failure here
     * must not make the conversation look broken.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            val current = graph.settings.current()
            if (!current.dashboardConfigured) {
                _dashboard.value = DashboardState.Off
                return@launch
            }
            _dashboard.value = DashboardState.Connecting
            runCatching {
                graph.dashboard.login()
                _profiles.value = graph.dashboard.profiles()
                _activeProfile.value = graph.dashboard.activeProfile()
                _dashboardSkills.value = graph.dashboard.skills()
            }.onSuccess {
                _dashboard.value = DashboardState.Ready
            }.onFailure { cause ->
                _dashboard.value = DashboardState.Failed(
                    cause.message ?: cause::class.simpleName.orEmpty(),
                )
            }
        }
    }

    fun setActiveProfile(name: String) {
        viewModelScope.launch {
            runCatching {
                graph.dashboard.setActiveProfile(name)
                _activeProfile.value = graph.dashboard.activeProfile()
            }.onFailure { cause ->
                _dashboard.value = DashboardState.Failed(cause.message.orEmpty())
            }
        }
    }

    fun toggleSkill(skill: DashboardSkill) {
        viewModelScope.launch {
            runCatching {
                graph.dashboard.toggleSkill(skill.name, !skill.enabled)
                // Re-read rather than flipping locally: the server owns which
                // skills are disabled, and a profile scope can change the answer.
                _dashboardSkills.value = graph.dashboard.skills()
            }.onFailure { cause ->
                _dashboard.value = DashboardState.Failed(cause.message.orEmpty())
            }
        }
    }

    fun saveDashboard(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            graph.settings.setDashboard(host, port, username, password)
            refreshDashboard()
        }
    }

    fun setLayoutMode(mode: LayoutMode) {
        viewModelScope.launch { graph.settings.setLayoutMode(mode) }
    }

    fun setUiScale(scale: Float) {
        viewModelScope.launch { graph.settings.setUiScale(scale) }
    }

    fun setColumnWidths(drawerDp: Float, railDp: Float) {
        viewModelScope.launch { graph.settings.setColumnWidths(drawerDp, railDp) }
    }

    fun setRailPanel(panel: RailPanel) {
        viewModelScope.launch { graph.settings.setRailPanel(panel) }
    }

    fun setDrawerPinned(pinned: Boolean) {
        viewModelScope.launch { graph.settings.setDrawerPinned(pinned) }
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
