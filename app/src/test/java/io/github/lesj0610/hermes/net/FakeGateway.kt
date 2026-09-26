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
 * runtime, a live session per resume holding its own copy, settings that apply
 * to the live session named and persist to the stored one, a model switch that
 * re-reads the reasoning level from config, custom endpoints reported under a
 * canonical identity (`custom:custom`) that does not match the picker's slugs,
 * and `model.options` rows carrying each endpoint's URL with the current one
 * marked by URL. Faults are injected by name: refuse a request, hold its reply,
 * fail the client's send, or end the stream early.
 */
internal class FakeGateway {
    data class Runtime(
        var model: String,
        var endpoint: String,
        var reasoning: String,
        /** The identity the gateway reports; for custom endpoints, canonicalised. */
        var identity: String,
    ) {
        fun copyOf() = copy()
    }

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
    var script = Script.Reply

    /** "method" or "config.set:key" → how many times to refuse it, and with what. */
    val refuse = mutableMapOf<String, MutableList<String>>()

    /** "method" or "config.set:key" → how many of its replies to hold back. */
    val hold = mutableMapOf<String, Int>()
    private val held = mutableListOf<Pair<Connection, String>>()

    /** A request whose client-side send fails, as a socket dying at that moment would. */
    var failSendOf: String? = null

    private val live = mutableMapOf<String, Pair<String, Runtime>>()
    private var nextLive = 0
    private var nextStored = 0
    private val json = Json { ignoreUnknownKeys = true }

    fun open(): FrameTransport = Connection(connections.size + 1).also { connections += it }

    fun refuseNext(name: String, message: String) {
        refuse.getOrPut(name) { mutableListOf() } += message
    }

    /** Sends every held reply, in the order they were held. */
    fun releaseHeld() {
        val pending = held.toList()
        held.clear()
        pending.forEach { (connection, reply) -> connection.push(reply) }
    }

    fun liveRuntime(liveId: String): Runtime? = live[liveId]?.second

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
                val liveId = "live-${++nextLive}"
                live[liveId] = storedId to runtime.copyOf()
                reply(session(liveId, runtime))
            }
            "session.create" -> {
                val storedId = "new-${++nextStored}"
                val provider = params.str("provider")
                val runtime = Runtime(
                    model = params.str("model").ifEmpty { "Qwen" },
                    endpoint = endpointOf(provider.ifEmpty { "custom" }),
                    reasoning = params.str("reasoning_effort"),
                    identity = identityOf(provider.ifEmpty { "custom" }),
                )
                stored[storedId] = runtime
                val liveId = "live-${++nextLive}"
                live[liveId] = storedId to runtime.copyOf()
                reply(session(liveId, runtime))
            }
            "config.set" -> configSet(params, ::reply, ::error)
            "model.options" -> {
                val runtime = live[params.str("session_id")]?.second
                    ?: return connection.push(error(4001, "no such session"))
                val current = rows.firstOrNull { normal(it.apiUrl) == normal(runtime.endpoint) && it.apiUrl.isNotEmpty() }
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
                reply(buildJsonObject { })
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
        val (storedId, runtime) = live[liveId] ?: return error(4001, "session not found")
        val value = params.str("value")
        when (params.str("key")) {
            "model" -> {
                val provider = value.substringAfter("--provider ", "").substringBefore(" --")
                runtime.model = value.substringBefore(" --")
                runtime.endpoint = endpointOf(provider)
                runtime.identity = identityOf(provider)
                // As the gateway does: a switch re-reads the level from config.
                runtime.reasoning = configReasoning
            }
            "reasoning" -> runtime.reasoning = value
        }
        // Persisted to the stored row, which the next resume reads.
        stored[storedId] = runtime.copyOf()
        return reply(buildJsonObject { put("value", value) })
    }

    private fun session(liveId: String, runtime: Runtime) = buildJsonObject {
        put("session_id", liveId)
        put("info", buildJsonObject {
            put("model", runtime.model)
            put("provider", runtime.identity)
            put("reasoning_effort", runtime.reasoning)
        })
    }

    fun endpointOf(provider: String): String =
        rows.firstOrNull { it.slug == provider }?.apiUrl?.let(::normal).orEmpty()

    private fun identityOf(provider: String): String =
        if (provider == "custom" || provider.startsWith("custom:")) "custom:custom" else provider

    private fun normal(url: String) = url.trim().trimEnd('/').lowercase()

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()
}
