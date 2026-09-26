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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunEngine] driving real socket turns against [FakeGateway]: how a turn ends,
 * that it ends once, and that a turn still finishing cannot reach into the next
 * — which, since the gateway hands every turn of a conversation the same live
 * session, is a question about the gateway's state and not only the app's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunEngineSocketTest {

    private val gateway = FakeGateway().apply {
        stored["s1"] = FakeGateway.Runtime("Qwen", endpointOf("custom"), "xhigh", identityOf(endpointOf("custom")))
    }
    private val scopes = mutableListOf<CoroutineScope>()

    private fun engine(scope: CoroutineScope): RunEngine {
        val dashboard = DashboardApi(
            { "http://dashboard.invalid" }, { "u" to "p" }, { gateway.open() }, CLEANUP_TIMEOUT, SETUP_TIMEOUT,
        )
        // Port 1 refuses at once: the HTTP route is never meant to be reached here,
        // and reopening a session's history fails fast instead of hanging.
        val api = HermesApi({ "http://127.0.0.1:1" }, { "" })
        return RunEngine(api, scope, dashboard, socketEnabled = { true })
    }

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

    private fun complete(connection: Int) {
        gateway.connections[connection - 1].event("message.delta", buildJsonObject { put("text", "답.") })
        gateway.connections[connection - 1].event("message.complete", buildJsonObject { put("status", "complete") })
    }

    private fun resumes() = gateway.calls.count { it.method == "session.resume" }

    @Test
    fun `a typed turn completes, ends once, and leaves the live session to the gateway`() = runTest {
        val engine = opened()
        val turn = engine.send("안녕", null, null, "xhigh")!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        assertEquals("안녕하세요. 반갑습니다.", (state.items.last() as TranscriptItem.AssistantText).text)
        assertNull(state.error)
        assertNull(state.warning)
        assertTrue(gateway.closedLive.isEmpty())
        assertTrue(gateway.connections.single().closed)
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
        assertEquals(
            listOf<UiError>(UiError.Disconnected),
            state.items.filterIsInstance<TranscriptItem.Failure>().map { it.error },
        )
        // The socket was gone, so the restore cannot be confirmed — and is not claimed.
        assertEquals(UiError.ReasoningNotRestored, state.warning)
        assertNull(state.error)
    }

    @Test
    fun `a refused submit on a socket that stays open ends the turn once, with the reason`() = runTest {
        gateway.refuseNext("prompt.submit", "session busy")
        val engine = opened()
        val turn = engine.send("안녕", null, null, "xhigh")!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        assertEquals(
            listOf<UiError>(UiError.Raw("session busy")),
            state.items.filterIsInstance<TranscriptItem.Failure>().map { it.error },
        )
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
    }

    @Test
    fun `the next turn waits for the previous restore on the shared live session`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()

        // Turn 1, spoken: thinking off, the reply arrives by hand.
        val first = engine.send("첫 번째", null, null, "none", voice = spoken)!!
        advanceUntilIdle()
        // Its restore to xhigh hangs until released. Time is not advanced from
        // here, so the restore's bound does not expire underneath the test.
        gateway.hold["config.set:reasoning"] = 1
        complete(1)
        runCurrent()
        assertEquals(first, engine.state.value.endedTurn)

        // Turn 2 asks for a level different from the one being restored.
        val second = engine.send("두 번째", null, null, "low")!!
        runCurrent()
        // Nothing of turn 2 reaches the gateway while turn 1 is restoring.
        assertEquals(1, resumes())
        assertTrue(engine.state.value.isBusy)

        gateway.releaseHeld()
        runCurrent()
        // Turn 2 now resumes — onto the same live session, reused — at its own level.
        assertEquals(2, resumes())
        assertEquals("low", gateway.liveRuntime("live-1")!!.reasoning)
        assertTrue(engine.state.value.phase is RunPhase.Running)

        // Stop reaches turn 2's own socket.
        engine.stop()
        runCurrent()
        val interrupt = gateway.calls.single { it.method == "session.interrupt" }
        assertEquals(2, interrupt.connection)
        assertEquals("live-1", (interrupt.params["session_id"] as JsonPrimitive).content)
        assertEquals(second, engine.state.value.endedTurn)
        assertEquals(RunPhase.Idle, engine.state.value.phase)

        // Nothing was torn down, and turn 1's restore did not overwrite turn 2.
        assertTrue(gateway.closedLive.isEmpty())
        assertEquals("low", gateway.liveRuntime("live-1")!!.reasoning)
        assertNull(engine.state.value.warning)
    }

    @Test
    fun `after a restore of unknown outcome the next turn sends its level regardless`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()
        engine.send("첫 번째", null, null, "none", voice = spoken)!!
        advanceUntilIdle()
        // The restore reaches the gateway and is applied, but its answer never
        // comes back: from the app's side the outcome is unknown.
        gateway.hold["config.set:reasoning"] = 1
        complete(1)
        advanceUntilIdle()
        assertEquals(UiError.ReasoningNotRestored, engine.state.value.warning)
        assertEquals("xhigh", gateway.liveRuntime("live-1")!!.reasoning)

        // The session reports xhigh; a typed turn wanting xhigh sends it anyway,
        // so the latest explicit setting on the session is this turn's.
        gateway.script = FakeGateway.Script.Reply
        val before = gateway.calls.size
        engine.send("두 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        assertEquals(listOf("session.resume", "config.set:reasoning", "prompt.submit"), gateway.methods().drop(before))

        // Once this turn's own level has gone out, the next turn trusts the report again.
        val after = gateway.calls.size
        engine.send("세 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        assertEquals(listOf("session.resume", "prompt.submit"), gateway.methods().drop(after))
    }

    @Test
    fun `a refused stop leaves the turn running, not stopping`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        gateway.refuseNext("session.interrupt", "not running")
        val engine = opened()
        val turn = engine.send("안녕", null, null, "xhigh")!!
        advanceUntilIdle()
        engine.stop()
        advanceUntilIdle()
        assertTrue(engine.state.value.phase is RunPhase.Running)
        assertEquals(UiError.Raw("not running"), engine.state.value.error)
        complete(1)
        advanceUntilIdle()
        assertEquals(turn, engine.state.value.endedTurn)
        assertEquals(RunPhase.Idle, engine.state.value.phase)
    }

    @Test
    fun `stop during setup ends the turn quietly and still cleans up`() = runTest {
        gateway.hold["config.set:reasoning"] = 1
        val engine = opened()
        val turn = engine.send("안녕", null, null, "none", voice = spoken)!!
        // runCurrent, not advanceUntilIdle: fast-forwarding would expire the
        // setup's own time limit and end the turn before Stop is pressed.
        runCurrent()
        assertTrue(engine.state.value.isBusy)
        assertEquals(RunPhase.Idle, engine.state.value.phase)

        engine.stop()
        runCurrent()
        val state = engine.state.value
        assertEquals(turn, state.endedTurn)
        assertFalse(state.isBusy)
        assertNull(state.error)
        assertTrue(state.items.none { it is TranscriptItem.Failure })
        // The level had been changed; putting it back is not skipped by the stop.
        assertEquals(listOf("session.resume", "config.set:reasoning", "config.set:reasoning"), gateway.methods())
    }

    @Test
    fun `a second message during setup is refused rather than racing the first`() = runTest {
        gateway.hold["session.resume"] = 1
        val engine = opened()
        assertTrue(engine.send("첫 번째", null, null, "xhigh") != null)
        runCurrent()
        assertNull(engine.send("두 번째", null, null, "xhigh"))
        assertEquals(1, engine.state.value.items.count { it is TranscriptItem.UserText })
    }

    @Test
    fun `events from an ended turn cannot touch the current one`() = runTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()
        val first = engine.send("첫 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        complete(1)
        advanceUntilIdle()
        val second = engine.send("두 번째", null, null, "xhigh")!!
        advanceUntilIdle()
        val before = engine.state.value

        engine.apply(RunEvent.MessageDelta(null, null, "늦게 온 말"), first)
        engine.apply(RunEvent.Completed(null, null, null, null), first)
        engine.apply(RunEvent.Failed(null, null, "늦은 실패"), first)
        engine.apply(RunEvent.StopRefused("늦은 거절"), first)

        assertEquals(before, engine.state.value)
        assertTrue(engine.state.value.endedTurn < second)
    }

    @Test
    fun `switching session mid-setup still puts the level back`() = runTest {
        gateway.hold["config.set:reasoning"] = 1
        val engine = opened()
        engine.send("안녕", null, null, "none", voice = spoken)
        runCurrent()
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())

        engine.openSession("s1")
        runCurrent()
        // Planned before the change went out, so put back even though the
        // change's own answer never arrived.
        assertEquals(listOf("session.resume", "config.set:reasoning", "config.set:reasoning"), gateway.methods())
        assertTrue(gateway.connections.single().closed)
    }

    private companion object {
        const val CLEANUP_TIMEOUT = 1_000L
        const val SETUP_TIMEOUT = 30_000L
    }
}
