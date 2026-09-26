package io.github.lesj0610.hermes.net

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * An in-process stand-in for the gateway's `/api/ws` JSON-RPC socket.
 *
 * It keeps what the tests depend on the real gateway for: a stored session's
 * runtime; one live session per conversation, which `session.resume` REUSES —
 * same id, same state, `eager_build` or not — for as long as it is alive,
 * whichever client asks (tui_gateway `_find_live_session_by_key`);
 * `session.close` tearing that live session down outright; settings that apply
 * to the live session named and persist to the stored one; a model switch that
 * re-reads the reasoning level from config.
 *
 * And the agent build, which decides what a session reports. A fresh resume
 * builds only when asked (`eager_build`); otherwise the first submit builds.
 * Until then the session reports its model and nothing of its route, and
 * `model.options` marks the profile default as current (`_fallback_session_info`,
 * `_model_picker_context`). Once built, a custom endpoint is reported under the
 * first configured row whose URL matches with the gateway's own normalisation —
 * lowercased whole, so paths differing only in case collide there — or under
 * the row an explicit switch named, or as bare `custom` when no row matches; and
 * `model.options` marks the row whose slug is that identity, by URL only for
 * bare `custom` (`canonical_custom_identity`, `endpoint_is_current`).
 *
 * Faults are injected by name: refuse a request, hold its reply, fail the
 * client's send, end the stream early, or make a stored runtime unbuildable.
 */
internal class FakeGateway {
    data class Runtime(
        var model: String,
        var endpoint: String,
        var reasoning: String,
        /** The provider the gateway reports once the agent is built. */
        var identity: String,
    ) {
        fun copyOf() = copy()
    }

    /** A live session: whose it is, what it runs, and whether its agent is built. */
    class Live(val storedId: String, val runtime: Runtime, var built: Boolean)

    data class Row(val slug: String, val apiUrl: String)
    data class Call(val connection: Int, val method: String, val params: JsonObject)

    /** How a submitted turn plays out. */
    enum class Script { Reply, EndEarly, Hold }

    val stored = mutableMapOf<String, Runtime>()
    val calls = mutableListOf<Call>()
    val connections = mutableListOf<Connection>()
    val closedLive = mutableListOf<String>()

    var rows = listOf(
        Row("custom", "http://127.0.0.1:8088/v1"),
        Row("custom:local-(127.0.0.1:8088)", "http://127.0.0.1:8088/v1/"),
        Row("custom:lab", "http://10.0.0.5:8000/v1"),
        Row("anthropic", ""),
    )
    var configReasoning = "xhigh"

    /** The profile's default provider, which an unbuilt session's `model.options` marks as current. */
    var configProvider = "custom"

    /** Stored sessions whose runtime cannot be built — a provider since removed, say. */
    val unbuildable = mutableSetOf<String>()

    var script = Script.Reply

    /** Acknowledge `prompt.submit` before its events rather than after them. */
    var submitReplyFirst = false

    /** A client's close of the socket never completes, as a stuck handshake would. */
    var hangClose = false

    /** "method" or "config.set:key" → how many times to refuse it, and with what. */
    val refuse = mutableMapOf<String, MutableList<String>>()

    /** "method" or "config.set:key" → how many of its replies to hold back. */
    val hold = mutableMapOf<String, Int>()
    private val held = mutableListOf<Pair<Connection, String>>()

    /** A request whose client-side send fails, as a socket dying at that moment would. */
    var failSendOf: String? = null

    private val live = mutableMapOf<String, Live>()
    private var nextLive = 0
    private var nextStored = 0
    private val json = Json { ignoreUnknownKeys = true }

    fun open(): FrameTransport = Connection(connections.size + 1).also { connections += it }

    fun refuseNext(name: String, message: String) {
        refuse.getOrPut(name) { mutableListOf() } += message
    }

    /** The orphan reaper's grace has passed: live sessions nobody holds are gone. */
    fun reap() {
        live.clear()
    }

    /** Another client — the desktop with the chat open — resumed [storedId] without building it. */
    fun openLive(storedId: String): String =
        "live-${++nextLive}".also { live[it] = Live(storedId, stored.getValue(storedId).copyOf(), built = false) }

    fun isBuilt(liveId: String): Boolean = live.getValue(liveId).built

    /** Sends every held reply, in the order they were held. */
    fun releaseHeld() {
        val pending = held.toList()
        held.clear()
        pending.forEach { (connection, reply) -> connection.push(reply) }
    }

    fun liveRuntime(liveId: String): Runtime? = live[liveId]?.runtime

    fun methods(connection: Int? = null) =
        calls.filter { connection == null || it.connection == connection }.map { call ->
            val key = call.params.str("key")
            if (call.method == "config.set" && key.isNotEmpty()) "config.set:$key" else call.method
        }

    inner class Connection(val id: Int) : FrameTransport {
        private val toClient = Channel<String>(Channel.UNLIMITED)
        var closed = false
            private set

        override suspend fun send(text: String) {
            if (closed) throw IOException("socket closed")
            val request = json.parseToJsonElement(text).jsonObject
            val method = request.str("method")
            if (method == failSendOf) {
                end()
                throw IOException("send failed: $method")
            }
            handle(this, request)
        }

        override suspend fun receive(): String? = toClient.receiveCatching().getOrNull()

        override suspend fun close() {
            if (hangClose) kotlinx.coroutines.awaitCancellation()
            end()
        }

        fun push(text: String) {
            if (!closed) toClient.trySend(text)
        }

        /** The socket goes away: no more frames, sends fail. */
        fun end() {
            closed = true
            toClient.close()
        }

        fun event(type: String, payload: JsonObject = buildJsonObject { }) = push(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", type)
                    put("payload", payload)
                })
            }.toString(),
        )
    }

    private fun handle(connection: Connection, request: JsonObject) {
        val id = request["id"]
        val method = request.str("method")
        val params = request["params"] as? JsonObject ?: buildJsonObject { }
        calls += Call(connection.id, method, params)
        val name = if (method == "config.set") "config.set:${params.str("key")}" else method

        fun reply(result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("result", result)
        }.toString()

        fun error(code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()

        refuse[name]?.takeIf { it.isNotEmpty() }?.let { reasons ->
            connection.push(error(4002, reasons.removeAt(0)))
            return
        }

        val answer: String? = when (method) {
            "session.resume" -> {
                val storedId = params.str("session_id")
                val runtime = stored[storedId] ?: return connection.push(error(4001, "no such session"))
                // Reuse while alive: the same id and the same state, not a copy —
                // built or not, whatever this caller asked for.
                val liveId = live.entries.firstOrNull { it.value.storedId == storedId }?.key ?: run {
                    val eager = params.flag("eager_build")
                    if (eager && storedId in unbuildable) {
                        return connection.push(error(5000, "resume failed: provider is not configured"))
                    }
                    "live-${++nextLive}".also { live[it] = Live(storedId, runtime.copyOf(), built = eager) }
                }
                reply(session(liveId, live.getValue(liveId)))
            }
            "session.create" -> {
                val storedId = "new-${++nextStored}"
                val provider = params.str("provider")
                val endpoint = endpointOf(provider.ifEmpty { configProvider })
                val runtime = Runtime(
                    model = params.str("model").ifEmpty { "Qwen" },
                    endpoint = endpoint,
                    reasoning = params.str("reasoning_effort").ifEmpty { configReasoning },
                    identity = provider.ifEmpty { configProvider }.takeUnless { it == "custom" } ?: identityOf(endpoint),
                )
                stored[storedId] = runtime
                val liveId = "live-${++nextLive}"
                live[liveId] = Live(storedId, runtime.copyOf(), built = false)
                // As the gateway does: the pick is echoed back before any build.
                reply(buildJsonObject {
                    put("session_id", liveId)
                    put("info", buildJsonObject {
                        put("model", runtime.model)
                        if (provider.isNotEmpty()) put("provider", provider)
                    })
                })
            }
            "config.set" -> configSet(params, ::reply, ::error)
            "model.options" -> {
                val session = live[params.str("session_id")]
                    ?: return connection.push(error(4001, "no such session"))
                val identity = if (session.built) session.runtime.identity else configProvider
                val endpoint = if (session.built) session.runtime.endpoint else endpointOf(configProvider)
                val current = rows.firstOrNull { row ->
                    row.slug == identity ||
                        (identity == "custom" && row.apiUrl.isNotEmpty() && normal(row.apiUrl) == normal(endpoint))
                }
                reply(buildJsonObject {
                    put("providers", buildJsonArray {
                        rows.forEach { row ->
                            add(buildJsonObject {
                                put("slug", row.slug)
                                put("api_url", row.apiUrl)
                                put("is_current", row === current)
                            })
                        }
                    })
                })
            }
            "image.attach_bytes", "approval.respond" -> reply(buildJsonObject { })
            "session.close" -> {
                closedLive += params.str("session_id")
                live.remove(params.str("session_id"))
                reply(buildJsonObject { })
            }
            "session.interrupt" -> {
                connection.event("message.complete", buildJsonObject { put("status", "interrupted") })
                reply(buildJsonObject { put("status", "interrupted") })
            }
            "prompt.submit" -> {
                // The first turn builds whatever the session was left with.
                live[params.str("session_id")]?.built = true
                val ack = reply(buildJsonObject { })
                if (submitReplyFirst) connection.push(ack)
                when (script) {
                    Script.Reply -> {
                        connection.event("message.delta", buildJsonObject { put("text", "안녕하세요. ") })
                        connection.event("message.delta", buildJsonObject { put("text", "반갑습니다.") })
                        connection.event("message.complete", buildJsonObject { put("status", "complete") })
                    }
                    Script.EndEarly -> {
                        connection.event("message.delta", buildJsonObject { put("text", "안녕") })
                        connection.end()
                    }
                    Script.Hold -> Unit
                }
                if (submitReplyFirst) null else ack
            }
            else -> reply(buildJsonObject { })
        }
        if (answer == null) return
        val holding = hold[name] ?: 0
        if (holding > 0) {
            hold[name] = holding - 1
            held += connection to answer
        } else {
            connection.push(answer)
        }
    }

    private fun configSet(
        params: JsonObject,
        reply: (JsonObject) -> String,
        error: (Int, String) -> String,
    ): String {
        val liveId = params.str("session_id")
        if (liveId.isEmpty()) throw AssertionError("config.set without a session writes config.yaml")
        val session = live[liveId] ?: return error(4001, "session not found")
        val runtime = session.runtime
        val value = params.str("value")
        when (params.str("key")) {
            "model" -> {
                // Unbuilt, the switch waits for the build; either way it is what the turn runs.
                val provider = value.substringAfter("--provider ", "").substringBefore(" --")
                runtime.model = value.substringBefore(" --")
                runtime.endpoint = endpointOf(provider)
                // An explicit pick is reported as named.
                runtime.identity = provider.takeUnless { it == "custom" } ?: identityOf(runtime.endpoint)
                // As the gateway does: a switch re-reads the level from config.
                runtime.reasoning = configReasoning
            }
            "reasoning" -> runtime.reasoning = value
        }
        // Persisted to the stored row, which the next resume reads.
        stored[session.storedId] = runtime.copyOf()
        return reply(buildJsonObject { put("value", value) })
    }

    private fun session(liveId: String, session: Live) = buildJsonObject {
        put("session_id", liveId)
        put("info", buildJsonObject {
            put("model", session.runtime.model)
            // Unbuilt, the gateway knows the model it will build and nothing of the route.
            if (session.built) {
                put("provider", session.runtime.identity)
                put("reasoning_effort", session.runtime.reasoning)
            }
        })
    }

    /** The URL of the row [provider] names, as configured. */
    fun endpointOf(provider: String): String = rows.firstOrNull { it.slug == provider }?.apiUrl?.trim().orEmpty()

    /** The identity a built agent on [endpoint] reports: the first named row whose URL matches, else bare `custom`. */
    fun identityOf(endpoint: String): String =
        rows.firstOrNull { it.slug.startsWith("custom:") && normal(it.apiUrl) == normal(endpoint) }?.slug ?: "custom"

    /** The gateway's own URL match: trailing slash dropped, then everything lowercased. */
    private fun normal(url: String) = url.trim().trimEnd('/').lowercase()

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
}
