package io.github.lesj0610.hermes.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.lesj0610.hermes.net.Cleanup
import io.github.lesj0610.hermes.net.ConversationHeldException
import io.github.lesj0610.hermes.net.DashboardApi
import io.github.lesj0610.hermes.net.GatewayTarget
import io.github.lesj0610.hermes.net.HermesApi
import io.github.lesj0610.hermes.net.SocketRun
import io.github.lesj0610.hermes.net.HermesUnauthorizedException
import io.github.lesj0610.hermes.net.RunEvent
import io.github.lesj0610.hermes.net.TurnRuntime
import io.github.lesj0610.hermes.net.VoiceTurn
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
    /** Keeps held conversations across restarts of the app; see [holds]. */
    private val holdStore: HoldStore = HoldStore.InMemory(),
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Side-channel for the notification layer. Replay 0 — missed signals are stale by definition. */
    private val _signals = MutableSharedFlow<RunSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<RunSignal> = _signals.asSharedFlow()

    private val keySeq = AtomicLong(0)
    private val turnSeq = AtomicLong(0)
    private var streamJob: Job? = null

    /** Set while a turn is running over the socket, so approvals go back the same way. */
    private var socketRun: SocketRun? = null

    /**
     * Conversations held, by gateway and stored session id; see [HeldConversation].
     *
     * A turn whose cleanup leaves a change unanswered cannot say whether the
     * gateway will still apply it, and the next turn is handed the same live
     * session. Resending a setting proves nothing about a request still
     * queued, and neither does any fixed wait, so a held conversation takes no
     * turn until there is proof: the answer, read on the socket kept open for
     * it ([Cleanup.settle]), or the live session gone from the gateway's list —
     * after which that id resolves to nothing there, and the conversation is
     * handed a new live session. Other conversations are not held.
     *
     * The proof has to come from the gateway the hold was set on. Another
     * gateway never saw that live session, so its list proves nothing, and a
     * hold on one is not looked for while another is configured.
     */
    private val holds = MutableStateFlow(
        holdStore.load().associate { stored ->
            HoldKey(stored.gateway, stored.sessionId) to HeldConversation(
                stored.liveId,
                check = if (stored.gateway == null) HoldCheck.UnknownServer else HoldCheck.NotYet,
            )
        },
    )
    private val holdLock = Any()
    private var checker: Job? = null

    /** Released because its live session ended: the next resume there must not hand that session back. */
    private val endedLive = java.util.concurrent.ConcurrentHashMap<HoldKey, String>()

    /**
     * Counts conversations opened. A turn hands the conversation it created
     * its stored id only while that conversation is still the one open.
     */
    private var openConversation = 0L

    /** The gateway the open conversation is on, as far as the app knows. */
    private var openGateway: String? = null

    /**
     * Held by a socket turn from its first request to the end of its cleanup.
     *
     * The gateway hands every turn of a conversation the SAME live session, by
     * the same id, for as long as it is alive — so a turn that has ended but is
     * still restoring its level would otherwise be restoring it over the next
     * turn's settings. The next turn cannot reach the gateway until the lock is
     * released, which is after cleanup; a cancelled waiter just stops waiting,
     * and the holder still releases only once its cleanup is done.
     */
    private val gatewayTurn = Mutex()

    init {
        // Held when the app last ran: whatever socket was waiting for an answer
        // went with the process, so only the live session's end releases these.
        if (holds.value.isNotEmpty()) scheduleChecks()
    }

    private fun nextKey(prefix: String): String = "$prefix-${keySeq.incrementAndGet()}"

    // ── session switching ─────────────────────────────────────────────────

    /** Drops any in-flight stream and loads [sessionId]'s stored history. */
    suspend fun openSession(sessionId: String?) {
        streamJob?.cancelAndJoin()
        streamJob = null
        // Every turn so far counts as ended: whatever was waiting on one from the
        // previous session must not wait on into this one.
        val last = turnSeq.get()
        val gateway = currentGateway()
        val held = synchronized(holdLock) {
            openConversation++
            openGateway = gateway
            sessionId?.let(::holdFor).also { held ->
                _state.value = ChatState(sessionId = sessionId, startedTurn = last, endedTurn = last, held = held)
            }
        }
        if (sessionId == null) return
        if (held != null) recheckHold()

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

    /**
     * Sends a turn and answers its number, or null when it was not sent.
     *
     * [voice] marks a turn spoken in a conversation: it is shaped for speech and
     * runs with whatever [effort] the caller chose for speech, and the socket
     * restores the session's level afterwards.
     *
     * Everything the turn does afterwards carries its number. Its events apply
     * only while it is the current turn, and it ends exactly once — by its own
     * terminal event or, when its stream stops without one, as a failure — so
     * nothing left over from it can end or extend a later turn.
     */
    fun send(
        prompt: String,
        model: String?,
        provider: String?,
        effort: String?,
        images: List<String> = emptyList(),
        voice: VoiceTurn? = null,
    ): Long? {
        if ((prompt.isBlank() && images.isEmpty()) || _state.value.isBusy) return null
        val sessionId = _state.value.sessionId
        // Held on any gateway: not knowing which the next turn reaches, refuse.
        if (sessionId != null && holds.value.keys.any { it.sessionId == sessionId }) {
            recheckHold()
            return null
        }
        val conversation = synchronized(holdLock) { openConversation }

        val turn = turnSeq.incrementAndGet()
        _state.update {
            it.copy(
                items = it.items + TranscriptItem.UserText(nextKey("u"), prompt, images),
                error = null,
                warning = null,
                startedTurn = turn,
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
                runSocket(turn, conversation, sessionId, prompt, images, TurnRuntime(model, provider, effort), voice)
                return@launch
            }

            val started = try {
                api.startRun(prompt, _state.value.sessionId, model, provider, effort, images)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                endTurn(turn, cause.toUiError(), asItem = false)
                return@launch
            }
            running(turn, started.runId)
            consume(turn, started.runId)
        }
        return turn
    }

    /** [turn] is under way, unless it already ended or was superseded. */
    private fun running(turn: Long, runId: String) {
        var applied = false
        _state.update { state ->
            if (!state.isOpen(turn)) return@update state
            applied = true
            state.copy(phase = RunPhase.Running(runId), runStartedAtMillis = System.currentTimeMillis())
        }
        if (applied) _signals.tryEmit(RunSignal.Started(runId))
    }

    /**
     * Drives a turn over the event socket.
     *
     * However the turn goes, [SocketRun.close] runs last: it restores what the
     * turn changed and gives the socket back. The live session stays with the
     * gateway, which other clients may share.
     */
    private suspend fun runSocket(
        turn: Long,
        conversation: Long,
        sessionId: String?,
        prompt: String,
        images: List<String>,
        runtime: TurnRuntime,
        voice: VoiceTurn?,
    ) = gatewayTurn.withLock { runSocketLocked(turn, conversation, sessionId, prompt, images, runtime, voice) }

    private suspend fun runSocketLocked(
        turn: Long,
        conversation: Long,
        sessionId: String?,
        prompt: String,
        images: List<String>,
        runtime: TurnRuntime,
        voice: VoiceTurn?,
    ) {
        val api = dashboard ?: return
        // Fixed here: the hold is looked at, and the whole turn runs, against
        // this gateway, whatever the settings become meanwhile.
        val target = try {
            api.currentTarget()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            endTurn(turn, cause.toUiError(), asItem = false)
            return
        }
        // Under the lock: a hold left by the cleanup this turn waited for is seen here.
        if (sessionId != null && !released(sessionId, target)) {
            endTurn(turn, UiError.ConversationHeld, asItem = false)
            return
        }
        val endedKey = sessionId?.let { HoldKey(target.identity, it) }
        val endedLiveId = endedKey?.let { endedLive[it] }
        val run = try {
            api.startSocketRun(
                sessionId, prompt, images, runtime, voice,
                onCleanup = { noteCleanup(sessionId, it) },
                heldLiveId = endedLiveId,
                target = target,
                onCreated = { adopt(conversation, target.identity, it) },
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (held: ConversationHeldException) {
            // Released as gone, yet handed back: held again until it really is.
            if (endedKey != null && endedLiveId != null) {
                setHold(endedKey, HeldConversation(endedLiveId, check = HoldCheck.StillOpen))
                scheduleChecks()
            }
            endTurn(turn, UiError.ConversationHeld, asItem = false)
            return
        } catch (cause: Exception) {
            endTurn(turn, cause.toUiError(), asItem = false)
            return
        }
        // Handed a new live session: the ended one is behind this conversation.
        if (endedKey != null && endedLiveId != null) endedLive.remove(endedKey, endedLiveId)
        socketRun = run
        running(turn, run.liveSessionId)
        try {
            run.events().collect { apply(it, turn) }
            // The stream stopped. A turn still open here stopped without its
            // own ending: the socket closed under it.
            endTurn(turn, UiError.Disconnected)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            endTurn(turn, cause.toUiError())
        } finally {
            // Only this turn's own run is cleared: a later turn can start while
            // this one is still closing, and its run has to stay reachable for
            // Stop and for approvals.
            if (socketRun === run) socketRun = null
            noteCleanup(sessionId, run.close())
        }
    }

    private suspend fun consume(turn: Long, runId: String) {
        try {
            api.runEvents(runId).collect { apply(it, turn) }
            endTurn(turn, UiError.Disconnected)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            endTurn(turn, cause.toUiError())
        }
    }

    /**
     * Ends [turn] with [error] unless it has already ended: whichever path gets
     * here first ends it, and every later one is a no-op. Only the current turn
     * touches the transcript and the phase; an older one is just marked ended.
     *
     * [asItem] puts the failure in the transcript, where a turn that broke
     * mid-reply shows it; otherwise it goes to the banner, for a turn that
     * never started.
     */
    private fun endTurn(turn: Long, error: UiError?, asItem: Boolean = true) {
        var ended = false
        _state.update { state ->
            if (state.endedTurn >= turn) return@update state
            ended = true
            if (state.startedTurn != turn) return@update state.copy(endedTurn = turn)
            state.copy(
                phase = RunPhase.Idle,
                runStartedAtMillis = null,
                items = if (asItem && error != null) {
                    state.items.finishStreaming() + TranscriptItem.Failure(nextKey("e"), error)
                } else {
                    state.items.finishStreaming()
                },
                error = if (asItem || error == null) state.error else error,
                endedTurn = turn,
            )
        }
        if (ended) _signals.tryEmit(RunSignal.Finished("", ok = error == null))
    }

    /**
     * What a turn's cleanup left: a change still unanswered holds its
     * conversation; a restore known not to have happened is a warning.
     * Recorded apart from any error, which stays the one that ended the turn.
     */
    private fun noteCleanup(sessionId: String?, cleanup: Cleanup) {
        val stored = cleanup.storedId ?: sessionId
        val liveId = cleanup.liveId
        if (cleanup.report.unsettled && stored != null && liveId != null) {
            hold(HoldKey(cleanup.gateway, stored), liveId, cleanup)
            return
        }
        if (cleanup.report.restoreFailed) _state.update { it.copy(warning = UiError.ReasoningNotRestored) }
    }

    /**
     * The conversation this turn created now has a stored id. It is handed
     * over only while that conversation is still the one open and has no id
     * yet — a creation answered after the user moved on touches nothing — so
     * the next turn resumes it, and a hold on it shows here.
     */
    internal fun adopt(conversation: Long, gateway: String, storedId: String) {
        synchronized(holdLock) {
            if (conversation != openConversation || _state.value.sessionId != null) return
            openGateway = gateway
            _state.update { it.copy(sessionId = storedId, held = holdFor(storedId)) }
        }
    }

    // ── held conversations ────────────────────────────────────────────────

    private fun hold(key: HoldKey, liveId: String, cleanup: Cleanup) {
        setHold(key, HeldConversation(liveId, awaitingAnswer = cleanup.awaitingAnswer))
        if (!cleanup.awaitingAnswer) {
            scheduleChecks()
            return
        }
        scope.launch {
            if (cleanup.settle(SETTLE_GIVE_UP_MILLIS)) {
                // Answered on the socket it went out on: the gateway has dealt
                // with it, and nothing of that turn is left to land.
                updateHold(key, liveId) { null }
            } else {
                // No answer can come now — the socket ended, or was given up on
                // and closed so the gateway can let the session go. Only that
                // session's end releases the hold.
                updateHold(key, liveId) { it.copy(awaitingAnswer = false) }
                scheduleChecks()
            }
        }
    }

    /** The hold [sessionId] shows: on the open gateway, else one with no gateway recorded, else on another. */
    private fun holdFor(sessionId: String): HeldConversation? {
        val mine = holds.value.filterKeys { it.sessionId == sessionId }
        return mine[HoldKey(openGateway, sessionId)] ?: mine[HoldKey(null, sessionId)] ?: mine.values.firstOrNull()
    }

    /** Applies [change] to the holds, records them, and shows the open conversation's. */
    private fun changeHolds(change: (Map<HoldKey, HeldConversation>) -> Map<HoldKey, HeldConversation>) {
        synchronized(holdLock) {
            holds.update(change)
            holdStore.save(holds.value.map { (key, hold) -> StoredHold(key.gateway, key.sessionId, hold.liveId) })
            _state.update { state -> state.sessionId?.let { state.copy(held = holdFor(it)) } ?: state }
        }
    }

    private fun setHold(key: HoldKey, hold: HeldConversation?) =
        changeHolds { if (hold == null) it - key else it + (key to hold) }

    /** Changes [key]'s hold only while it is still the one on [liveId]. */
    private fun updateHold(key: HoldKey, liveId: String, change: (HeldConversation) -> HeldConversation?) {
        synchronized(holdLock) {
            val current = holds.value[key]?.takeIf { it.liveId == liveId } ?: return
            setHold(key, change(current))
        }
    }

    /**
     * Looks for [key]'s live session on the gateway at [target] — fixed when
     * the look began, so a change of settings meanwhile cannot redirect it —
     * and releases the hold when that gateway no longer lists it. True when
     * released.
     *
     * A hold on another gateway is not looked for at all. One recorded with no
     * gateway is not released by a list without it, since it may be another
     * gateway's; found in the list, it is that gateway's from then on.
     */
    private suspend fun checkHold(key: HoldKey, target: GatewayTarget): Boolean {
        val dashboard = dashboard ?: return false
        val liveId = holds.value[key]?.liveId ?: return true
        val gateway = target.identity
        if (key.gateway != null && key.gateway != gateway) {
            updateHold(key, liveId) { it.copy(check = HoldCheck.OtherServer) }
            return false
        }
        if (key.gateway == null) {
            when (dashboard.liveSessionOpen(liveId, target, sessionKey = key.sessionId)) {
                true -> changeHolds { all ->
                    val current = all[key]?.takeIf { it.liveId == liveId } ?: return@changeHolds all
                    all - key + (HoldKey(gateway, key.sessionId) to current.copy(check = HoldCheck.StillOpen))
                }
                false -> updateHold(key, liveId) { it.copy(check = HoldCheck.UnknownServer) }
                null -> updateHold(key, liveId) { it.copy(check = HoldCheck.Unreachable) }
            }
            return false
        }
        val open = dashboard.liveSessionOpen(liveId, target)
        if (open == false) {
            synchronized(holdLock) {
                if (holds.value[key]?.liveId != liveId) return false
                endedLive[key] = liveId
                setHold(key, null)
            }
            return true
        }
        updateHold(key, liveId) { it.copy(check = if (open == true) HoldCheck.StillOpen else HoldCheck.Unreachable) }
        return false
    }

    /** Looks at every hold on [sessionId] through [target]; true when none is left. */
    private suspend fun released(sessionId: String, target: GatewayTarget): Boolean {
        holds.value.keys.filter { it.sessionId == sessionId }.forEach { checkHold(it, target) }
        return holds.value.keys.none { it.sessionId == sessionId }
    }

    /** The configured gateway's identity, or null when it cannot be read. */
    private suspend fun currentGateway(): String? = currentTargetOrNull()?.identity

    private suspend fun currentTargetOrNull(): GatewayTarget? = try {
        dashboard?.currentTarget()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Looks again at every hold no socket is waiting on, now and then less
     * often: the gateway lets a live session go some time after its last
     * client does, and not at all while one keeps it. The delays say when to
     * look, never that it is safe.
     */
    private fun scheduleChecks() {
        if (dashboard == null) return
        synchronized(holdLock) {
            checker?.cancel()
            checker = scope.launch {
                var wait = HOLD_FIRST_CHECK_MILLIS
                while (true) {
                    delay(wait)
                    val due = synchronized(holdLock) {
                        if (holds.value.isEmpty()) return@launch
                        holds.value.filterValues { !it.awaitingAnswer }.keys
                    }
                    currentTargetOrNull()?.let { target -> due.forEach { checkHold(it, target) } }
                    wait = (wait * 2).coerceAtMost(HOLD_MAX_CHECK_MILLIS)
                }
            }
        }
    }

    /** Looks now whether the open conversation's holds can be released, through the gateway configured now. */
    fun recheckHold() {
        val sessionId = _state.value.sessionId ?: return
        val keys = holds.value.keys.filter { it.sessionId == sessionId }
        if (keys.isEmpty()) return
        scope.launch {
            val target = currentTargetOrNull() ?: return@launch
            keys.forEach { checkHold(it, target) }
        }
    }

    // ── event application ─────────────────────────────────────────────────

    /** Applies [block] only while [turn] is the current, unfinished turn. */
    private inline fun updateTurn(turn: Long, crossinline block: (ChatState) -> ChatState): Boolean {
        var applied = false
        _state.update { state ->
            if (!state.isOpen(turn)) {
                state
            } else {
                applied = true
                block(state)
            }
        }
        return applied
    }

    internal fun apply(event: RunEvent, turn: Long) {
        when (event) {
            is RunEvent.MessageDelta -> appendDelta(event.delta, turn)

            // Real reasoning, streamed. Appended into one block the way prose
            // is, so a long thought does not become a hundred cards.
            // Timed from its first token to whatever the turn does next, which
            // is what closes it (finishStreaming) — the desktop's per-block
            // measure. Only an open block takes more tokens; a closed one means
            // this is a new thought.
            is RunEvent.ReasoningDelta -> updateTurn(turn) { current ->
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
            is RunEvent.ReasoningAvailable -> updateTurn(turn) { current ->
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

            is RunEvent.ToolStarted -> updateTurn(turn) {
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

            is RunEvent.ToolCompleted -> updateTurn(turn) { current ->
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
                val applied = updateTurn(turn) { current ->
                    current.copy(
                        items = current.items.finishStreaming().markLastToolAwaiting(),
                        phase = RunPhase.AwaitingApproval(event.runId.orEmpty(), approval),
                    )
                }
                if (applied) _signals.tryEmit(RunSignal.ApprovalNeeded(approval))
            }

            is RunEvent.ApprovalResponded -> {
                val applied = updateTurn(turn) { current ->
                    val runId = (current.phase as? RunPhase.AwaitingApproval)?.runId
                        ?: event.runId.orEmpty()
                    current.copy(phase = RunPhase.Running(runId))
                }
                if (applied) _signals.tryEmit(RunSignal.ApprovalCleared)
            }

            is RunEvent.Completed -> {
                val applied = updateTurn(turn) { current ->
                    current.copy(
                        // Only now: a `MEDIA:` line arrives a character at a
                        // time, and a path half-written is not a path.
                        items = current.items.finishStreaming().map { item ->
                            if (item is TranscriptItem.AssistantText && item.imagePaths.isEmpty()) {
                                parseAttachmentRefs(item.text, ASSISTANT_MEDIA_DIRECTIVE)
                                    .let { parsed ->
                                        if (parsed.imagePaths.isEmpty()) {
                                            item
                                        } else {
                                            item.copy(
                                                text = parsed.text,
                                                imagePaths = parsed.imagePaths,
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
                        endedTurn = turn,
                    )
                }
                if (applied) {
                    _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = true))
                    // Fetch whatever that turn produced, the same pass a
                    // reopened session uses.
                    scope.launch { restoreAttachedImages() }
                }
            }

            is RunEvent.Failed -> {
                val applied = updateTurn(turn) {
                    it.copy(
                        items = it.items.finishStreaming() + TranscriptItem.Failure(
                            nextKey("e"),
                            event.error?.takeIf { it.isNotBlank() }?.let(UiError::Raw)
                                ?: UiError.RunFailed,
                        ),
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                        endedTurn = turn,
                    )
                }
                if (applied) _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = false))
            }

            is RunEvent.Cancelled -> {
                val applied = updateTurn(turn) {
                    it.copy(
                        items = it.items.finishStreaming(),
                        phase = RunPhase.Idle,
                        runStartedAtMillis = null,
                        endedTurn = turn,
                    )
                }
                if (applied) _signals.tryEmit(RunSignal.Finished(event.runId.orEmpty(), ok = true))
            }

            // Stop was refused: the turn runs on, so it must not stay "stopping"
            // with nothing coming to end that.
            is RunEvent.StopRefused -> updateTurn(turn) { current ->
                val phase = current.phase
                current.copy(
                    phase = if (phase is RunPhase.Stopping) RunPhase.Running(phase.runId) else phase,
                    error = UiError.Raw(event.reason),
                )
            }

            // A tenth event name from a newer server is ignored, not fatal.
            is RunEvent.Unknown -> Unit
        }
    }

    private fun appendDelta(delta: String, turn: Long) {
        if (delta.isEmpty()) return
        updateTurn(turn) { current ->
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
        val state = _state.value
        val runId = when (val phase = state.phase) {
            is RunPhase.Running -> phase.runId
            is RunPhase.AwaitingApproval -> phase.runId
            else -> {
                // Sent but still being set up: nothing runs on the gateway yet, so
                // the setup itself is what stops. Its cleanup still runs — it is
                // not cancellable — and the next turn waits for it.
                if (state.startedTurn > state.endedTurn) {
                    streamJob?.cancel()
                    endTurn(state.startedTurn, error = null, asItem = false)
                }
                return
            }
        }
        _state.update { it.copy(phase = RunPhase.Stopping(runId)) }
        scope.launch {
            // Back down the transport that is running it: a socket turn is
            // stopped on its socket, and the HTTP route does not know its id.
            val run = socketRun
            runCatching { if (run != null) run.interrupt() else api.stopRun(runId) }
                .onFailure { cause -> _state.update { it.copy(error = cause.toUiError()) } }
        }
    }

    fun clearError() = _state.update { it.copy(error = null, warning = null) }
}

/** A hold's record key: the gateway it was set on (null when not recorded) and the stored session. */
internal data class HoldKey(val gateway: String?, val sessionId: String)

/** What the notification layer reacts to. */
sealed interface RunSignal {
    data class Started(val runId: String) : RunSignal
    data class ApprovalNeeded(val approval: PendingApproval) : RunSignal
    data object ApprovalCleared : RunSignal
    data class Finished(val runId: String, val ok: Boolean) : RunSignal
}

// ── transcript helpers ────────────────────────────────────────────────────

/** [turn] is the current turn and has not ended. */
private fun ChatState.isOpen(turn: Long): Boolean = startedTurn == turn && endedTurn < turn

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
 * How long a socket kept for an unanswered change is read before it is closed.
 * Closing it lets the gateway reap the live session, the other proof.
 */
internal const val SETTLE_GIVE_UP_MILLIS = 60_000L

/** The first look for a held conversation's live session: a little past the gateway's 20 s orphan grace. */
internal const val HOLD_FIRST_CHECK_MILLIS = 25_000L

internal const val HOLD_MAX_CHECK_MILLIS = 300_000L

/**
 * How many past turns get their pictures fetched when a session opens.
 *
 * Each one is a separate request for a file that can be megabytes, and a long
 * session holds dozens. The recent ones are the ones being looked at.
 */
private const val IMAGE_RESTORE_LIMIT = 12
