package io.github.lesj0610.hermes.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How one cleanup step went. Only [Done] means the gateway is known to have acted. */
sealed interface StepOutcome {
    data object Done : StepOutcome

    /** The gateway answered with an error. */
    data class Refused(val message: String) : StepOutcome

    /** No answer within the limit; the gateway may or may not have acted. */
    data object TimedOut : StepOutcome

    /** The socket was gone, so the request may never have reached the gateway. */
    data class Unreachable(val reason: String?) : StepOutcome
}

/** What giving a live turn back achieved. */
data class CleanupReport(
    /** Null when the turn changed nothing that needed putting back. */
    val restore: StepOutcome?,
    /**
     * A change this turn sent to the live session — the restore, or a setting
     * or attachment during setup — went out and its answer was never read. The
     * gateway may still carry it out, after whatever the next turn sets: a
     * timeout is not proof it was dropped.
     */
    val unsettled: Boolean = false,
) {
    /** A restore was needed and is not known to have happened. */
    val restoreFailed: Boolean get() = restore != null && restore != StepOutcome.Done
}

/** A turn given back: what its cleanup achieved, and what it leaves the next turn to wait on. */
class Cleanup internal constructor(
    val report: CleanupReport,
    /** The live session the turn ran on, when the gateway named one. */
    val liveId: String?,
    /** The stored conversation that live session belongs to, when known. */
    val storedId: String?,
    private val kept: LiveTurn?,
) {
    /** The socket stays open for the answers to [CleanupReport.unsettled] changes; see [settle]. */
    val awaitingAnswer: Boolean get() = kept != null

    /**
     * Reads the kept socket until every unanswered change has been answered,
     * for at most [giveUpMillis], then closes it. True only when every one
     * was: the gateway has dealt with them all and none can land later.
     */
    suspend fun settle(giveUpMillis: Long): Boolean = kept?.settle(giveUpMillis) ?: !report.unsettled
}

/** Requests that change the live session a later turn runs on. */
internal val SESSION_CHANGES = setOf("config.set", "image.attach_bytes")

/**
 * The gateway-side resources one turn holds, and the one way to give them back.
 *
 * It exists before anything is opened and is filled in as the turn acquires
 * things: the live session id the moment the gateway hands it over, and the
 * level to restore just before a spoken turn changes it. Every way a turn can
 * end — setup refused, an attachment or the submit failing, the caller being
 * cancelled, the stream finishing or breaking — goes through [finish], so none
 * of them can skip the restore.
 *
 * The live session itself is never closed from here. The gateway keeps one
 * live session per conversation and hands the SAME one to every client that
 * resumes it — the desktop with the chat open, or this app's next turn — and
 * `session.close` tears it down for all of them. Closing the socket is the
 * release: the gateway reaps a live session no client holds once its orphan
 * grace (20 s by default) has passed, and not while a turn is still running.
 *
 * Except when a change went out unanswered. Its answer can only arrive on the
 * socket it went out on, and the gateway works through one socket's requests
 * in order, so an open socket is kept for [Cleanup.settle] to hear it out:
 * that answer is the proof it will not land later. Closed, only the live
 * session's end can show that.
 *
 * [finish] runs once (later calls answer the same cleanup), is never
 * cancelled, and bounds every send and every wait by [timeoutMillis]. Its
 * report says what is known; a timed-out or unreachable step is not reported
 * as done, because the gateway's state after it is not known.
 */
internal class LiveTurn(
    private val transport: FrameTransport,
    private val rpc: RpcSession,
    private val timeoutMillis: Long,
) {
    @Volatile
    var liveId: String? = null

    @Volatile
    var storedId: String? = null

    @Volatile
    var restoreReasoning: String? = null

    private val lock = Mutex()
    private var cleanup: Cleanup? = null

    suspend fun finish(): Cleanup = withContext(NonCancellable) {
        lock.withLock {
            cleanup ?: run {
                val live = liveId
                val restore = live?.let { id ->
                    restoreReasoning?.let { level ->
                        step {
                            rpc.callRaw(
                                "config.set",
                                buildJsonObject {
                                    put("session_id", id)
                                    put("key", "reasoning")
                                    put("value", level)
                                },
                            )
                        }
                    }
                }
                val unsettled = live != null && rpc.awaiting(SESSION_CHANGES)
                val keep = unsettled && transport.isOpen
                if (!keep) closeTransport()
                Cleanup(CleanupReport(restore, unsettled), live, storedId, if (keep) this@LiveTurn else null)
                    .also { cleanup = it }
            }
        }
    }

    /** See [Cleanup.settle]. */
    suspend fun settle(giveUpMillis: Long): Boolean = try {
        withTimeoutOrNull(giveUpMillis) { rpc.awaitAnswers(SESSION_CHANGES) } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    } finally {
        withContext(NonCancellable) { closeTransport() }
    }

    private suspend fun closeTransport() {
        withTimeoutOrNull(timeoutMillis) { runCatching { transport.close() } }
    }

    private suspend fun step(request: suspend () -> Unit): StepOutcome = try {
        withTimeoutOrNull(timeoutMillis) {
            request()
            StepOutcome.Done
        } ?: StepOutcome.TimedOut
    } catch (refused: GatewayRpcException) {
        StepOutcome.Refused(refused.message)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (gone: Exception) {
        // A closed socket, or a send that failed: nothing was heard back.
        StepOutcome.Unreachable(gone.message)
    }
}
