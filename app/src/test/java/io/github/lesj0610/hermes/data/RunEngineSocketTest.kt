package io.github.lesj0610.hermes.data

import io.github.lesj0610.hermes.net.DashboardApi
import io.github.lesj0610.hermes.net.FakeGateway
import io.github.lesj0610.hermes.net.HermesApi
import io.github.lesj0610.hermes.net.RunEvent
import io.github.lesj0610.hermes.net.VoiceTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * [RunEngine] driving real socket turns against [FakeGateway]: how a turn ends,
 * that it ends once, and that a turn still finishing cannot reach into the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunEngineSocketTest {

    private val gateway = FakeGateway().apply {
        stored["s1"] = FakeGateway.Runtime("Qwen", endpointOf("custom"), "xhigh", "custom:custom")
    }

    private fun engine(scope: CoroutineScope): RunEngine {
        val dashboard = DashboardApi({ "http://dashboard.invalid" }, { "u" to "p" }, { gateway.open() }, 1_000)
        // Port 1 refuses at once: the HTTP route is never meant to be reached here,
        // and reopening a session's history fails fast instead of hanging.
        val api = HermesApi({ "http://127.0.0.1:1" }, { "" })
        return RunEngine(api, scope, dashboard, socketEnabled = { true })
    }

    private val scopes = mutableListOf<CoroutineScope>()

    // Not backgroundScope: advanceUntilIdle does not run background work on its
    // own, and the engine's turns are exactly the work these tests wait on. A
    // dispatcher on the test's scheduler keeps virtual time; the scope is
    // cancelled after each test so a turn left waiting does not outlive it.
    private suspend fun TestScope.opened(): RunEngine {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return engine(scope).apply {
            openSession("s1")
            clearError()
        }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private val spoken = VoiceTurn(context = "Delivery: test", restoreReasoning = "xhigh")

    @Test
    fun `a typed turn completes, ends once, and closes its session`() = runTest {
        val engine = opened()
        val turn = engine.send("안녕", null, null, "xhigh")!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        assertEquals("안녕하세요. 반갑습니다.", (state.items.last() as TranscriptItem.AssistantText).text)
        assertNull(state.error)
        assertNull(state.warning)
        assertEquals(listOf("live-1"), gateway.closedLive)
    }

    @Test
    fun `a stream that ends early fails the turn exactly once`() = runTest {
        gateway.script = FakeGateway.Script.EndEarly
        val engine = opened()
        val turn = engine.send("안녕", null, null, "none", voice = spoken)!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        val failures = state.items.filterIsInstance<TranscriptItem.Failure>()
        assertEquals(listOf<UiError>(UiError.Disconnected), failures.map { it.error })
        // The socket was gone, so the restore cannot be confirmed — and is not claimed.
        assertEquals(UiError.ReasoningNotRestored, state.warning)
        assertNull(state.error)
    }

    @Test
    fun `a refused setting ends the turn with the gateway's reason`() = runTest {
        gateway.refuseNext("config.set:reasoning", "unknown reasoning value")
        val engine = opened()
        val turn = engine.send("안녕", null, null, "low")!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        assertEquals(UiError.Raw("unknown reasoning value"), state.error)
        assertTrue(state.items.none { it is TranscriptItem.Failure })
        assertEquals(listOf("live-1"), gateway.closedLive)
    }

    @Test
    fun `a first turn still closing cannot take the second turn's socket`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()

        // Turn 1, spoken: set up, then its reply arrives by hand.
        val first = engine.send("첫 번째", null, null, "none", voice = spoken)!!
        advanceUntilIdle()
        // Its restore will hang until released, keeping turn 1 in cleanup. From
        // here time is not advanced: the restore's bound must not expire before
        // turn 2 has started over the top of it.
        gateway.hold["config.set:reasoning"] = 1
        gateway.connections[0].event("message.delta", buildJsonObject { put("text", "첫 답.") })
        gateway.connections[0].event("message.complete", buildJsonObject { put("status", "complete") })
        runCurrent()
        assertEquals(first, engine.state.value.endedTurn)
        assertFalse(gateway.closedLive.contains("live-1"))

        // Turn 2 starts while turn 1 is still putting its level back.
        val second = engine.send("두 번째", null, null, "xhigh")!!
        runCurrent()
        assertTrue(engine.state.value.phase is RunPhase.Running)

        // Stop reaches turn 2's socket, not turn 1's.
        engine.stop()
        runCurrent()
        val interrupt = gateway.calls.single { it.method == "session.interrupt" }
        assertEquals(2, interrupt.connection)
        assertEquals("live-2", (interrupt.params["session_id"] as JsonPrimitive).content)
        assertEquals(second, engine.state.value.endedTurn)
        assertEquals(RunPhase.Idle, engine.state.value.phase)
        assertFalse(gateway.closedLive.contains("live-1"))

        // Turn 1's cleanup finishes last and changes nothing about turn 2.
        gateway.releaseHeld()
        runCurrent()
        assertTrue(gateway.closedLive.containsAll(listOf("live-1", "live-2")))
        assertEquals(second, engine.state.value.endedTurn)
        assertNull(engine.state.value.warning)
        assertEquals("xhigh", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `events from an ended turn cannot touch the current one`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()
        val first = engine.send("첫 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        gateway.connections[0].event("message.complete", buildJsonObject { put("status", "complete") })
        advanceUntilIdle()
        val second = engine.send("두 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        val before = engine.state.value

        engine.apply(RunEvent.MessageDelta(null, null, "늦게 온 말"), first)
        engine.apply(RunEvent.Completed(null, null, null, null), first)
        engine.apply(RunEvent.Failed(null, null, "늦은 실패"), first)

        assertEquals(before, engine.state.value)
        assertTrue(engine.state.value.endedTurn < second)
    }

    @Test
    fun `switching session mid-setup still closes the live session`() = runTest {
        gateway.hold["config.set:reasoning"] = 1
        val engine = opened()
        engine.send("안녕", null, null, "none", voice = spoken)
        advanceUntilIdle()
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())

        engine.openSession("s1")
        advanceUntilIdle()
        assertTrue(gateway.closedLive.contains("live-1"))
        // The level was planned for restore before the change went out, so it
        // is put back even though the change's own answer never arrived.
        assertEquals(listOf("session.resume", "config.set:reasoning", "config.set:reasoning", "session.close"), gateway.methods())
    }
}
