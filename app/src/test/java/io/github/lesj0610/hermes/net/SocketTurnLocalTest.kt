package io.github.lesj0610.hermes.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The socket path end to end — the real [DashboardApi.startSocketRun],
 * [LiveTurn] and [SocketRun] — against [FakeGateway] instead of a network.
 *
 * Each test breaks one step of a turn and checks what the gateway was left
 * with: which live sessions were closed, which levels were put back, and what
 * the cleanup report admits it does not know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocketTurnLocalTest {

    private val gateway = FakeGateway().apply {
        stored["s1"] = FakeGateway.Runtime("Qwen", endpointOf("custom"), "xhigh", "custom:custom")
    }
    private val reports = mutableListOf<CleanupReport>()

    private fun api(timeout: Long = CLEANUP_TIMEOUT) =
        DashboardApi({ "http://dashboard.invalid" }, { "user" to "pass" }, { gateway.open() }, timeout)

    private val spoken = VoiceTurn(context = "Delivery: test", restoreReasoning = "xhigh")
    private val quiet = TurnRuntime(reasoning = "none")

    private suspend fun start(
        runtime: TurnRuntime = quiet,
        voice: VoiceTurn? = spoken,
        images: List<String> = emptyList(),
    ): SocketRun = api().startSocketRun("s1", "안녕", images, runtime, voice, onCleanup = { reports += it })

    @Test
    fun `a refused setting closes the session it opened and keeps the original error`() = runTest {
        gateway.refuseNext("config.set:reasoning", "unknown reasoning value")
        try {
            start()
            fail("expected the refusal")
        } catch (expected: GatewayRpcException) {
            assertEquals("unknown reasoning value", expected.message)
        }
        assertEquals(listOf("session.resume", "config.set:reasoning", "session.close"), gateway.methods())
        assertEquals(listOf("live-1"), gateway.closedLive)
        // Refused outright: the level never changed, so nothing is put back.
        assertEquals(CleanupReport(restore = null, close = StepOutcome.Done), reports.single())
        assertEquals("xhigh", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `a refused model closes the session without touching the level`() = runTest {
        gateway.refuseNext("config.set:model", "unknown model")
        try {
            start(TurnRuntime("nope", "anthropic", "none"))
            fail("expected the refusal")
        } catch (expected: GatewayRpcException) {
            assertEquals("unknown model", expected.message)
        }
        assertEquals(listOf("session.resume", "config.set:model", "session.close"), gateway.methods())
        assertNull(reports.single().restore)
    }

    @Test
    fun `an attachment failure after the level changed puts it back and closes`() = runTest {
        gateway.refuseNext("image.attach_bytes", "too large")
        try {
            start(images = listOf("data:image/jpeg;base64,AAAA"))
            fail("expected the attachment failure")
        } catch (expected: GatewayRpcException) {
            assertEquals("too large", expected.message)
        }
        assertEquals(
            listOf(
                "session.resume", "config.set:reasoning", "image.attach_bytes",
                "config.set:reasoning", "session.close",
            ),
            gateway.methods(),
        )
        assertEquals(CleanupReport(StepOutcome.Done, StepOutcome.Done), reports.single())
        assertEquals("xhigh", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `a submit that cannot be sent is cleaned up only as far as the socket allows`() = runTest {
        gateway.failSendOf = "prompt.submit"
        try {
            start()
            fail("expected the send failure")
        } catch (expected: IOException) {
            assertEquals("send failed: prompt.submit", expected.message)
        }
        val report = reports.single()
        // The socket is gone: neither step can be confirmed, and the report says so.
        assertTrue(report.restore is StepOutcome.Unreachable)
        assertTrue(report.close is StepOutcome.Unreachable)
        assertTrue(report.restoreFailed)
        assertEquals("none", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `cancelled mid-setup, the session is still closed and the level put back`() = runTest {
        gateway.hold["config.set:reasoning"] = 1
        val job = launch {
            try {
                start()
            } catch (_: Exception) {
            }
        }
        advanceUntilIdle()
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())
        job.cancel()
        advanceUntilIdle()
        assertEquals(
            listOf("session.resume", "config.set:reasoning", "config.set:reasoning", "session.close"),
            gateway.methods(),
        )
        assertEquals(listOf("live-1"), gateway.closedLive)
    }

    @Test
    fun `a stream that ends early ends the flow, and cleanup admits what it cannot know`() = runTest {
        gateway.script = FakeGateway.Script.EndEarly
        val run = start()
        val events = mutableListOf<RunEvent>()
        run.events().collect { events += it }
        assertEquals(listOf<RunEvent>(RunEvent.MessageDelta(null, null, "안녕")), events)
        val report = run.close()
        assertTrue(report.restore is StepOutcome.Unreachable)
        assertTrue(report.restoreFailed)
    }

    @Test
    fun `a refused restore is reported as refused`() = runTest {
        val run = start()
        run.events().collect { }
        gateway.refuseNext("config.set:reasoning", "restore refused")
        val report = run.close()
        assertEquals(StepOutcome.Refused("restore refused"), report.restore)
        assertEquals(StepOutcome.Done, report.close)
    }

    @Test
    fun `a restore that never answers times out within the bound`() = runTest {
        val run = start()
        run.events().collect { }
        gateway.hold["config.set:reasoning"] = 1
        val before = currentTime
        val report = run.close()
        assertEquals(StepOutcome.TimedOut, report.restore)
        assertEquals(StepOutcome.Done, report.close)
        assertTrue(currentTime - before <= CLEANUP_TIMEOUT * 3)
        // Closing twice answers the same report and sends nothing more.
        val sent = gateway.calls.size
        assertEquals(report, run.close())
        assertEquals(sent, gateway.calls.size)
    }

    @Test
    fun `a typed turn restores nothing and closes its session`() = runTest {
        val run = start(TurnRuntime(reasoning = "xhigh"), voice = null)
        run.events().collect { }
        val report = run.close()
        assertEquals(CleanupReport(restore = null, close = StepOutcome.Done), report)
        assertFalse(gateway.methods().contains("config.set:reasoning"))
    }

    @Test
    fun `the same model on another custom endpoint is switched to it`() = runTest {
        val run = start(TurnRuntime("Qwen", "custom:lab", "xhigh"), voice = null)
        run.events().collect { }
        run.close()
        // The switch re-reads the level from config, so the level is applied after it.
        assertEquals(
            listOf(
                "session.resume", "model.options", "config.set:model", "config.set:reasoning",
                "prompt.submit", "session.close",
            ),
            gateway.methods(),
        )
        assertEquals(gateway.endpointOf("custom:lab"), gateway.stored.getValue("s1").endpoint)
    }

    @Test
    fun `an alias of the endpoint the session is on is not switched`() = runTest {
        val run = start(TurnRuntime("Qwen", "custom:local-(127.0.0.1:8088)", "xhigh"), voice = null)
        run.events().collect { }
        run.close()
        assertEquals(listOf("session.resume", "model.options", "prompt.submit", "session.close"), gateway.methods())
    }

    @Test
    fun `an endpoint that cannot be confirmed is applied explicitly`() = runTest {
        gateway.refuseNext("model.options", "unavailable")
        val run = start(TurnRuntime("Qwen", "custom:local-(127.0.0.1:8088)", "xhigh"), voice = null)
        run.events().collect { }
        run.close()
        assertTrue(gateway.methods().contains("config.set:model"))
    }

    private companion object {
        const val CLEANUP_TIMEOUT = 1_000L
    }
}
