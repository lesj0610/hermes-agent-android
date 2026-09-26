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
import kotlinx.coroutines.test.advanceTimeBy
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
 *
 * Once a conversation is held, the engine looks at the gateway on a schedule
 * for as long as the hold lasts, so those tests step time explicitly rather
 * than running it out with advanceUntilIdle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunEngineSocketTest {

    private val gateway = FakeGateway().apply {
        stored["s1"] = FakeGateway.Runtime("Qwen", endpointOf("custom"), "xhigh", identityOf(endpointOf("custom")))
    }

    /** A second gateway, for a dashboard setting changed to point elsewhere. */
    private val other = FakeGateway()

    /** The dashboard address the settings hold right now. */
    private var dashboardUrl = GATEWAY_A
    private val scopes = mutableListOf<CoroutineScope>()

    private fun gatewayAt(base: String) = when (base) {
        GATEWAY_A -> gateway
        GATEWAY_B -> other
        else -> error("no gateway at $base")
    }

    private fun engine(scope: CoroutineScope, holdStore: HoldStore): RunEngine {
        val dashboard = DashboardApi(
            { dashboardUrl }, { "u" to "p" }, { base -> gatewayAt(base).open() }, CLEANUP_TIMEOUT, SETUP_TIMEOUT,
        )
        // The HTTP route is never meant to be reached here. Failing before any
        // I/O keeps reopening a session inside virtual time: waiting on a real
        // socket, runTest would skip time ahead and fire every pending timer.
        val api = HermesApi({ throw java.io.IOException("no HTTP route in these tests") }, { "" })
        return RunEngine(api, scope, dashboard, socketEnabled = { true }, holdStore = holdStore)
    }

    // Not backgroundScope: advanceUntilIdle does not run background work on its
    // own, and the engine's turns are exactly the work these tests wait on. A
    // dispatcher on the test's scheduler keeps virtual time; the scope is
    // cancelled after each test so a turn left waiting does not outlive it.
    private suspend fun TestScope.opened(holdStore: HoldStore = HoldStore.InMemory()): RunEngine {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return engine(scope, holdStore).apply {
            openSession("s1")
            clearError()
        }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /**
     * runTest, with the engines stopped before it winds down: runTest runs the
     * scheduler out once the body is done, and an engine still looking at a
     * held conversation on a schedule would never let it.
     */
    private fun engineTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    private val spoken = VoiceTurn(context = "Delivery: test", restoreReasoning = "xhigh")

    private fun complete(connection: Int) {
        gateway.connections[connection - 1].event("message.delta", buildJsonObject { put("text", "답.") })
        gateway.connections[connection - 1].event("message.complete", buildJsonObject { put("status", "complete") })
    }

    /** Completes the turn submitted last; looks at the live list take connections of their own, so its number varies. */
    private fun completeLatest() = complete(gateway.calls.last { it.method == "prompt.submit" }.connection)

    private fun resumes() = gateway.calls.count { it.method == "session.resume" }

    /** Requests that act on a conversation, leaving out looks at the live list. */
    private fun acting() = gateway.calls.filterNot { it.method == "session.active_list" }

    /**
     * A spoken turn on s1 whose restore the gateway gets to only later — or,
     * with [dropSocket], whose socket then goes away before any answer.
     */
    private fun TestScope.spokenTurnWithDelayedRestore(engine: RunEngine, dropSocket: Boolean = false) {
        gateway.script = FakeGateway.Script.Hold
        engine.send("첫 번째", null, null, "none", voice = spoken)!!
        runCurrent()
        gateway.delayApply["config.set:reasoning"] = 1
        complete(1)
        runCurrent()
        if (dropSocket) {
            gateway.connections[0].end()
            runCurrent()
        } else {
            advanceTimeBy(CLEANUP_TIMEOUT * 3)
            runCurrent()
        }
    }

    @Test
    fun `a typed turn completes, ends once, and leaves the live session to the gateway`() = engineTest {
        val engine = opened()
        val turn = engine.send("안녕", null, null, "xhigh")!!
        advanceUntilIdle()
        val state = engine.state.value
        assertEquals(RunPhase.Idle, state.phase)
        assertEquals(turn, state.endedTurn)
        assertEquals("안녕하세요. 반갑습니다.", (state.items.last() as TranscriptItem.AssistantText).text)
        assertNull(state.error)
        assertNull(state.warning)
        assertNull(state.held)
        assertTrue(gateway.closedLive.isEmpty())
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun `a stream that ends early fails the turn exactly once`() = engineTest {
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
        // The socket was gone before the restore could go out: known not done,
        // and nothing left that could still land, so nothing is held.
        assertEquals(UiError.ReasoningNotRestored, state.warning)
        assertNull(state.held)
        assertNull(state.error)
    }

    @Test
    fun `a refused submit on a socket that stays open ends the turn once, with the reason`() = engineTest {
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
    fun `a refused setting ends the turn with the gateway's reason`() = engineTest {
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
    fun `the next turn waits for the previous restore on the shared live session`() = engineTest {
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
        assertNull(engine.state.value.held)
    }

    // ── a change left unanswered ─────────────────────────────────────────

    @Test
    fun `a restore the gateway applies late cannot land over the next turn`() = engineTest {
        val engine = opened()
        // 1. The gateway gets to turn 1's restore only later: not applied, not answered.
        // 2. The app's wait for it times out.
        spokenTurnWithDelayedRestore(engine)
        val held = engine.state.value.held!!
        assertEquals("live-1", held.liveId)
        assertTrue(held.awaitingAnswer)
        // The socket it went out on is kept, since only it can carry the answer.
        assertFalse(gateway.connections[0].closed)

        // 3. The next turn is refused, and nothing of it reaches the gateway.
        val before = gateway.calls.size
        assertNull(engine.send("두 번째", null, null, "low"))
        runCurrent()
        assertEquals(before, acting().size)

        // 4. The gateway applies the old restore now, and answers on the kept socket.
        gateway.applyDelayed()
        runCurrent()
        assertEquals("xhigh", gateway.liveRuntime("live-1")!!.reasoning)
        assertNull(engine.state.value.held)
        assertTrue(gateway.connections[0].closed)

        // Only now does the next turn run, at its own level, and nothing is left to overwrite it.
        engine.send("두 번째", null, null, "low")!!
        runCurrent()
        completeLatest()
        runCurrent()
        assertEquals("low", gateway.liveRuntime("live-1")!!.reasoning)
    }

    @Test
    fun `time alone never releases a hold`() = engineTest {
        // The desktop keeps the conversation open, so its live session never ends.
        gateway.heldElsewhere += "live-1"
        val engine = opened()
        spokenTurnWithDelayedRestore(engine)
        val before = acting().size

        // Past giving up on the answer, and many looks at the gateway after it.
        advanceTimeBy(30 * 60 * 1_000L)
        runCurrent()
        val held = engine.state.value.held!!
        assertFalse(held.awaitingAnswer)
        assertEquals(HoldCheck.StillOpen, held.check)
        assertTrue(gateway.connections[0].closed)
        assertTrue(gateway.calls.count { it.method == "session.active_list" } >= 3)

        // Sending is refused, so no setting goes out either: nothing re-sent releases it.
        assertNull(engine.send("두 번째", null, null, "low"))
        runCurrent()
        assertEquals(before, acting().size)
        assertEquals(HoldCheck.StillOpen, engine.state.value.held!!.check)
    }

    @Test
    fun `a socket lost before the answer holds the conversation until its live session is gone`() = engineTest {
        val engine = opened()
        spokenTurnWithDelayedRestore(engine, dropSocket = true)
        val held = engine.state.value.held!!
        assertFalse(held.awaitingAnswer)

        // Still listed: still held.
        advanceTimeBy(HOLD_FIRST_CHECK_MILLIS + 1)
        runCurrent()
        assertEquals(HoldCheck.StillOpen, engine.state.value.held!!.check)

        // The gateway lets the session go; the next look releases the conversation.
        gateway.reap()
        advanceTimeBy(HOLD_FIRST_CHECK_MILLIS * 2 + 1)
        runCurrent()
        assertNull(engine.state.value.held)

        // The next turn is handed a new live session, and the old restore,
        // arriving at last, finds nothing to land on.
        gateway.script = FakeGateway.Script.Reply
        engine.send("두 번째", null, null, "low")!!
        runCurrent()
        gateway.applyDelayed()
        runCurrent()
        assertNull(gateway.liveRuntime("live-1"))
        assertEquals("low", gateway.liveRuntime("live-2")!!.reasoning)
        assertNull(engine.state.value.error)
    }

    @Test
    fun `a look that cannot reach the gateway releases nothing`() = engineTest {
        val engine = opened()
        spokenTurnWithDelayedRestore(engine, dropSocket = true)
        gateway.reap()
        gateway.refuseNext("session.active_list", "unavailable")
        engine.recheckHold()
        runCurrent()
        assertEquals(HoldCheck.Unreachable, engine.state.value.held!!.check)
        // The next look that does reach it finds the session gone.
        engine.recheckHold()
        runCurrent()
        assertNull(engine.state.value.held)
    }

    @Test
    fun `another conversation stays usable while one is held`() = engineTest {
        gateway.stored["s2"] = FakeGateway.Runtime("Qwen", gateway.endpointOf("custom"), "xhigh", "custom:local-(127.0.0.1:8088)")
        val engine = opened()
        spokenTurnWithDelayedRestore(engine)
        assertTrue(engine.state.value.held != null)

        engine.openSession("s2")
        runCurrent()
        assertNull(engine.state.value.held)
        gateway.script = FakeGateway.Script.Reply
        val turn = engine.send("다른 대화", null, null, "low")!!
        runCurrent()
        assertEquals(turn, engine.state.value.endedTurn)
        assertNull(engine.state.value.error)

        engine.openSession("s1")
        runCurrent()
        assertTrue(engine.state.value.held!!.awaitingAnswer)
    }

    @Test
    fun `a turn already waiting behind the cleanup sees the hold it leaves`() = engineTest {
        gateway.script = FakeGateway.Script.Hold
        val engine = opened()
        engine.send("첫 번째", null, null, "none", voice = spoken)!!
        runCurrent()
        gateway.delayApply["config.set:reasoning"] = 1
        complete(1)
        runCurrent()
        // Sent while turn 1 is still waiting on its restore, before any hold exists.
        val second = engine.send("두 번째", null, null, "low")!!
        val before = acting().size
        advanceTimeBy(CLEANUP_TIMEOUT * 3)
        runCurrent()
        val state = engine.state.value
        assertEquals(second, state.endedTurn)
        assertEquals(UiError.ConversationHeld, state.error)
        assertEquals(before, acting().size)
        assertEquals("none", gateway.liveRuntime("live-1")!!.reasoning)
    }

    @Test
    fun `a hold outlives the engine that set it`() = engineTest {
        val store = HoldStore.InMemory()
        val first = opened(store)
        spokenTurnWithDelayedRestore(first, dropSocket = true)
        assertEquals(listOf(StoredHold(GATEWAY_A, "s1", "live-1")), store.load())
        scopes.forEach { it.cancel() }

        // The app starts again: the conversation is still held.
        val again = opened(store)
        runCurrent()
        assertEquals("live-1", again.state.value.held!!.liveId)
        assertNull(again.send("두 번째", null, null, "low"))

        gateway.reap()
        again.recheckHold()
        runCurrent()
        assertNull(again.state.value.held)
        assertTrue(store.load().isEmpty())
    }

    // ── a new conversation ───────────────────────────────────────────────

    @Test
    fun `a new conversation's first turn creates it, and the next resumes it`() = engineTest {
        val engine = opened()
        engine.openSession(null)
        engine.send("첫 번째", null, null, "xhigh")!!
        runCurrent()
        assertEquals("new-1", engine.state.value.sessionId)

        engine.send("두 번째", null, null, "xhigh")!!
        runCurrent()
        assertEquals(
            listOf("session.create", "session.resume"),
            gateway.methods().filter { it == "session.create" || it == "session.resume" },
        )
        assertEquals("new-1", (gateway.calls.single { it.method == "session.resume" }.params["session_id"] as JsonPrimitive).content)
        assertEquals("live-1", gateway.calls.last { it.method == "prompt.submit" }.params.let { (it["session_id"] as JsonPrimitive).content })
    }

    @Test
    fun `a created conversation whose setting goes unanswered is held on the screen it was made on`() = engineTest {
        val engine = opened()
        engine.openSession(null)
        gateway.hold["config.set:reasoning"] = 1
        engine.send("첫 번째", null, null, "low")!!
        runCurrent()
        // Handed over the moment it was created, before the setting failed.
        assertEquals("new-1", engine.state.value.sessionId)

        advanceTimeBy(SETUP_TIMEOUT + 1)
        runCurrent()
        val held = engine.state.value.held!!
        assertEquals("live-1", held.liveId)
        assertTrue(held.awaitingAnswer)
        val before = acting().size
        assertNull(engine.send("두 번째", null, null, "low"))
        runCurrent()
        assertEquals(before, acting().size)
    }

    @Test
    fun `a created conversation's late restore holds the screen it was made on`() = engineTest {
        val engine = opened()
        engine.openSession(null)
        gateway.script = FakeGateway.Script.Hold
        engine.send("첫 번째", null, null, "none", voice = spoken)!!
        runCurrent()
        assertEquals("new-1", engine.state.value.sessionId)
        gateway.delayApply["config.set:reasoning"] = 1
        completeLatest()
        runCurrent()
        advanceTimeBy(CLEANUP_TIMEOUT * 3)
        runCurrent()
        assertTrue(engine.state.value.held!!.awaitingAnswer)
        assertNull(engine.send("두 번째", null, null, "low"))
    }

    @Test
    fun `a creation answered after the user moved on leaves the new screen alone`() = engineTest {
        val engine = opened()
        engine.openSession(null)
        gateway.hold["session.create"] = 1
        engine.send("첫 번째", null, null, "xhigh")!!
        runCurrent()
        engine.openSession("s1")
        runCurrent()
        gateway.releaseHeld()
        runCurrent()
        val state = engine.state.value
        assertEquals("s1", state.sessionId)
        assertNull(state.held)
        assertTrue(state.items.none { it is TranscriptItem.UserText })
    }

    @Test
    fun `a stored id is handed only to the conversation that was open when its turn was sent`() = engineTest {
        // Conversations are counted as they open: s1 was the first, then two new ones.
        val engine = opened()
        engine.openSession(null)
        engine.openSession(null)
        engine.adopt(conversation = 2, gateway = GATEWAY_A, storedId = "new-9")
        assertNull(engine.state.value.sessionId)
        engine.adopt(conversation = 3, gateway = GATEWAY_A, storedId = "new-9")
        assertEquals("new-9", engine.state.value.sessionId)
        // Never over an id it already has.
        engine.adopt(conversation = 3, gateway = GATEWAY_A, storedId = "new-10")
        assertEquals("new-9", engine.state.value.sessionId)
    }

    // ── holds and gateways ───────────────────────────────────────────────

    @Test
    fun `a hold on one gateway survives switching to another and back, across a restart`() = engineTest {
        val store = HoldStore.InMemory()
        val engine = opened(store)
        spokenTurnWithDelayedRestore(engine, dropSocket = true)

        // The dashboard now points at another gateway, which never had that live session.
        dashboardUrl = GATEWAY_B
        engine.recheckHold()
        advanceTimeBy(30 * 60 * 1_000L)
        runCurrent()
        assertEquals(HoldCheck.OtherServer, engine.state.value.held!!.check)
        assertTrue(other.calls.isEmpty())
        assertEquals(listOf(StoredHold(GATEWAY_A, "s1", "live-1")), store.load())

        // Back to the first gateway, and the app starts again: still held, still listed there.
        dashboardUrl = GATEWAY_A
        scopes.forEach { it.cancel() }
        val again = opened(store)
        runCurrent()
        assertEquals(HoldCheck.StillOpen, again.state.value.held!!.check)
        assertNull(again.send("두 번째", null, null, "low"))

        // Its own gateway letting the session go is what releases it.
        gateway.reap()
        again.recheckHold()
        runCurrent()
        assertNull(again.state.value.held)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a change of server during a look does not release the hold`() = engineTest {
        val engine = opened()
        spokenTurnWithDelayedRestore(engine, dropSocket = true)
        gateway.hold["session.active_list"] = 1
        engine.recheckHold()
        runCurrent()
        // The look is out, waiting on the first gateway, when the settings change.
        dashboardUrl = GATEWAY_B
        gateway.releaseHeld()
        runCurrent()
        assertEquals(HoldCheck.StillOpen, engine.state.value.held!!.check)
        assertTrue(other.calls.isEmpty())
    }

    @Test
    fun `a hold recorded without its server is kept until found there, then released there`() = engineTest {
        val store = HoldStore.InMemory(listOf(StoredHold(null, "s1", "live-1")))
        val engine = opened(store)
        runCurrent()
        // Not on this gateway's list: no proof, since it may be another gateway's.
        assertEquals(HoldCheck.UnknownServer, engine.state.value.held!!.check)
        assertNull(engine.send("두 번째", null, null, "low"))
        runCurrent()
        assertEquals(listOf(StoredHold(null, "s1", "live-1")), store.load())

        // Found on this gateway, for this conversation: this gateway's from now on.
        gateway.openLive("s1")
        engine.recheckHold()
        runCurrent()
        assertEquals(listOf(StoredHold(GATEWAY_A, "s1", "live-1")), store.load())
        assertEquals(HoldCheck.StillOpen, engine.state.value.held!!.check)

        gateway.reap()
        engine.recheckHold()
        runCurrent()
        assertNull(engine.state.value.held)
    }

    // ── stopping and switching ───────────────────────────────────────────

    @Test
    fun `a refused stop leaves the turn running, not stopping`() = engineTest {
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
    fun `stop during setup ends the turn quietly and still cleans up`() = engineTest {
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
        // The first change's answer is still out, so the conversation waits for it.
        assertTrue(state.held!!.awaitingAnswer)
        gateway.releaseHeld()
        runCurrent()
        assertNull(engine.state.value.held)
    }

    @Test
    fun `a second message during setup is refused rather than racing the first`() = engineTest {
        gateway.hold["session.resume"] = 1
        val engine = opened()
        assertTrue(engine.send("첫 번째", null, null, "xhigh") != null)
        runCurrent()
        assertNull(engine.send("두 번째", null, null, "xhigh"))
        assertEquals(1, engine.state.value.items.count { it is TranscriptItem.UserText })
    }

    @Test
    fun `events from an ended turn cannot touch the current one`() = engineTest {
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
    fun `switching session mid-setup still puts the level back`() = engineTest {
        gateway.hold["config.set:reasoning"] = 1
        val engine = opened()
        engine.send("안녕", null, null, "none", voice = spoken)
        runCurrent()
        assertEquals(listOf("session.resume", "config.set:reasoning"), gateway.methods())

        engine.openSession("s1")
        runCurrent()
        // Planned before the change went out, so put back even though the
        // change's own answer never arrived — and that answer is still awaited.
        assertEquals(
            listOf("session.resume", "config.set:reasoning", "config.set:reasoning"),
            gateway.methods().filterNot { it == "session.active_list" },
        )
        assertTrue(engine.state.value.held!!.awaitingAnswer)
        gateway.releaseHeld()
        runCurrent()
        assertNull(engine.state.value.held)
        assertTrue(gateway.connections[0].closed)
    }

    private companion object {
        const val CLEANUP_TIMEOUT = 1_000L
        const val SETUP_TIMEOUT = 30_000L
        const val GATEWAY_A = "http://gw-a.invalid"
        const val GATEWAY_B = "http://gw-b.invalid"
    }
}
