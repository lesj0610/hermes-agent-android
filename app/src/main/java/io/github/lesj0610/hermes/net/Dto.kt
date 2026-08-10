package io.github.lesj0610.hermes.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

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
    /**
     * Sent with a model chosen from the inventory. A bare model is honoured on
     * this route, but the slug removes the ambiguity when two providers offer
     * the same model id.
     */
    val provider: String? = null,
    @SerialName("model_options") val modelOptions: ModelOptions? = null,
)

/**
 * Per-request runtime options. The gateway reads `reasoning.effort` and ignores
 * an effort it does not recognise rather than failing the turn.
 */
@Serializable
data class ModelOptions(val reasoning: ReasoningOption? = null)

@Serializable
data class ReasoningOption(val enabled: Boolean, val effort: String? = null)

/**
 * `content` is a bare string for a text-only turn and an array of parts when
 * something is attached, which is the shape the OpenAI-compatible surfaces use.
 *
 * The run route passes the trailing message's content straight through to the
 * agent without normalising it, and the agent runtime understands both shapes —
 * so the typing here is deliberately loose rather than a sealed hierarchy that
 * would have to be flattened again on the way out.
 */
@Serializable
data class InputMessage(val role: String, val content: JsonElement) {
    companion object {
        fun text(role: String, text: String) = InputMessage(role, JsonPrimitive(text))

        /**
         * The text part comes first: some providers key off the leading part for
         * the turn's intent, and an image with no preceding instruction reads as
         * "describe this" whatever the user actually asked.
         */
        fun withImages(role: String, text: String, imageUrls: List<String>) = InputMessage(
            role = role,
            content = buildJsonArray {
                if (text.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        },
                    )
                }
                imageUrls.forEach { url ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put(
                                "image_url",
                                buildJsonObject { put("url", JsonPrimitive(url)) },
                            )
                        },
                    )
                }
            },
        )
    }
}

// ── model inventory ───────────────────────────────────────────────────────

/**
 * `/api/model/options` — the provider catalogue the desktop picker uses.
 *
 * `/v1/models` cannot back a picker: it advertises a single virtual alias for
 * OpenAI-compatible clients, so it lists one entry however many models are
 * configured.
 */
@Serializable
data class ModelOptionsPayload(
    val providers: List<ProviderRow> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class ProviderRow(
    val slug: String? = null,
    val name: String? = null,
    val models: List<String> = emptyList(),
    /** `{model: {fast, reasoning}}`, absent on gateways that do not enrich it. */
    val capabilities: Map<String, ModelCapability> = emptyMap(),
)

@Serializable
data class ModelCapability(val fast: Boolean = false, val reasoning: Boolean = false)

/** One selectable model, flattened out of the provider rows. */
data class ModelChoice(
    val provider: String,
    val providerLabel: String,
    val model: String,
    val reasoning: Boolean,
)

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

/**
 * A partial session update.
 *
 * Nulls are dropped rather than sent: the route treats a present key as an
 * instruction, so serialising an absent field as null would clear a title
 * nobody asked to clear.
 */
@Serializable
data class SessionPatch(
    val title: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
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
