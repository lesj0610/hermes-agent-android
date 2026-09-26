package io.github.lesj0610.hermes.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bringing a live session to the runtime a turn asked for.
 *
 * The fake below plays the gateway: each stored session keeps its own model and
 * reasoning, `session.resume` reports them under a fresh live id, and
 * `config.set` changes only the session whose live id it names — the property
 * that keeps two conversations from bleeding into each other.
 */
class SocketTurnSetupTest {

    private class Gateway {
        data class Runtime(var model: String, var provider: String, var reasoning: String)

        val stored = mutableMapOf<String, Runtime>()
        val live = mutableMapOf<String, String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var nextLive = 0
        var refuseModel: String? = null
        var confirmModel = false

        val rpc = RpcCaller { method, params -> handle(method, params) }

        fun handle(method: String, params: JsonObject): JsonElement {
            calls += method to params
            return when (method) {
                "session.resume" -> {
                    val id = params.field("session_id")
                    val runtime = stored.getValue(id)
                    val liveId = "live-${++nextLive}"
                    live[liveId] = id
                    session(liveId, runtime)
                }
                "session.create" -> {
                    val id = "new-${++nextLive}"
                    val runtime = Runtime(
                        params.field("model").ifEmpty { "default" },
                        params.field("provider").ifEmpty { "custom" },
                        params.field("reasoning_effort"),
                    )
                    stored[id] = runtime
                    val liveId = "live-$nextLive"
                    live[liveId] = id
                    session(liveId, runtime)
                }
                "config.set" -> {
                    val liveId = params.field("session_id")
                    if (liveId.isEmpty()) fail("config.set without a session writes config.yaml")
                    val runtime = stored.getValue(live.getValue(liveId))
                    when (params.field("key")) {
                        "model" -> {
                            if (confirmModel) {
                                return buildJsonObject {
                                    put("confirm_required", true)
                                    put("confirm_message", "비싼 모델입니다")
                                }
                            }
                            val value = params.field("value")
                            if (refuseModel != null) throw GatewayRpcException(4002, refuseModel!!)
                            runtime.model = value.substringBefore(" --")
                            runtime.provider = value.substringAfter("--provider ", "").substringBefore(" --")
                            // As the gateway does: a switch re-reads the level from config.yaml.
                            runtime.reasoning = CONFIG_REASONING
                        }
                        "reasoning" -> runtime.reasoning = params.field("value")
                    }
                    buildJsonObject { put("value", params.field("value")) }
                }
                else -> buildJsonObject { }
            }
        }

        private fun session(liveId: String, runtime: Runtime) = buildJsonObject {
            put("session_id", liveId)
            put(
                "info",
                buildJsonObject {
                    put("model", runtime.model)
                    put("provider", runtime.provider)
                    put("reasoning_effort", runtime.reasoning)
                },
            )
        }

        fun methods() = calls.map { it.first }
    }

    @Test
    fun `settings the session already has cost nothing`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "xhigh") }
        val prepared = prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("Qwen", "openrouter", "xhigh"))
        assertEquals(PreparedTurn("live-1", null), prepared)
        assertEquals(listOf("session.resume"), gateway.methods())
    }

    @Test
    fun `a different model and level are applied to the live id, model first`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "xhigh") }
        prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("claude-opus-5", "anthropic", "low"))
        assertEquals(listOf("session.resume", "config.set", "config.set"), gateway.methods())
        val (_, model) = gateway.calls[1]
        assertEquals("live-1", model.field("session_id"))
        assertEquals("model", model.field("key"))
        assertEquals("claude-opus-5 --provider anthropic --session", model.field("value"))
        assertEquals("low", gateway.calls[2].second.field("value"))
        assertEquals(Gateway.Runtime("claude-opus-5", "anthropic", "low"), gateway.stored["s1"])
    }

    @Test
    fun `after a model switch the level is applied even if it matched before`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "low") }
        prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("claude-opus-5", "anthropic", "low"))
        assertEquals(listOf("session.resume", "config.set", "config.set"), gateway.methods())
        assertEquals("low", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `a spoken turn that switched model restores the app's level, not the stale one`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "low") }
        val spoken = prepareSocketTurn(
            gateway.rpc, "s1", TurnRuntime("claude-opus-5", "anthropic", "none"), VoiceTurn(restoreReasoning = "medium"),
        )
        assertEquals("none", gateway.stored.getValue("s1").reasoning)
        assertEquals("medium", spoken.restoreReasoning)
    }

    @Test
    fun `a refused setting ends the turn instead of running on the old model`() = runBlocking {
        val gateway = Gateway().apply {
            stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "xhigh")
            refuseModel = "unknown model"
        }
        try {
            prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("nope", "openrouter", "low"))
            fail("expected the refusal to propagate")
        } catch (expected: GatewayRpcException) {
            assertEquals("unknown model", expected.message)
        }
        // Nothing after the refusal: no level change on a turn that will not run.
        assertEquals(listOf("session.resume", "config.set"), gateway.methods())
    }

    @Test
    fun `a model that needs confirmation is refused with the gateway's reason`() = runBlocking {
        val gateway = Gateway().apply {
            stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "xhigh")
            confirmModel = true
        }
        try {
            prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("gpt-6-sol", "openai-codex", "xhigh"))
            fail("expected a confirmation refusal")
        } catch (expected: TurnSetupException) {
            assertEquals("비싼 모델입니다", expected.message)
        }
    }

    @Test
    fun `a new session takes the runtime at creation`() = runBlocking {
        val gateway = Gateway()
        val prepared = prepareSocketTurn(gateway.rpc, null, TurnRuntime("Qwen", "openrouter", "medium"))
        assertEquals("live-1", prepared.liveId)
        assertEquals(listOf("session.create"), gateway.methods())
        val create = gateway.calls[0].second
        assertEquals("Qwen", create.field("model"))
        assertEquals("openrouter", create.field("provider"))
        assertEquals("medium", create.field("reasoning_effort"))
    }

    @Test
    fun `typed, spoken, typed — the spoken level never outlives its turn`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "xhigh") }
        val typed = TurnRuntime("Qwen", "openrouter", "xhigh")

        assertNull(prepareSocketTurn(gateway.rpc, "s1", typed).restoreReasoning)

        val spoken = prepareSocketTurn(
            gateway.rpc, "s1", typed.copy(reasoning = "none"), VoiceTurn(restoreReasoning = "xhigh"),
        )
        assertEquals("none", gateway.stored.getValue("s1").reasoning)
        assertEquals("xhigh", spoken.restoreReasoning)
        // SocketRun.close sends the restore on the same live id.
        gateway.handle("config.set", buildJsonObject {
            put("session_id", spoken.liveId); put("key", "reasoning"); put("value", spoken.restoreReasoning!!)
        })

        gateway.calls.clear()
        assertNull(prepareSocketTurn(gateway.rpc, "s1", typed).restoreReasoning)
        assertEquals(listOf("session.resume"), gateway.methods())
        assertEquals("xhigh", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `a restore that never arrived is repaired by the next typed turn`() = runBlocking {
        // The socket died before the spoken turn could put its level back.
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "none") }
        prepareSocketTurn(gateway.rpc, "s1", TurnRuntime("Qwen", "openrouter", "xhigh"))
        assertEquals("xhigh", gateway.stored.getValue("s1").reasoning)
    }

    @Test
    fun `a spoken turn with no reported level restores the app's own`() = runBlocking {
        val gateway = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "") }
        val spoken = prepareSocketTurn(
            gateway.rpc, "s1", TurnRuntime(reasoning = "none"), VoiceTurn(restoreReasoning = "medium"),
        )
        assertEquals("medium", spoken.restoreReasoning)

        // Left at "none" by a lost restore: back to the app's level, not to "none".
        val left = Gateway().apply { stored["s1"] = Gateway.Runtime("Qwen", "openrouter", "none") }
        val again = prepareSocketTurn(
            left.rpc, "s1", TurnRuntime(reasoning = "none"), VoiceTurn(restoreReasoning = "medium"),
        )
        assertEquals("medium", again.restoreReasoning)
    }

    @Test
    fun `two sessions are set through their own live ids`() = runBlocking {
        val gateway = Gateway().apply {
            stored["a"] = Gateway.Runtime("Qwen", "openrouter", "xhigh")
            stored["b"] = Gateway.Runtime("Qwen", "openrouter", "xhigh")
        }
        prepareSocketTurn(gateway.rpc, "a", TurnRuntime(reasoning = "none"), VoiceTurn(restoreReasoning = "xhigh"))
        prepareSocketTurn(gateway.rpc, "b", TurnRuntime(reasoning = "low"))
        assertEquals("none", gateway.stored.getValue("a").reasoning)
        assertEquals("low", gateway.stored.getValue("b").reasoning)
        val targets = gateway.calls.filter { it.first == "config.set" }.map { it.second.field("session_id") }
        assertEquals(listOf("live-1", "live-2"), targets)
    }

    @Test
    fun `no live id is a failure, not a sessionless setting`() = runBlocking {
        val rpc = RpcCaller { method, _ ->
            if (method == "config.set") fail("must not set anything without a live id")
            buildJsonObject { put("session_id", "") }
        }
        try {
            prepareSocketTurn(rpc, "s1", TurnRuntime(reasoning = "none"))
            fail("expected a failure")
        } catch (expected: GatewayRpcException) {
            assertTrue(expected.message.orEmpty().contains("live session"))
        }
    }

    @Test
    fun `a spoken turn is submitted on the voice surface with its context`() {
        val spoken = promptSubmitParams("live-1", "안녕", VoiceTurn(context = "Delivery: …\nUser: 전"))
        assertEquals("voice-live", spoken.field("surface"))
        assertEquals("Delivery: …\nUser: 전", spoken.field("voice_context"))
        val typed = promptSubmitParams("live-1", "안녕", null)
        assertEquals(setOf("session_id", "text"), typed.keys)
    }
}

private fun JsonObject.field(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

private const val CONFIG_REASONING = "xhigh"
