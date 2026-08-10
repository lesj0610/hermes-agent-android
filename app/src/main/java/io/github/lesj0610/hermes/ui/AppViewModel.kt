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
import io.github.lesj0610.hermes.net.FsEntry
import io.github.lesj0610.hermes.net.ProjectsPayload
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.Job
import io.github.lesj0610.hermes.net.ModelChoice
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.ui.artifacts.Artifact
import io.github.lesj0610.hermes.ui.artifacts.collectArtifacts
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
enum class Pane { Chat, Projects, Artifacts, Cron, Gateway, Dashboard, Settings }

/**
 * How many sessions the artifact scan reads.
 *
 * Each one is a separate request for its whole message history, so the whole
 * list would be as many round trips as there are sessions and would grow without
 * bound. The most recent are where the things you are looking for were made; the
 * screen says how many it read rather than implying it read everything.
 */
const val ARTIFACT_SESSION_LIMIT = 20

/**
 * How far the artifact scan got.
 *
 * [available] is every session the app knows about, so the screen can say it
 * read the most recent [total] of them rather than presenting a partial sweep as
 * the whole picture.
 */
data class ArtifactScan(
    val running: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val available: Int = 0,
    val failed: Int = 0,
)

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

    /** True while the list is being re-read, for the pull-to-refresh indicator. */
    private val _sessionsRefreshing = MutableStateFlow(false)
    val sessionsRefreshing: StateFlow<Boolean> = _sessionsRefreshing.asStateFlow()

    /** One-shot message about a session action, shown as a snackbar. */
    private val _sessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    private val _projects = MutableStateFlow(ProjectsPayload())
    val projects: StateFlow<ProjectsPayload> = _projects.asStateFlow()

    private val _projectsBusy = MutableStateFlow(false)
    val projectsBusy: StateFlow<Boolean> = _projectsBusy.asStateFlow()

    private val _projectsError = MutableStateFlow<String?>(null)
    val projectsError: StateFlow<String?> = _projectsError.asStateFlow()

    private val _artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val artifacts: StateFlow<List<Artifact>> = _artifacts.asStateFlow()

    /** Scan state, so the screen can say what it read instead of looking empty. */
    private val _artifactScan = MutableStateFlow(ArtifactScan())
    val artifactScan: StateFlow<ArtifactScan> = _artifactScan.asStateFlow()

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    /**
     * Every model the gateway can route to, flattened from the provider
     * inventory. Empty on a gateway that cannot serve it, which is why the
     * picker falls back to [models].
     */
    private val _modelChoices = MutableStateFlow<List<ModelChoice>>(emptyList())
    val modelChoices: StateFlow<List<ModelChoice>> = _modelChoices.asStateFlow()

    /**
     * The model the gateway is currently on, from the same inventory call.
     *
     * This is what the UI shows when nothing has been overridden here — naming
     * the actual model rather than calling it a default, which said nothing
     * about what the next turn would run on.
     */
    private val _serverModel = MutableStateFlow("")
    val serverModel: StateFlow<String> = _serverModel.asStateFlow()

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
        // Taken before the inventory call so the model has a name as soon as the
        // gateway answers at all. The inventory is allowed to replace it below,
        // but is not allowed to be the only source: it builds provider
        // catalogues and fails on gateways this one probe still answers on.
        caps?.model?.takeIf { it.isNotBlank() }?.let { _serverModel.value = it }

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
            payload.model?.takeIf { it.isNotBlank() }?.let { _serverModel.value = it }
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

    /**
     * Reads the recent sessions' messages and pulls the artifacts out of them.
     *
     * Sequential rather than parallel: this is someone else's machine serving a
     * conversation at the same time, and twenty simultaneous history reads is a
     * burst it has no reason to absorb for a screen the user is browsing.
     *
     * A session that fails to load is counted rather than aborting the scan —
     * one unreadable history should not empty the whole screen.
     */
    fun loadArtifacts() {
        if (_artifactScan.value.running) return
        viewModelScope.launch {
            val targets = _sessions.value.take(ARTIFACT_SESSION_LIMIT)
            _artifactScan.value = ArtifactScan(
                running = true,
                scanned = 0,
                total = targets.size,
                available = _sessions.value.size,
            )
            val collected = mutableListOf<Artifact>()
            var failed = 0
            targets.forEach { session ->
                runCatching { graph.api.messages(session.id) }
                    .onSuccess { collected += collectArtifacts(session, it) }
                    .onFailure { failed++ }
                _artifacts.value = collected.toList()
                _artifactScan.value = _artifactScan.value.copy(
                    scanned = _artifactScan.value.scanned + 1,
                    failed = failed,
                )
            }
            _artifactScan.value = _artifactScan.value.copy(running = false)
        }
    }

    fun send(prompt: String, images: List<String> = emptyList()) {
        viewModelScope.launch {
            val current = graph.settings.current()
            graph.runEngine.send(
                prompt = prompt,
                model = current.model.takeIf { it.isNotBlank() },
                provider = current.provider.takeIf { it.isNotBlank() },
                effort = current.reasoningEffort.wire,
                images = images,
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

    /**
     * Re-reads the session list.
     *
     * [silent] leaves the pull indicator alone. A refresh nobody asked for
     * should not put a spinner over the list they are reading — that is what
     * made the drawer feel slow when opening it triggered one.
     *
     * Overlapping calls are dropped rather than queued, so a pull landing on
     * top of a background read does not fire a second request.
     */
    fun refreshSessions(silent: Boolean = false) {
        if (_sessionsRefreshing.value || _sessionsLoading) return
        viewModelScope.launch {
            _sessionsLoading = true
            if (!silent) _sessionsRefreshing.value = true
            loadSessions()
            _sessionsRefreshing.value = false
            _sessionsLoading = false
        }
    }

    /** Guards against overlap for silent reads too, which never set the flag. */
    private var _sessionsLoading = false

    // ── session actions ───────────────────────────────────────────────────

    /**
     * Applies [block] to a session and re-reads the list.
     *
     * Re-read rather than patched locally: the server owns pin ordering and the
     * archived filter, and guessing the resulting list is how a row reappears
     * on the next refresh after looking gone.
     */
    private fun sessionAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { loadSessions() }
                .onFailure { _sessionNotice.value = it.message ?: it::class.simpleName.orEmpty() }
        }
    }

    fun renameSession(sessionId: String, title: String) =
        sessionAction { graph.api.patchSession(sessionId, title = title) }

    fun setSessionPinned(sessionId: String, pinned: Boolean) =
        sessionAction { graph.api.patchSession(sessionId, pinned = pinned) }

    /**
     * Archives rather than deletes: this is the reversible one, and it is the
     * flag the session list filters on.
     */
    fun archiveSession(sessionId: String) = sessionAction {
        graph.api.patchSession(sessionId, archived = true)
        if (chat.value.sessionId == sessionId) graph.runEngine.openSession(null)
    }

    /**
     * Deletes permanently. The route removes the messages and the transcript
     * files on the agent's host; the UI confirms before calling this.
     */
    fun deleteSession(sessionId: String) = sessionAction {
        graph.api.deleteSession(sessionId)
        if (chat.value.sessionId == sessionId) graph.runEngine.openSession(null)
    }

    /** Forks the session and opens the copy, which is what branching is for. */
    fun branchSession(sessionId: String) = sessionAction {
        val forked = graph.api.forkSession(sessionId)
        graph.runEngine.openSession(forked.id)
        _pane.value = Pane.Chat
    }

    /** The transcript as plain text, for sharing. Null when it cannot be read. */
    suspend fun exportSession(session: SessionSummary): String? =
        runCatching {
            val messages = graph.api.messages(session.id)
            buildString {
                append(session.title?.takeIf { it.isNotBlank() } ?: session.id)
                append("\n\n")
                messages.forEach { message ->
                    val text = message.text
                    if (text.isBlank()) return@forEach
                    append("[")
                    append(message.role?.uppercase() ?: "?")
                    append("] ")
                    message.toolName?.takeIf { it.isNotBlank() }?.let { append(it + "\n") }
                    append(text)
                    append("\n\n")
                }
            }
        }.getOrNull()

    fun clearSessionNotice() { _sessionNotice.value = null }

    // ── projects ──────────────────────────────────────────────────────────

    /**
     * Projects are the dashboard's, not the phone's.
     *
     * They live in the profile's `projects.db` and are reached over the
     * dashboard's JSON-RPC socket — the same store the desktop writes to, which
     * is the point: a project made here is one the desktop opens. The gateway's
     * HTTP surface has no projects route, so without a configured dashboard
     * there is nothing to show, and the screen says so rather than looking empty.
     */
    fun loadProjects() {
        viewModelScope.launch {
            if (!graph.settings.current().dashboardConfigured) {
                _projectsError.value = null
                _projects.value = ProjectsPayload()
                return@launch
            }
            _projectsBusy.value = true
            runCatching { graph.dashboard.projects() }
                .onSuccess {
                    _projects.value = it
                    _projectsError.value = null
                }
                .onFailure { _projectsError.value = it.message ?: it::class.simpleName.orEmpty() }
            _projectsBusy.value = false
        }
    }

    /**
     * Creates a project and, when an idea was written, saves it as IDEA.md in
     * the primary folder — which is what the desktop's New project dialog does
     * with that field, and where the agent will find it.
     */
    fun createProject(name: String, idea: String, folders: List<String>) {
        viewModelScope.launch {
            _projectsBusy.value = true
            runCatching {
                val primary = folders.firstOrNull()
                graph.dashboard.createProject(
                    name = name,
                    description = idea.takeIf { it.isNotBlank() },
                    folders = folders,
                    primaryPath = primary,
                )
                if (idea.isNotBlank() && primary != null) {
                    // Best effort: the project itself is created either way, and
                    // failing the whole action over a file write would leave the
                    // user unsure whether the project exists.
                    runCatching {
                        graph.dashboard.fsWriteText("${primary.trimEnd('/')}/IDEA.md", idea)
                    }
                }
                graph.dashboard.projects()
            }
                .onSuccess {
                    _projects.value = it
                    _projectsError.value = null
                }
                .onFailure { _projectsError.value = it.message ?: it::class.simpleName.orEmpty() }
            _projectsBusy.value = false
        }
    }

    fun setActiveProject(id: String) = projectAction { graph.dashboard.setActiveProject(id) }

    fun renameProject(id: String, name: String) =
        projectAction { graph.dashboard.renameProject(id, name) }

    fun archiveProject(id: String, archived: Boolean) =
        projectAction { graph.dashboard.archiveProject(id, archived) }

    private fun projectAction(block: suspend () -> ProjectsPayload) {
        viewModelScope.launch {
            _projectsBusy.value = true
            runCatching { block() }
                .onSuccess {
                    _projects.value = it
                    _projectsError.value = null
                }
                .onFailure { _projectsError.value = it.message ?: it::class.simpleName.orEmpty() }
            _projectsBusy.value = false
        }
    }

    /** Lists a directory on the gateway host, for the folder picker. */
    suspend fun browseGateway(path: String): List<FsEntry> =
        runCatching { graph.dashboard.fsList(path).entries }.getOrDefault(emptyList())

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
