package io.github.lesj0610.hermes.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire types for the Hermes gateway api_server platform.
 *
 * Every class here is lenient on purpose: the server owns the schema and adds
 * fields as it evolves, so unknown keys are ignored and anything the app does
 * not strictly need is nullable. See DESIGN.md §5 for the maintenance boundary.
 */

// ── runs ──────────────────────────────────────────────────────────────────

@Serializable
data class RunStarted(
    @SerialName("run_id") val runId: String,
    val status: String? = null,
)

@Serializable
data class RunStatus(
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
    @SerialName("last_event") val lastEvent: String? = null,
)

@Serializable
data class ApprovalDecision(val choice: String)

/**
 * The server accepts OpenAI-style `input` — a list of role/content messages.
 * Only the trailing user message matters for a fresh turn; history lives
 * server-side under [RunRequest.sessionId].
 */
@Serializable
data class RunRequest(
    val input: List<InputMessage>,
    @SerialName("session_id") val sessionId: String? = null,
    val model: String? = null,
)

@Serializable
data class InputMessage(val role: String, val content: String)

// ── sessions ──────────────────────────────────────────────────────────────

@Serializable
data class SessionListResponse(
    val data: List<SessionSummary> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val model: String? = null,
    val source: String? = null,
    val preview: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("last_active") val lastActive: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
data class MessageListResponse(val data: List<StoredMessage> = emptyList())

@Serializable
data class StoredMessage(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val role: String? = null,
    /** String for plain text, array for multimodal. Normalize with [text]. */
    val content: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: String? = null,
    val reasoning: String? = null,
) {
    /** Flattens whichever shape `content` arrived in down to displayable text. */
    val text: String
        get() = flattenContent(content)
}

/**
 * `content` is a plain string on the common path and a multimodal part array
 * otherwise. Anything that is not text (images, files) is dropped — the phone
 * renders those from their own fields, not from this flattening.
 */
internal fun flattenContent(content: JsonElement?): String = when (content) {
    null -> ""
    is JsonPrimitive -> content.contentOrEmpty()
    is JsonObject -> content["text"]?.jsonPrimitive?.contentOrEmpty() ?: ""
    else -> runCatching {
        content.jsonArray.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrEmpty()
                is JsonObject -> part["text"]?.jsonPrimitive?.contentOrEmpty() ?: ""
                else -> ""
            }
        }
    }.getOrDefault("")
}

private fun JsonPrimitive.contentOrEmpty(): String = if (isString) content else content

// ── metadata ──────────────────────────────────────────────────────────────

@Serializable
data class ModelListResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String? = null,
    val platform: String? = null,
    val version: String? = null,
)

// ── errors ────────────────────────────────────────────────────────────────

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
