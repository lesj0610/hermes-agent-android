package io.github.lesj0610.hermes.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The nine frames `GET /v1/runs/{run_id}/events` can emit.
 *
 * All of them arrive as unnamed `data:` frames whose JSON body carries an
 * `event` key — the SSE `event:` line is not used on this route. Two names
 * that look like they belong here but do not:
 *
 *  - `run.stopping` is a run *status*, readable from `GET /v1/runs/{id}`.
 *  - `hermes.tool.progress` belongs to the `/v1/chat/completions` stream.
 *
 * [Unknown] exists so a server that grows a tenth event does not crash the app.
 */
/**
 * Token counts from `run.completed`.
 *
 * Note the naming: this route uses Hermes' native `input_tokens` /
 * `output_tokens`. The OpenAI-shaped `prompt_tokens` / `completion_tokens`
 * rename only happens on the /v1/chat/completions path.
 */
data class RunUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

sealed interface RunEvent {
    val runId: String?
    val timestamp: Double?

    data class MessageDelta(
        override val runId: String?,
        override val timestamp: Double?,
        val delta: String,
    ) : RunEvent

    data class ReasoningAvailable(
        override val runId: String?,
        override val timestamp: Double?,
        val text: String,
        val error: String?,
    ) : RunEvent

    data class ToolStarted(
        override val runId: String?,
        override val timestamp: Double?,
        val tool: String,
        val preview: String?,
    ) : RunEvent

    data class ToolCompleted(
        override val runId: String?,
        override val timestamp: Double?,
        val tool: String,
        val preview: String?,
        val duration: Double?,
        val error: String?,
    ) : RunEvent

    /**
     * [choices] is whatever the server computed — the app renders exactly these
     * and never derives its own set. Server-side values are `once`, `session`,
     * `always`, `deny`, narrowed by smart-deny and permanent-allow policy.
     */
    data class ApprovalRequest(
        override val runId: String?,
        override val timestamp: Double?,
        val command: String?,
        val choices: List<String>,
        val smartDenied: Boolean,
    ) : RunEvent

    data class ApprovalResponded(
        override val runId: String?,
        override val timestamp: Double?,
        val choice: String?,
        val resolved: Boolean,
    ) : RunEvent

    data class Completed(
        override val runId: String?,
        override val timestamp: Double?,
        val output: String?,
        val usage: RunUsage?,
    ) : RunEvent

    data class Failed(
        override val runId: String?,
        override val timestamp: Double?,
        val error: String?,
    ) : RunEvent

    data class Cancelled(
        override val runId: String?,
        override val timestamp: Double?,
    ) : RunEvent

    data class Unknown(
        override val runId: String?,
        override val timestamp: Double?,
        val name: String,
    ) : RunEvent
}

/** Maps one decoded SSE payload onto [RunEvent]. Returns null for frames with no `event` key. */
fun parseRunEvent(obj: JsonObject): RunEvent? {
    val name = obj.str("event") ?: return null
    val runId = obj.str("run_id")
    val ts = obj["timestamp"]?.let { runCatching { it.jsonPrimitive.content.toDouble() }.getOrNull() }

    return when (name) {
        "message.delta" -> RunEvent.MessageDelta(runId, ts, obj.str("delta").orEmpty())
        "reasoning.available" -> RunEvent.ReasoningAvailable(
            runId, ts, obj.str("text").orEmpty(), obj.str("error"),
        )
        "tool.started" -> RunEvent.ToolStarted(
            runId, ts, obj.str("tool").orEmpty(), obj.str("preview"),
        )
        "tool.completed" -> RunEvent.ToolCompleted(
            runId, ts,
            obj.str("tool").orEmpty(),
            obj.str("preview"),
            obj["duration"]?.let { runCatching { it.jsonPrimitive.content.toDouble() }.getOrNull() },
            obj.str("error"),
        )
        "approval.request" -> RunEvent.ApprovalRequest(
            runId, ts,
            obj.str("command"),
            obj["choices"]?.let { el ->
                runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
            } ?: emptyList(),
            obj["smart_denied"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false,
        )
        "approval.responded" -> RunEvent.ApprovalResponded(
            runId, ts,
            obj.str("choice"),
            obj["resolved"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: true,
        )
        "run.completed" -> RunEvent.Completed(
            runId, ts, obj.str("output"),
            (obj["usage"] as? JsonObject)?.let { usage ->
                RunUsage(
                    inputTokens = usage.int("input_tokens"),
                    outputTokens = usage.int("output_tokens"),
                    totalTokens = usage.int("total_tokens"),
                )
            },
        )
        "run.failed" -> RunEvent.Failed(runId, ts, obj.str("error"))
        "run.cancelled" -> RunEvent.Cancelled(runId, ts)
        else -> RunEvent.Unknown(runId, ts, name)
    }
}

private fun JsonObject.int(key: String): Int =
    this[key]?.let { runCatching { it.jsonPrimitive.content.toDouble().toInt() }.getOrNull() } ?: 0

private fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive }.getOrNull() }
        ?.let { if (it.isString) it.content else it.content.takeIf { c -> c != "null" } }
