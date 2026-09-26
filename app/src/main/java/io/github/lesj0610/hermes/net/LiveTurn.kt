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
    /** Null when no live session was ever opened. */
    val close: StepOutcome?,
) {
    /** A restore was needed and is not known to have happened. */
    val restoreFailed: Boolean get() = restore != null && restore != StepOutcome.Done
}

/**
 * The gateway-side resources one turn holds, and the one way to give them back.
 *
 * It exists before anything is opened and is filled in as the turn acquires
 * things: the live session id the moment the gateway hands it over, and the
 * level to restore just before a spoken turn changes it. Every way a turn can
 * end — setup refused, an attachment or the submit failing, the caller being
 * cancelled, the stream finishing or breaking — goes through [finish], so none
 * of them can skip the restore or leave the live session open.
 *
 * [finish] runs once (later calls answer the same report), is never cancelled,
 * and bounds every send and every wait by [timeoutMillis]. Its report says what
 * is known; a timed-out or unreachable step is not reported as done, because
 * the gateway's state after it is not known.
 */
internal class LiveTurn(
    private val transport: FrameTransport,
    private val rpc: RpcSession,
    private val timeoutMillis: Long,
) {
    @Volatile
    var liveId: String? = null

    @Volatile
    var restoreReasoning: String? = null

    private val lock = Mutex()
    private var report: CleanupReport? = null

    suspend fun finish(): CleanupReport = withContext(NonCancellable) {
        lock.withLock {
            report ?: run {
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
                val close = live?.let { id ->
                    step { rpc.callRaw("session.close", buildJsonObject { put("session_id", id) }) }
                }
                withTimeoutOrNull(timeoutMillis) { runCatching { transport.close() } }
                CleanupReport(restore, close).also { report = it }
            }
        }
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
