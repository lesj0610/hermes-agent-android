package io.github.lesj0610.hermes.net

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One turn driven over the gateway's own event socket.
 *
 * The HTTP route cannot show reasoning: it has no thinking channel, and its
 * single reasoning-shaped event is the finished answer repeated back. This
 * surface is what the desktop reads, and it carries the real thing —
 * `reasoning.delta`, token by token, before the tool calls it leads to, plus
 * the tool results HTTP never sends.
 *
 * The event names and payload shapes here were taken from a live capture rather
 * than from reading the server, which corrected two guesses that would have
 * shipped as bugs. See docs/ws-transcript-contract.md.
 */
class SocketRun(
    private val session: DefaultClientWebSocketSession,
    private val json: Json,
    /** The gateway's live session id, which is *not* the stored one. */
    val liveSessionId: String,
) {
    private var nextId = 1_000

    /**
     * Frames for this turn, mapped onto the same events the HTTP path produces
     * so the transcript reducer does not care which transport ran.
     */
    fun events(): Flow<RunEvent> = flow {
        while (true) {
            val frame = try {
                session.incoming.receive() as? Frame.Text ?: continue
            } catch (_: ClosedReceiveChannelException) {
                return@flow
            }
            val message = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }
                .getOrNull() ?: continue
            if ((message["method"] as? JsonPrimitive)?.content != "event") continue

            val params = message["params"] as? JsonObject ?: continue
            val type = (params["type"] as? JsonPrimitive)?.content ?: continue
            val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
            val text = payload.text()

            when (type) {
                // The real thinking channel.
                "reasoning.delta" -> if (text.isNotEmpty()) emit(RunEvent.ReasoningDelta(text))

                // NOT thinking, despite the name: this carries the spinner
                // captions — "(◔_◔) ruminating…", and often an empty string.
                // Rendering it would have filled the transcript with mood text.
                "thinking.delta" -> Unit

                "message.delta" -> if (text.isNotEmpty()) emit(RunEvent.MessageDelta(null, null, text))

                "tool.start" -> emit(
                    RunEvent.ToolStarted(
                        null, null,
                        tool = payload.str("name").orEmpty(),
                        preview = payload.str("context") ?: payload.str("args_text"),
                    ),
                )

                "tool.complete" -> emit(
                    RunEvent.ToolCompleted(
                        null, null,
                        tool = payload.str("name").orEmpty(),
                        // The result the HTTP route never sends.
                        preview = payload.resultText(),
                        duration = (payload["duration_s"] as? JsonPrimitive)
                            ?.content?.toDoubleOrNull(),
                        failed = false,
                        errorMessage = null,
                    ),
                )

                "approval.request" -> emit(
                    RunEvent.ApprovalRequest(
                        null, null,
                        command = payload.str("command"),
                        choices = (payload["choices"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content }
                            ?: listOf("once", "deny"),
                        smartDenied = (payload["smart_denied"] as? JsonPrimitive)
                            ?.content?.toBooleanStrictOrNull() ?: false,
                    ),
                )

                // The turn's end, and its own copy of everything — used to
                // finish rather than to re-render.
                "message.complete" -> {
                    emit(RunEvent.Completed(null, null, null, null))
                    return@flow
                }

                "error" -> {
                    emit(RunEvent.Failed(null, null, payload.str("message")))
                    return@flow
                }

                // Arrives last carrying the finished answer, on this surface as
                // on HTTP. It is an echo, not reasoning.
                "reasoning.available" -> Unit

                // Housekeeping the transcript has no use for.
                else -> Unit
            }
        }
    }

    suspend fun respondToApproval(choice: String) {
        send(
            "approval.respond",
            buildJsonObject {
                put("session_id", liveSessionId)
                put("choice", choice)
            },
        )
    }

    suspend fun close() {
        // The live session is the gateway's, not ours to leave running.
        runCatching {
            send("session.close", buildJsonObject { put("session_id", liveSessionId) })
        }
        runCatching { session.close() }
    }

    private suspend fun send(method: String, params: JsonObject) {
        val id = nextId++
        session.send(
            Frame.Text(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("method", method)
                        put("params", params)
                    },
                ),
            ),
        )
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.text(): String =
    (this["text"] as? JsonPrimitive)?.let { if (it.isString) it.content else it.content }.orEmpty()

/**
 * A tool's output, whichever shape it arrived in.
 *
 * `result` is a string for most tools and a JSON object for the structured
 * ones, so it is rendered rather than assumed; `result_text` is preferred when
 * the server already flattened it.
 */
private fun JsonObject.resultText(): String? {
    str("result_text")?.let { return it }
    str("summary")?.let { return it }
    return when (val result = this["result"]) {
        null -> null
        is JsonPrimitive -> result.content.takeIf { it.isNotBlank() }
        else -> result.toString().takeIf { it.isNotBlank() }
    }
}
