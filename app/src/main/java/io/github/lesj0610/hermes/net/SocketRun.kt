package io.github.lesj0610.hermes.net

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
class SocketRun internal constructor(
    private val transport: FrameTransport,
    private val json: Json,
    private val rpc: RpcSession,
    private val live: LiveTurn,
    /** The gateway's live session id, which is *not* the stored one. */
    val liveSessionId: String,
    /** The id `prompt.submit` went out under. Its answer can be a refusal. */
    private val submitId: Int,
) {
    /** Ids of stop requests still awaiting an answer. */
    private val interrupts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * Frames for this turn, mapped onto the same events the HTTP path produces
     * so the transcript reducer does not care which transport ran.
     *
     * The flow ends after the turn's terminal event, or when the socket closes.
     * A socket that closes first ends the flow with no terminal event at all;
     * the caller sees the turn still open and ends it as a disconnection.
     *
     * Answers share the socket with events and arrive in whatever order the
     * gateway sends them — a delta can come before the submit is acknowledged —
     * so both are read here and neither is waited for. A refused submit ends
     * the turn with the gateway's reason: the socket stays open after one, and
     * nothing else would ever end it.
     */
    fun events(): Flow<RunEvent> = flow {
        while (true) {
            val frame = transport.receive() ?: return@flow
            val message = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
            if ((message["method"] as? JsonPrimitive)?.content != "event") {
                val id = (message["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                val error = message["error"] as? JsonObject
                if (error == null) {
                    interrupts.remove(id)
                    continue
                }
                val reason = (error["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: "The gateway refused the request"
                if (id == submitId) {
                    emit(RunEvent.Failed(null, null, reason))
                    return@flow
                }
                if (interrupts.remove(id)) emit(RunEvent.StopRefused(reason))
                continue
            }

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
                        // `args` is an object; `args_text` is the same thing as
                        // JSON text, read when the object is absent.
                        args = payload["args"] as? JsonObject
                            ?: payload.str("args_text")?.let { argsText ->
                                runCatching { json.parseToJsonElement(argsText).jsonObject }.getOrNull()
                            },
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
                // finish rather than to re-render. A turn stopped by
                // `session.interrupt` ends here too, and says so.
                "message.complete" -> {
                    if (payload.str("status") == "interrupted") {
                        emit(RunEvent.Cancelled(null, null))
                    } else {
                        emit(RunEvent.Completed(null, null, null, null))
                    }
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
        rpc.send(
            "approval.respond",
            buildJsonObject {
                put("session_id", liveSessionId)
                put("choice", choice)
            },
        )
    }

    /**
     * Stops the turn. The socket's own verb: the HTTP stop route knows runs by
     * the ids `/v1/runs` hands out, and a live session id is not one of them.
     * The answer is the event stream's `message.complete`, not this reply.
     */
    suspend fun interrupt() {
        // Registered before it is sent, so a refusal read back at once is still known for one.
        val id = rpc.reserve()
        interrupts += id
        rpc.send("session.interrupt", buildJsonObject { put("session_id", liveSessionId) }, id)
    }

    /**
     * Gives the turn back: restores anything it changed, then closes the live
     * session and the socket — the same cleanup every other ending uses. Safe
     * to call more than once; the report says what is known to have happened.
     */
    suspend fun close(): CleanupReport = live.finish()
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
