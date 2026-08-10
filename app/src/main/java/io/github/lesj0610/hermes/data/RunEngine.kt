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
import io.github.lesj0610.hermes.net.HermesApi
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.RunEvent
import io.github.lesj0610.hermes.net.StoredMessage
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
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Side-channel for the notification layer. Replay 0 — missed signals are stale by definition. */
    private val _signals = MutableSharedFlow<RunSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<RunSignal> = _signals.asSharedFlow()

    private val keySeq = AtomicLong(0)
    private var streamJob: Job? = null

    private fun nextKey(prefix: String): String = "$prefix-${keySeq.incrementAndGet()}"

    // ── session switching ─────────────────────────────────────────────────

    /** Drops any in-flight stream and loads [sessionId]'s stored history. */
    suspend fun openSession(sessionId: String?) {
        streamJob?.cancelAndJoin()
        streamJob = null
        _state.value = ChatState(sessionId = sessionId)
        if (sessionId == null) return

        runCatching { api.messages(sessionId) }
            .onSuccess { stored -> _state.update { it.copy(items = stored.toTranscript()) } }
            .onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }
    }

    private fun List<StoredMessage>.toTranscript(): List<TranscriptItem> = mapNotNull { message ->
        val body = message.text
        when (message.role) {
            "user" -> if (body.isBlank()) null else TranscriptItem.UserText(nextKey("u"), body)
            "assistant" -> if (body.isBlank()) null else
                TranscriptItem.AssistantText(nextKey("a"), body, streaming = false)
            "tool" -> TranscriptItem.ToolCall(
                key = nextKey("t"),
                tool = message.toolName ?: "tool",
                preview = body.takeIf { it.isNotBlank() },
                state = ToolState.Completed,
            )
            else -> null
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
                items = it.items + TranscriptItem.UserText(nextKey("u"), prompt),
                error = null,
            )
        }

        streamJob = scope.launch {
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

            is RunEvent.ReasoningAvailable -> if (event.text.isNotBlank()) {
                _state.update {
                    it.copy(items = it.items + TranscriptItem.Reasoning(nextKey("r"), event.text))
                }
            }

            is RunEvent.ToolStarted -> _state.update {
                it.copy(
                    items = it.items.finishStreaming() + TranscriptItem.ToolCall(
                        key = nextKey("t"),
                        tool = event.tool,
                        preview = event.preview,
                        state = ToolState.Running,
                    ),
                )
            }

            is RunEvent.ToolCompleted -> _state.update { current ->
                current.copy(items = current.items.updateLastTool(event.tool) { card ->
                    card.copy(
                        preview = event.preview ?: card.preview,
                        state = if (event.error.isNullOrBlank()) ToolState.Completed else ToolState.Failed,
                        durationSeconds = event.duration,
                        error = event.error,
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
                _state.update {
                    it.copy(
                        items = it.items.finishStreaming(),
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                        lastUsage = event.usage ?: it.lastUsage,
                    )
                }
                _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = true))
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
                current.items + TranscriptItem.AssistantText(nextKey("a"), delta, streaming = true)
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
            runCatching { api.respondToApproval(phase.runId, choice) }
                .onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }
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

private fun List<TranscriptItem>.finishStreaming(): List<TranscriptItem> {
    val last = lastOrNull()
    return if (last is TranscriptItem.AssistantText && last.streaming) {
        dropLast(1) + last.copy(streaming = false)
    } else {
        this
    }
}

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
