package io.github.lesj0610.hermes.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.lesj0610.hermes.net.DashboardApi
import io.github.lesj0610.hermes.net.HermesApi
import io.github.lesj0610.hermes.net.SocketRun
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.RunEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns the gateway's event stream into a transcript.
 *
 * Lives at application scope rather than in a ViewModel: a run must keep
 * accumulating while the app is backgrounded, and an approval that arrives then
 * has to reach the notification layer. The foreground service keeps the process
 * alive; this class owns the state.
 */
class RunEngine(
    private val api: HermesApi,
    private val scope: CoroutineScope,
    /**
     * The socket transport, used when a dashboard is configured.
     *
     * Preferred wherever one is reachable, and not only for reasoning: the
     * HTTP route emits no tool events at all, and delivers thinking as a
     * single block after the answer rather than as a stream. A turn falls back
     * to HTTP rather than refusing to talk.
     */
    private val dashboard: DashboardApi? = null,
    private val socketEnabled: suspend () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Side-channel for the notification layer. Replay 0 — missed signals are stale by definition. */
    private val _signals = MutableSharedFlow<RunSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<RunSignal> = _signals.asSharedFlow()

    private val keySeq = AtomicLong(0)
    private var streamJob: Job? = null

    /** Set while a turn is running over the socket, so approvals go back the same way. */
    private var socketRun: SocketRun? = null

    private fun nextKey(prefix: String): String = "$prefix-${keySeq.incrementAndGet()}"

    // ── session switching ─────────────────────────────────────────────────

    /** Drops any in-flight stream and loads [sessionId]'s stored history. */
    suspend fun openSession(sessionId: String?) {
        streamJob?.cancelAndJoin()
        streamJob = null
        _state.value = ChatState(sessionId = sessionId)
        if (sessionId == null) return

        runCatching { api.messages(sessionId) }
            .onSuccess { stored -> _state.update { it.copy(items = storedToTranscript(stored, ::nextKey)) } }
            .onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }

        // Pictures come back in a second pass, so the conversation is readable
        // immediately and the images fill in behind it. A session can hold
        // dozens, and each is a separate request.
        restoreAttachedImages()
    }

    /**
     * Fetch the pictures a reopened conversation refers to.
     *
     * The gateway keeps an attachment on its own disk and persists an
     * `@image:` path in the message, so a reopened session has paths and no
     * pixels. Without the dashboard there is no route to the file and the turn
     * stays text — which is what it did for every session before this.
     */
    private suspend fun restoreAttachedImages() {
        val dashboard = dashboard ?: return
        val pending = _state.value.items
            .mapNotNull { item ->
                when {
                    item is TranscriptItem.UserText && item.imagePaths.isNotEmpty() ->
                        item.key to item.imagePaths
                    item is TranscriptItem.AssistantText && item.imagePaths.isNotEmpty() ->
                        item.key to item.imagePaths
                    else -> null
                }
            }
            // The most recent, because those are the ones being looked at.
            .takeLast(IMAGE_RESTORE_LIMIT)
        if (pending.isEmpty()) return

        pending.forEach { (key, paths) ->
            val loaded = paths.mapNotNull { dashboard.readDataUrl(it) }
            if (loaded.isEmpty()) return@forEach
            _state.update { current ->
                current.copy(
                    items = current.items.map { existing ->
                        when {
                            existing.key != key -> existing
                            existing is TranscriptItem.UserText -> existing.copy(images = loaded)
                            existing is TranscriptItem.AssistantText -> existing.copy(images = loaded)
                            else -> existing
                        }
                    },
                )
            }
        }
    }

    // ── sending ───────────────────────────────────────────────────────────

    fun send(
        prompt: String,
        model: String?,
        provider: String?,
        effort: String?,
        images: List<String> = emptyList(),
    ) {
        if ((prompt.isBlank() && images.isEmpty()) || _state.value.isBusy) return

        _state.update {
            it.copy(
                items = it.items + TranscriptItem.UserText(nextKey("u"), prompt, images),
                error = null,
            )
        }

        streamJob = scope.launch {
            // The socket carries pictures too, through `image.attach_bytes`.
            // It has to: the HTTP route accepts an image and answers, but emits
            // no reasoning stream and no tool events at all, so an attached
            // photo turned the transcript into a bare answer.
            val viaSocket = dashboard != null &&
                runCatching { socketEnabled() }.getOrDefault(false)
            if (viaSocket) {
                runSocket(prompt, images)
                return@launch
            }

            val started = runCatching { api.startRun(prompt, _state.value.sessionId, model, provider, effort, images) }
                .getOrElse { cause ->
                    _state.update { it.copy(phase = RunPhase.Idle, error = cause.toUiError()) }
                    return@launch
                }

            _state.update {
                it.copy(
                    phase = RunPhase.Running(started.runId),
                    runStartedAtMillis = System.currentTimeMillis(),
                )
            }
            _signals.tryEmit(RunSignal.Started(started.runId))
            consume(started.runId)
        }
    }

    /**
     * Drives a turn over the event socket.
     *
     * The live session is closed in a finally: it belongs to the gateway, and
     * one left open per turn accumulates there.
     */
    private suspend fun runSocket(prompt: String, images: List<String>) {
        val api = dashboard ?: return
        val run = runCatching { api.startSocketRun(_state.value.sessionId, prompt, images) }
            .getOrElse { cause ->
                _state.update { it.copy(phase = RunPhase.Idle, error = cause.toUiError()) }
                return
            }
        socketRun = run
        _state.update {
            it.copy(
                phase = RunPhase.Running(run.liveSessionId),
                runStartedAtMillis = System.currentTimeMillis(),
            )
        }
        _signals.tryEmit(RunSignal.Started(run.liveSessionId))
        try {
            runCatching { run.events().collect(::apply) }
                .onFailure { cause ->
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    _state.update {
                        it.copy(
                            phase = RunPhase.Idle,
                            items = it.items.finishStreaming() +
                                TranscriptItem.Failure(nextKey("e"), cause.toUiError()),
                        )
                    }
                    _signals.tryEmit(RunSignal.Finished(run.liveSessionId, ok = false))
                }
        } finally {
            socketRun = null
            runCatching { run.close() }
        }
    }

    private suspend fun consume(runId: String) {
        runCatching {
            api.runEvents(runId).collect(::apply)
        }.onFailure { cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            _state.update {
                it.copy(
                    phase = RunPhase.Idle,
                    items = it.items.finishStreaming() +
                        TranscriptItem.Failure(nextKey("e"), cause.toUiError()),
                )
            }
            _signals.tryEmit(RunSignal.Finished(runId, ok = false))
        }
    }

    // ── event application ─────────────────────────────────────────────────

    private fun apply(event: RunEvent) {
        when (event) {
            is RunEvent.MessageDelta -> appendDelta(event.delta)

            // Real reasoning, streamed. Appended into one block the way prose
            // is, so a long thought does not become a hundred cards.
            // Timed from its first token to whatever the turn does next, which
            // is what closes it (finishStreaming) — the desktop's per-block
            // measure. Only an open block takes more tokens; a closed one means
            // this is a new thought.
            is RunEvent.ReasoningDelta -> _state.update { current ->
                val last = current.items.lastOrNull()
                val items = if (last is TranscriptItem.Reasoning && last.pending) {
                    current.items.dropLast(1) + last.copy(text = last.text + event.text)
                } else {
                    current.items.finishStreaming() + TranscriptItem.Reasoning(
                        key = nextKey("r"),
                        text = event.text,
                        startedAtMillis = System.currentTimeMillis(),
                    )
                }
                current.copy(items = items)
            }

            // `reasoning.available` is the whole thought, delivered at the end.
            //
            // This was dropped on the grounds that it echoed the answer. That
            // held on the socket, where `reasoning.delta` streams the real
            // thing and this arrives afterwards as a copy — and `SocketRun`
            // filters it there, so nothing on that route reaches this branch.
            //
            // On HTTP it is the only reasoning there is. Measured against the
            // live gateway, a run emits `message.delta`, exactly one
            // `reasoning.available` carrying genuine narration, and
            // `run.completed`. Dropping it left the whole route with no
            // reasoning at all.
            //
            // It lands after the answer, so it is inserted *above* the reply
            // rather than appended: thinking that reads below its own
            // conclusion is the bug this once shipped as.
            is RunEvent.ReasoningAvailable -> _state.update { current ->
                val text = event.text.trim()
                if (text.isEmpty()) {
                    current
                } else {
                    current.copy(
                        items = current.items.withReasoning(
                            TranscriptItem.Reasoning(nextKey("r"), text),
                        ),
                    )
                }
            }

            is RunEvent.ToolStarted -> _state.update {
                val aim = toolAim(event.tool, event.args, fallback = event.preview)
                it.copy(
                    items = it.items.finishStreaming() + TranscriptItem.ToolCall(
                        key = nextKey("t"),
                        tool = event.tool,
                        preview = event.preview,
                        state = ToolState.Running,
                        target = aim.target,
                        readsResource = aim.readsResource,
                    ),
                )
            }

            is RunEvent.ToolCompleted -> _state.update { current ->
                current.copy(items = current.items.updateLastTool(event.tool) { card ->
                    card.copy(
                        preview = event.preview ?: card.preview,
                        state = if (event.failed) ToolState.Failed else ToolState.Completed,
                        durationSeconds = event.duration,
                        // Only a real sentence, never the flag: the card shows
                        // this instead of the preview, and "false" is not a
                        // tool result.
                        error = event.errorMessage,
                    )
                })
            }

            is RunEvent.ApprovalRequest -> {
                val approval = PendingApproval(
                    runId = event.runId.orEmpty(),
                    command = event.command,
                    choices = event.choices,
                    smartDenied = event.smartDenied,
                )
                _state.update { current ->
                    current.copy(
                        items = current.items.finishStreaming().markLastToolAwaiting(),
                        phase = RunPhase.AwaitingApproval(event.runId.orEmpty(), approval),
                    )
                }
                _signals.tryEmit(RunSignal.ApprovalNeeded(approval))
            }

            is RunEvent.ApprovalResponded -> {
                _state.update { current ->
                    val runId = (current.phase as? RunPhase.AwaitingApproval)?.runId
                        ?: event.runId.orEmpty()
                    current.copy(phase = RunPhase.Running(runId))
                }
                _signals.tryEmit(RunSignal.ApprovalCleared)
            }

            is RunEvent.Completed -> {
                _state.update { current ->
                    current.copy(
                        // Only now: a `MEDIA:` line arrives a character at a
                        // time, and a path half-written is not a path.
                        items = current.items.finishStreaming().map { item ->
                            if (item is TranscriptItem.AssistantText && item.imagePaths.isEmpty()) {
                                parseAttachmentRefs(item.text, ASSISTANT_MEDIA_DIRECTIVE)
                                    .let { turn ->
                                        if (turn.imagePaths.isEmpty()) {
                                            item
                                        } else {
                                            item.copy(
                                                text = turn.text,
                                                imagePaths = turn.imagePaths,
                                            )
                                        }
                                    }
                            } else {
                                item
                            }
                        },
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                        lastUsage = event.usage ?: current.lastUsage,
                    )
                }
                _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = true))
                // Fetch whatever that turn produced, the same pass a reopened
                // session uses.
                scope.launch { restoreAttachedImages() }
            }

            is RunEvent.Failed -> {
                _state.update {
                    it.copy(
                        items = it.items.finishStreaming() + TranscriptItem.Failure(
                            nextKey("e"),
                            event.error?.takeIf { it.isNotBlank() }?.let(UiError::Raw)
                                ?: UiError.RunFailed,
                        ),
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                    )
                }
                _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = false))
            }

            is RunEvent.Cancelled -> {
                _state.update {
                    it.copy(
                        items = it.items.finishStreaming(),
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                    )
                }
                _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = true))
            }

            // A tenth event name from a newer server is ignored, not fatal.
            is RunEvent.Unknown -> Unit
        }
    }

    private fun appendDelta(delta: String) {
        if (delta.isEmpty()) return
        _state.update { current ->
            val last = current.items.lastOrNull()
            val items = if (last is TranscriptItem.AssistantText && last.streaming) {
                current.items.dropLast(1) + last.copy(text = last.text + delta)
            } else {
                // Closing the tail first: the reply starting is what ends the
                // reasoning block before it, and so what times it.
                current.items.finishStreaming() +
                    TranscriptItem.AssistantText(nextKey("a"), delta, streaming = true)
            }
            current.copy(items = items)
        }
    }

    // ── user actions ──────────────────────────────────────────────────────

    fun respondToApproval(choice: String) {
        val phase = _state.value.phase as? RunPhase.AwaitingApproval ?: return
        // Optimistic: the server confirms with approval.responded, but the sheet
        // must close on tap or it feels broken over a slow tunnel.
        _state.update { it.copy(phase = RunPhase.Running(phase.runId)) }
        _signals.tryEmit(RunSignal.ApprovalCleared)
        scope.launch {
            // Back down the transport that asked. An approval answered on the
            // other one targets a session that never requested it.
            val run = socketRun
            runCatching {
                if (run != null) run.respondToApproval(choice)
                else api.respondToApproval(phase.runId, choice)
            }.onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }
        }
    }

    fun stop() {
        val runId = when (val phase = _state.value.phase) {
            is RunPhase.Running -> phase.runId
            is RunPhase.AwaitingApproval -> phase.runId
            else -> return
        }
        _state.update { it.copy(phase = RunPhase.Stopping(runId)) }
        scope.launch {
            runCatching { api.stopRun(runId) }
                .onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/** What the notification layer reacts to. */
sealed interface RunSignal {
    data class Started(val runId: String) : RunSignal
    data class ApprovalNeeded(val approval: PendingApproval) : RunSignal
    data object ApprovalCleared : RunSignal
    data class Finished(val runId: String, val ok: Boolean) : RunSignal
}

// ── transcript helpers ────────────────────────────────────────────────────

private fun List<TranscriptItem>.updateLastTool(
    tool: String,
    transform: (TranscriptItem.ToolCall) -> TranscriptItem.ToolCall,
): List<TranscriptItem> {
    val index = indexOfLast { it is TranscriptItem.ToolCall && it.tool == tool && it.state != ToolState.Completed }
    if (index < 0) return this
    return toMutableList().also { it[index] = transform(it[index] as TranscriptItem.ToolCall) }
}

private fun List<TranscriptItem>.markLastToolAwaiting(): List<TranscriptItem> {
    val index = indexOfLast { it is TranscriptItem.ToolCall && it.state == ToolState.Running }
    if (index < 0) return this
    return toMutableList().also {
        it[index] = (it[index] as TranscriptItem.ToolCall).copy(state = ToolState.AwaitingApproval)
    }
}

internal fun Throwable.toUiError(): UiError = when (this) {
    is HermesUnauthorizedException -> UiError.Unauthorized
    else -> message?.takeIf { it.isNotBlank() }?.let(UiError::Raw)
        ?: UiError.Raw(this::class.simpleName.orEmpty())
}

/**
 * How many past turns get their pictures fetched when a session opens.
 *
 * Each one is a separate request for a file that can be megabytes, and a long
 * session holds dozens. The recent ones are the ones being looked at.
 */
private const val IMAGE_RESTORE_LIMIT = 12
