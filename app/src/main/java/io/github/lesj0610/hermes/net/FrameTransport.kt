package io.github.lesj0610.hermes.net

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * A text-frame duplex channel: the dashboard's socket, or a test's stand-in.
 *
 * Everything above it — turn setup, the event stream, cleanup — speaks JSON
 * text frames and nothing else, so the whole socket path can be exercised
 * against an in-process gateway without a network.
 */
internal interface FrameTransport {
    suspend fun send(text: String)

    /** The next text frame, or null once the channel has closed. */
    suspend fun receive(): String?

    suspend fun close()

    /** Neither closed nor seen to have ended: an answer can still arrive. */
    val isOpen: Boolean
}

/**
 * The socket closed before an answer arrived. Distinct from [GatewayRpcException]:
 * that one is the gateway refusing, this one is nothing having been heard.
 */
class SocketClosedException(message: String) : Exception(message)

/** The gateway did not answer within the limit. Not proof that it did nothing. */
class GatewayTimeoutException(message: String) : Exception(message)

/** [FrameTransport] over a Ktor client WebSocket. Non-text frames are skipped. */
internal class WebSocketTransport(private val session: DefaultClientWebSocketSession) : FrameTransport {
    @Volatile
    private var ended = false

    override val isOpen: Boolean get() = !ended

    override suspend fun send(text: String) {
        try {
            session.send(Frame.Text(text))
        } catch (closed: ClosedSendChannelException) {
            // Refused before it was queued: it never went out.
            ended = true
            throw java.io.IOException("The dashboard socket is closed", closed)
        }
    }

    override suspend fun receive(): String? {
        while (true) {
            val frame = try {
                session.incoming.receive()
            } catch (_: ClosedReceiveChannelException) {
                ended = true
                return null
            }
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun close() {
        ended = true
        runCatching { session.close() }
    }
}

/**
 * JSON-RPC over a [FrameTransport], one call at a time.
 *
 * Replies are matched by id; frames that are not the reply — events, answers
 * to fire-and-forget requests — are skipped while waiting.
 *
 * A request [callRaw] sent stays unanswered until its answer is read, whoever
 * reads it. A wait given up — timed out, cancelled — does not answer it: the
 * gateway may still carry it out, and only its answer says it has.
 */
internal class RpcSession(
    private val transport: FrameTransport,
    @PublishedApi internal val codec: Json,
) {
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    suspend inline fun <reified T> call(method: String, params: JsonObject): T =
        decode(callRaw(method, params))

    /** Exposed so the inline [call] can reach the codec. */
    inline fun <reified T> decode(element: JsonElement): T =
        codec.decodeFromJsonElement(serializer(), element)

    suspend fun callRaw(method: String, params: JsonObject): JsonElement {
        val id = reserve()
        unanswered[id] = method
        try {
            send(method, params, id)
        } catch (notSent: java.io.IOException) {
            // Refused by the socket itself, so it never went out.
            unanswered.remove(id)
            throw notSent
        }
        while (true) {
            val text = transport.receive()
                ?: throw SocketClosedException("The dashboard socket closed during $method")
            val message = runCatching { codec.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val replyId = noteAnswer(message) ?: continue
            if (replyId != id) continue

            (message["error"] as? JsonObject)?.let { error ->
                throw GatewayRpcException(
                    code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                    message = (error["message"] as? JsonPrimitive)?.content ?: "RPC $method failed",
                )
            }
            return message["result"] ?: JsonObject(emptyMap())
        }
    }

    /** An id for a request about to be sent, known before its answer can arrive. */
    fun reserve(): Int = nextId.getAndIncrement()

    /** Requests [callRaw] sent whose answer has not been read, by id. */
    private val unanswered = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Whether a request for one of [methods] went out and its answer has not been read. */
    fun awaiting(methods: Set<String>): Boolean = unanswered.values.any { it in methods }

    /**
     * Reads until every request for one of [methods] has been answered. False
     * when the socket ends first: those answers can no longer arrive, and
     * whether the gateway acted on the requests stays unknown.
     */
    suspend fun awaitAnswers(methods: Set<String>): Boolean {
        while (awaiting(methods)) {
            val text = transport.receive() ?: return false
            runCatching { codec.parseToJsonElement(text).jsonObject }.getOrNull()?.let(::noteAnswer)
        }
        return true
    }

    /** A frame read anywhere: when it answers a request, that request is answered. Its id, if it is an answer. */
    fun noteAnswer(message: JsonObject): Int? {
        // The gateway's own requests to the client carry an id too, and a method.
        if (message["method"] != null) return null
        val id = (message["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        unanswered.remove(id)
        return id
    }

    /**
     * Sends a request without waiting for its reply, and answers its id. For
     * requests whose answer is the event stream (`prompt.submit`) or that a
     * reader of that stream would otherwise have to wait on. Pass a [reserve]d
     * id when something must be ready to recognise the answer before it lands.
     */
    suspend fun send(method: String, params: JsonObject, id: Int = reserve()): Int {
        transport.send(
            codec.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            ),
        )
        return id
    }
}
