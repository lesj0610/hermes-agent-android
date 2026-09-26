package io.github.lesj0610.hermes.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The socket path end to end — the real [DashboardApi.startSocketRun],
 * [LiveTurn] and [SocketRun] — against [FakeGateway] instead of a network.
 *
 * Each test breaks one step of a turn and checks what the gateway was left
 * with: which levels were put back, that no live session was torn down, and
 * what the cleanup report admits it does not know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocketTurnLocalTest {

    private val gateway = FakeGateway().apply {
        // Built, the gateway reports the local endpoint under its named row.
        stored["s1"] = FakeGateway.Runtime("Qwen", endpointOf("custom"), "xhigh", LOCAL)
    }
    private val cleanups = mutableListOf<Cleanup>()

    private fun api(timeout: Long = CLEANUP_TIMEOUT) =
        DashboardApi({ "http://dashboard.invalid" }, { "user" to "pass" }, { gateway.open() }, timeout, SETUP_TIMEOUT)

    private val spoken = VoiceTurn(context = "Delivery: test", restoreReasoning = "xhigh")
    private val quiet = TurnRuntime(reasoning = "none")

    private suspend fun start(
        runtime: TurnRuntime = quiet,
        voice: VoiceTurn? = spoken,
        images: List<String> = emptyList(),
        heldLiveId: String? = null,
    ): SocketRun = api().startSocketRun(
        "s1", "안녕", images, runtime, voice, onCleanup = { cleanups += it }, heldLiveId = heldLiveId,
    )

    private suspend fun SocketRun.drain(): List<RunEvent> {
        val events = mutableListOf<RunEvent>()
        events().collect { events += it }
        return events
    }

    // ── cleanup on every ending ──────────────────────────────────────────

    @Test
    fun `a refused setting keeps the original error and tears nothing down`() = runTest {
        gateway.refuseNext("config.set:reasoning", "unknown reasoning value")
        try {
            start()
            fail("expected the refusal")
        } catch (expected: GatewayRpcException) {
            assertEquals("unknown reasoning value", expected.message)
        }
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())
        // Refused outright: the level never changed, so nothing is put back.
        assertEquals(CleanupReport(restore = null), cleanups.single().report)
        assertTrue(gateway.connections.single().closed)
        assertTrue(gateway.closedLive.isEmpty())
    }

    @Test
    fun `an attachment failure after the level changed puts it back`() = runTest {
        gateway.refuseNext("image.attach_bytes", "too large")
        try {
            start(images = listOf("data:image/jpeg;base64,AAAA"))
            fail("expected the attachment failure")
        } catch (expected: GatewayRpcException) {
            assertEquals("too large", expected.message)
        }
        assertEquals(
            listOf("session.resume", "config.set:reasoning", "image.attach_bytes", "config.set:reasoning"),
            gateway.methods(),
        )
        assertEquals(CleanupReport(StepOutcome.Done), cleanups.single().report)
        assertEquals("xhigh", gateway.liveRuntime("live-1")!!.reasoning)
    }

    @Test
    fun `a restore the socket refuses to send is known not to have happened`() = runTest {
        gateway.failSendOf = "prompt.submit"
        try {
            start()
            fail("expected the send failure")
        } catch (expected: IOException) {
            assertEquals("send failed: prompt.submit", expected.message)
        }
        val report = cleanups.single().report
        // Refused by the socket before it went out: not applied, and never will be.
        assertTrue(report.restore is StepOutcome.Unreachable)
        assertTrue(report.restoreFailed)
        assertFalse(report.unsettled)
        assertEquals("none", gateway.liveRuntime("live-1")!!.reasoning)
    }

    @Test
    fun `cancelled mid-setup, the level is put back and the unanswered one heard out`() = runTest {
        gateway.hold["config.set:reasoning"] = 1
        val job = launch {
            try {
                start()
            } catch (_: Exception) {
            }
        }
        // runCurrent: fast-forwarding would expire the setup's own time limit first.
        runCurrent()
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())
        job.cancel()
        runCurrent()
        assertEquals(
            listOf("session.resume", "config.set:reasoning", "config.set:reasoning"),
            gateway.methods(),
        )
        // The first change's answer was never read, so the socket stays for it.
        val cleanup = cleanups.single()
        assertTrue(cleanup.report.unsettled)
        assertTrue(cleanup.awaitingAnswer)
        assertFalse(gateway.connections.single().closed)
        gateway.releaseHeld()
        assertTrue(cleanup.settle(CLEANUP_TIMEOUT))
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun `a setup request with no answer fails the turn instead of hanging it`() = runTest {
        gateway.hold["session.resume"] = 1
        try {
            start()
            fail("expected the setup to time out")
        } catch (expected: GatewayTimeoutException) {
            assertTrue(expected.message.orEmpty().contains("session.resume"))
        }
        // No live session was named, so nothing was changed on one.
        assertFalse(cleanups.single().report.unsettled)
        assertTrue(gateway.connections.single().closed)
    }

    // ── the stream, answers included ─────────────────────────────────────

    @Test
    fun `a refused submit ends the stream with the gateway's reason while the socket stays open`() = runTest {
        gateway.refuseNext("prompt.submit", "session busy")
        val run = start(TurnRuntime(reasoning = "xhigh"), voice = null)
        assertEquals(listOf<RunEvent>(RunEvent.Failed(null, null, "session busy")), run.drain())
        assertFalse(gateway.connections.single().closed)
        run.close()
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun `events that arrive before the submit is acknowledged are kept`() = runTest {
        val events = start(TurnRuntime(reasoning = "xhigh"), voice = null).drain()
        assertEquals(
            listOf(
                RunEvent.MessageDelta(null, null, "안녕하세요. "),
                RunEvent.MessageDelta(null, null, "반갑습니다."),
                RunEvent.Completed(null, null, null, null),
            ),
            events,
        )
    }

    @Test
    fun `events after the acknowledgement are kept too`() = runTest {
        gateway.submitReplyFirst = true
        val events = start(TurnRuntime(reasoning = "xhigh"), voice = null).drain()
        assertEquals(3, events.size)
        assertEquals(RunEvent.Completed(null, null, null, null), events.last())
    }

    @Test
    fun `a refused stop is reported and the stream goes on`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        gateway.refuseNext("session.interrupt", "not running")
        val run = start(TurnRuntime(reasoning = "xhigh"), voice = null)
        run.interrupt()
        val connection = gateway.connections.single()
        connection.event("message.complete", kotlinx.serialization.json.buildJsonObject { })
        assertEquals(
            listOf(RunEvent.StopRefused("not running"), RunEvent.Completed(null, null, null, null)),
            run.drain(),
        )
    }

    @Test
    fun `a stream that ends early ends the flow, and the restore that could not go out is reported`() = runTest {
        gateway.script = FakeGateway.Script.EndEarly
        val run = start()
        assertEquals(listOf<RunEvent>(RunEvent.MessageDelta(null, null, "안녕")), run.drain())
        val report = run.close().report
        assertTrue(report.restore is StepOutcome.Unreachable)
        assertTrue(report.restoreFailed)
        assertFalse(report.unsettled)
    }

    // ── cleanup outcomes ─────────────────────────────────────────────────

    @Test
    fun `a refused restore is reported as refused`() = runTest {
        val run = start()
        run.drain()
        gateway.refuseNext("config.set:reasoning", "restore refused")
        assertEquals(CleanupReport(StepOutcome.Refused("restore refused")), run.close().report)
    }

    @Test
    fun `a restore that never answers times out, and its socket is kept for the answer`() = runTest {
        val run = start()
        run.drain()
        gateway.hold["config.set:reasoning"] = 1
        val before = currentTime
        val cleanup = run.close()
        assertEquals(CleanupReport(StepOutcome.TimedOut, unsettled = true), cleanup.report)
        assertEquals("live-1", cleanup.liveId)
        assertEquals("s1", cleanup.storedId)
        assertTrue(currentTime - before <= CLEANUP_TIMEOUT * 2)
        assertTrue(cleanup.awaitingAnswer)
        assertFalse(gateway.connections.single().closed)
        // Closing twice answers the same cleanup and sends nothing more.
        val sent = gateway.calls.size
        assertTrue(cleanup === run.close())
        assertEquals(sent, gateway.calls.size)
        // The answer arrives late: every change is accounted for, and only then is the socket let go.
        gateway.releaseHeld()
        assertTrue(cleanup.settle(CLEANUP_TIMEOUT))
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun `an answer that never comes is given up on, the socket closed, and nothing claimed`() = runTest {
        val run = start()
        run.drain()
        gateway.delayApply["config.set:reasoning"] = 1
        val cleanup = run.close()
        assertTrue(cleanup.awaitingAnswer)
        assertFalse(cleanup.settle(CLEANUP_TIMEOUT))
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun `a socket whose close never completes still lets cleanup finish in bounded time`() = runTest {
        val run = start()
        run.drain()
        gateway.hangClose = true
        val before = currentTime
        assertEquals(CleanupReport(StepOutcome.Done), run.close().report)
        assertTrue(currentTime - before <= CLEANUP_TIMEOUT * 2)
    }

    @Test
    fun `a typed turn restores nothing and never tears the live session down`() = runTest {
        val run = start(TurnRuntime(reasoning = "xhigh"), voice = null)
        run.drain()
        assertEquals(CleanupReport(restore = null), run.close().report)
        assertFalse(gateway.methods().contains("config.set:reasoning"))
        assertFalse(gateway.methods().contains("session.close"))
    }

    // ── what the session reports ─────────────────────────────────────────

    @Test
    fun `a fresh resume has the agent built first and leaves the history to HTTP`() = runTest {
        start(TurnRuntime(reasoning = "xhigh"), voice = null).drain()
        val resume = gateway.calls.first().params
        assertEquals("true", (resume["eager_build"] as JsonPrimitive).content)
        assertEquals("true", (resume["omit_messages"] as JsonPrimitive).content)
        // Built, the reported level is the session's: nothing to change.
        assertEquals(listOf("session.resume", "prompt.submit"), gateway.methods())
    }

    @Test
    fun `a fixed provider the session reports is not switched again`() = runTest {
        gateway.stored["s1"] = FakeGateway.Runtime("claude-opus-5", "", "xhigh", "anthropic")
        start(TurnRuntime("claude-opus-5", "anthropic", "xhigh"), voice = null).drain()
        assertEquals(listOf("session.resume", "prompt.submit"), gateway.methods())
    }

    @Test
    fun `a live session whose agent is not built yet is switched explicitly`() = runTest {
        // The desktop has the chat open, unbuilt: it reports no route at all.
        gateway.stored["s1"] = FakeGateway.Runtime("claude-opus-5", "", "xhigh", "anthropic")
        val held = gateway.openLive("s1")
        start(TurnRuntime("claude-opus-5", "anthropic", "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertEquals(held, (gateway.calls[1].params["session_id"] as JsonPrimitive).content)
    }

    @Test
    fun `a stored runtime that cannot be built is resumed without the build and the pick applied`() = runTest {
        gateway.unbuildable += "s1"
        start(TurnRuntime("Qwen", "custom:lab", "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertFalse(gateway.calls[1].params.containsKey("eager_build"))
        assertEquals(gateway.endpointOf("custom:lab"), gateway.liveRuntime("live-1")!!.endpoint)
    }

    @Test
    fun `a resume refused for any other reason is not retried`() = runTest {
        gateway.refuseNext("session.resume", "no such session")
        try {
            start(TurnRuntime("Qwen", LOCAL, "xhigh"), voice = null)
            fail("expected the refusal")
        } catch (expected: GatewayRpcException) {
            assertEquals("no such session", expected.message)
        }
        assertEquals(listOf("session.resume"), gateway.methods())
    }

    @Test
    fun `a turn handed back the live session it is held on stops before sending anything`() = runTest {
        try {
            start(TurnRuntime("Qwen", LOCAL, "low"), voice = null, heldLiveId = "live-1")
            fail("expected the turn to stop")
        } catch (expected: ConversationHeldException) {
        }
        assertEquals(listOf("session.resume"), gateway.methods())
        assertTrue(gateway.connections.single().closed)
        assertEquals("xhigh", gateway.liveRuntime("live-1")!!.reasoning)
    }

    // ── custom endpoints: the pick is sent every turn ─────────────────────

    @Test
    fun `a session reporting the very row picked while on another endpoint is still switched`() = runTest {
        // Only tenant B is configured; the session runs on /TenantA/v1, which
        // the gateway, lowercasing whole URLs, reports as tenant B.
        gateway.rows = listOf(FakeGateway.Row("custom:tenant-b", "http://10.0.0.9:8000/tenanta/v1"))
        val tenantA = "http://10.0.0.9:8000/TenantA/v1"
        gateway.stored["s1"] = FakeGateway.Runtime("Qwen", tenantA, "xhigh", gateway.identityOf(tenantA))
        assertEquals("custom:tenant-b", gateway.stored.getValue("s1").identity)

        start(TurnRuntime("Qwen", "custom:tenant-b", "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertEquals("http://10.0.0.9:8000/tenanta/v1", gateway.liveRuntime("live-1")!!.endpoint)
    }

    @Test
    fun `the same model on another custom endpoint is switched to it`() = runTest {
        start(TurnRuntime("Qwen", "custom:lab", "xhigh"), voice = null).drain()
        // The switch re-reads the level from config, so the level is applied after it.
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertEquals(gateway.endpointOf("custom:lab"), gateway.liveRuntime("live-1")!!.endpoint)
    }

    @Test
    fun `the row the session reports is applied again all the same`() = runTest {
        start(TurnRuntime("Qwen", LOCAL, "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertEquals(
            "Qwen --provider $LOCAL --session",
            (gateway.calls[1].params["value"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `another name for the endpoint the session is on is applied explicitly`() = runTest {
        gateway.rows += FakeGateway.Row("custom:home", "HTTP://127.0.0.1:8088/v1/")
        start(TurnRuntime("Qwen", "custom:home", "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
        assertEquals("custom:home", gateway.liveRuntime("live-1")!!.identity)
    }

    @Test
    fun `endpoints whose paths differ only in case are switched between`() = runTest {
        gateway.rows = listOf(
            FakeGateway.Row("custom:tenant-a", "http://10.0.0.9:8000/TenantA/v1"),
            FakeGateway.Row("custom:tenant-b", "http://10.0.0.9:8000/tenanta/v1"),
        )
        gateway.stored["s1"] = FakeGateway.Runtime("Qwen", "http://10.0.0.9:8000/TenantA/v1", "xhigh", "custom:tenant-a")
        start(TurnRuntime("Qwen", "custom:tenant-b", "xhigh"), voice = null).drain()
        assertEquals("http://10.0.0.9:8000/tenanta/v1", gateway.liveRuntime("live-1")!!.endpoint)
    }

    @Test
    fun `bare custom is applied like any other custom pick`() = runTest {
        gateway.stored["s1"] = FakeGateway.Runtime("Qwen", "http://10.0.0.7:9000/v1", "xhigh", "custom")
        start(TurnRuntime("Qwen", "custom", "xhigh"), voice = null).drain()
        assertEquals(
            listOf("session.resume", "config.set:model", "config.set:reasoning", "prompt.submit"),
            gateway.methods(),
        )
    }

    private companion object {
        const val CLEANUP_TIMEOUT = 1_000L
        const val SETUP_TIMEOUT = 5_000L
        const val LOCAL = "custom:local-(127.0.0.1:8088)"
    }
}
