package io.github.lesj0610.hermes.data

import io.github.lesj0610.hermes.net.StoredMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A stored session, read back as the transcript the live turn would have drawn.
 *
 * The history already holds everything the desktop shows. Each assistant row
 * carries its `reasoning`, and each tool result carries a `tool_call_id` that
 * resolves to the call — and its arguments — on the assistant row before it.
 * Reading only the text dropped both, so a reopened conversation had no
 * thinking in it and every tool row lost what it had acted on.
 *
 * The order falls out of the rows themselves: think, call, results, think,
 * call, results, answer — the same sequence a live turn streams.
 *
 * [key] mints row keys, so a reopened session and a live one never collide.
 */
internal fun storedToTranscript(
    messages: List<StoredMessage>,
    key: (String) -> String,
): List<TranscriptItem> {
    val calls = storedToolCalls(messages)
    val out = ArrayList<TranscriptItem>(messages.size + messages.size / 2)

    for (message in messages) {
        val body = message.text
        when (message.role) {
            // `@image:` directives are attachments, not prose: lifted out so the
            // bubble shows a caption, the paths kept for the fetch that turns
            // them back into pictures.
            "user" -> parseStoredUserTurn(body).let { turn ->
                if (turn.text.isNotBlank() || turn.imagePaths.isNotEmpty()) {
                    out += TranscriptItem.UserText(
                        key = key("u"),
                        text = turn.text,
                        imagePaths = turn.imagePaths,
                    )
                }
            }

            "assistant" -> {
                // Thinking first: it is what led to this row's text and calls.
                // Never timed — it arrived whole — so it reads "Thought".
                message.reasoning?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    out += TranscriptItem.Reasoning(key("r"), it)
                }
                // `MEDIA:` is a produced image, not prose. The HTTP route
                // rewrites these into data URLs; stored history leaves paths.
                parseAttachmentRefs(body, ASSISTANT_MEDIA_DIRECTIVE).let { turn ->
                    if (turn.text.isNotBlank() || turn.imagePaths.isNotEmpty()) {
                        out += TranscriptItem.AssistantText(
                            key = key("a"),
                            text = turn.text,
                            streaming = false,
                            imagePaths = turn.imagePaths,
                        )
                    }
                }
            }

            "tool" -> {
                val call = message.toolCallId?.let(calls::get)
                val name = message.toolName ?: call?.name ?: "tool"
                val aim = toolAim(name, call?.args)
                out += TranscriptItem.ToolCall(
                    key = key("t"),
                    tool = name,
                    preview = body.takeIf { it.isNotBlank() },
                    // History keeps no success flag, so the result is read the
                    // way the desktop reads it — without this, a run summary on
                    // a reopened session could never say that anything failed.
                    state = if (resultLooksFailed(body)) ToolState.Failed else ToolState.Completed,
                    target = aim.target,
                    readsResource = aim.readsResource,
                )
            }
        }
    }
    return out
}

private data class StoredCall(val name: String?, val args: JsonObject?)

private val lenient = Json { ignoreUnknownKeys = true }

/**
 * Every call on every assistant row, by id. Indexed under both `id` and
 * `call_id`: rows carry both, and a result may reference either.
 */
private fun storedToolCalls(messages: List<StoredMessage>): Map<String, StoredCall> {
    val byId = HashMap<String, StoredCall>()
    for (message in messages) {
        if (message.role != "assistant") continue
        for (element in callsOf(message.toolCalls)) {
            val call = element as? JsonObject ?: continue
            val function = call["function"] as? JsonObject
            val stored = StoredCall(
                name = function?.string("name"),
                args = argumentsOf(function?.get("arguments")),
            )
            call.string("id")?.let { byId[it] = stored }
            call.string("call_id")?.let { byId[it] = stored }
        }
    }
    return byId
}

/** `tool_calls` as a list, whether it arrived as an array or as its JSON text. */
private fun callsOf(raw: JsonElement?): List<JsonElement> = when (raw) {
    is JsonArray -> raw
    is JsonPrimitive -> if (raw.isString) {
        runCatching { lenient.parseToJsonElement(raw.content) as? JsonArray }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
    else -> emptyList()
}

/** Arguments are a JSON string on the wire and occasionally an object. */
private fun argumentsOf(raw: JsonElement?): JsonObject? = when (raw) {
    is JsonObject -> raw
    is JsonPrimitive -> if (raw.isString) {
        runCatching { lenient.parseToJsonElement(raw.content).jsonObject }.getOrNull()
    } else {
        null
    }
    else -> null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
