package io.github.lesj0610.hermes.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a turn asks the agent to run with. A blank field leaves the session's
 * own setting alone.
 */
data class TurnRuntime(
    val model: String? = null,
    val provider: String? = null,
    /** A reasoning level on the wire: `none`, `low`, … `ultra`. */
    val reasoning: String? = null,
) {
    companion object {
        val DEFAULT = TurnRuntime()
    }
}

/**
 * A turn spoken in a conversation rather than typed.
 *
 * The gateway shapes it through the `voice-live` surface: a per-turn note on the
 * model input asks for short, plain, speakable sentences. The note reaches the
 * model only — the stored user row stays what was said — and the system prompt
 * is not touched.
 */
data class VoiceTurn(
    /** Recent spoken exchange the model reads alongside the turn; never persisted. */
    val context: String = "",
    /**
     * The level ordinary turns run at. A spoken turn runs with thinking off, and
     * that setting is stored on the session, so it is put back afterwards —
     * otherwise the next typed turn, here or on another client, would inherit it.
     */
    val restoreReasoning: String? = null,
)

/** One JSON-RPC round trip, reduced to what turn setup needs. */
internal fun interface RpcCaller {
    suspend fun call(method: String, params: JsonObject): JsonElement
}

/** A live session ready for `prompt.submit`, and what to put back when the turn ends. */
internal data class PreparedTurn(
    val liveId: String,
    /** Set when this turn changed the session's reasoning and it must be restored. */
    val restoreReasoning: String?,
)

/** The gateway refused a setting, so the turn must not run on something else. */
class TurnSetupException(message: String) : Exception(message)

/**
 * The conversation is held: a setting an earlier turn sent may still land on
 * the live session this turn was handed. Nothing was sent to it.
 */
class ConversationHeldException(message: String) : Exception(message)

/**
 * Brings a live session to the runtime the turn asked for, then hands it back.
 *
 * Every setting goes to the LIVE id. `config.set` on a stored id answers "session
 * not found", and one with no id at all writes the profile's config.yaml — the
 * setting every other client and session starts from. The live id is checked
 * non-empty before any setting is sent, so that write cannot happen from here.
 *
 * Each setting is awaited and a refusal ends the turn: running it on the default
 * model after the user picked another is the silent wrong answer this replaces.
 * Settings the session already has are skipped, so an ordinary turn costs the
 * same round trips it did before.
 *
 * Responsibility for cleanup is handed over as it arises, not at the end: the
 * live id goes to [onLive] the moment the gateway returns it, and a spoken
 * turn's restore target goes to [onRestorePlan] before the change is sent. A
 * failure anywhere after that still leaves the caller able to close the session
 * and put the level back.
 */
internal suspend fun prepareSocketTurn(
    rpc: RpcCaller,
    storedSessionId: String?,
    runtime: TurnRuntime,
    voice: VoiceTurn? = null,
    onLive: (String) -> Unit = {},
    /**
     * The stored conversation the live session belongs to — the one resumed,
     * or, `created`, the one this call just made — before anything is set.
     */
    onStored: (storedId: String, created: Boolean) -> Unit = { _, _ -> },
    onRestorePlan: (String?) -> Unit = {},
    /**
     * A live session an earlier turn's unanswered setting addressed, released
     * because the gateway no longer lists it. A resume must then hand out
     * another; handed this one back, the turn stops before sending anything.
     */
    heldLiveId: String? = null,
): PreparedTurn {
    val model = runtime.model?.trim().orEmpty()
    val provider = runtime.provider?.trim().orEmpty()
    val reasoning = runtime.reasoning?.trim().orEmpty()

    val opened = if (storedSessionId.isNullOrBlank()) {
        // A new session takes the runtime at creation, the way the desktop's
        // composer override does; the reported info says whether it held.
        rpc.call(
            "session.create",
            buildJsonObject {
                if (model.isNotEmpty()) put("model", model)
                if (provider.isNotEmpty()) put("provider", provider)
                if (reasoning.isNotEmpty()) put("reasoning_effort", reasoning)
            },
        )
    } else {
        try {
            // Built before the answer: an agent not built yet reports neither its
            // provider nor its level, and the settings below are decided on both.
            resume(rpc, storedSessionId, eager = true)
        } catch (refused: GatewayRpcException) {
            if (refused.code != RESUME_FAILED) throw refused
            // The stored runtime could not be built — a provider since removed,
            // say — and this turn may be picking another. Resumed without the
            // build, nothing is reported, so everything is applied explicitly.
            resume(rpc, storedSessionId, eager = false)
        }
    }
    val openedObject = opened as? JsonObject
    val liveId = (openedObject?.get("session_id") as? JsonPrimitive)?.content?.trim().orEmpty()
    if (liveId.isEmpty()) throw GatewayRpcException(-1, "The gateway did not return a live session")
    if (liveId == heldLiveId) {
        throw ConversationHeldException("The gateway handed back the live session this conversation is held on")
    }
    onLive(liveId)
    val created = storedSessionId.isNullOrBlank()
    val storedId = if (created) {
        (openedObject?.get("stored_session_id") as? JsonPrimitive)?.content?.trim().orEmpty()
    } else {
        storedSessionId.orEmpty()
    }
    if (storedId.isNotEmpty()) onStored(storedId, created)
    val info = openedObject?.get("info")?.let { element ->
        runCatching { setupJson.decodeFromJsonElement(SessionLiveInfo.serializer(), element) }.getOrNull()
    }

    val switchModel = model.isNotEmpty() && !onPickedModel(info, model, provider, created)
    if (switchModel) {
        val result = rpc.call(
            "config.set",
            buildJsonObject {
                put("session_id", liveId)
                put("key", "model")
                // `--session`: a pick made in the app is this conversation's,
                // never a rewrite of the profile default.
                put(
                    "value",
                    buildString {
                        append(model)
                        if (provider.isNotEmpty()) append(" --provider ").append(provider)
                        append(" --session")
                    },
                )
            },
        ) as? JsonObject
        if ((result?.get("confirm_required") as? JsonPrimitive)?.booleanOrNull == true) {
            // A guarded model asks for confirmation on the desktop. Running the
            // turn anyway on the old model would ignore the pick; refusing says
            // why.
            val message = (result["confirm_message"] as? JsonPrimitive)?.content
            throw TurnSetupException(message?.takeIf { it.isNotBlank() } ?: "The gateway asked to confirm this model")
        }
    }

    // A model switch re-reads the reasoning level from config.yaml, so after
    // one the level reported before it says nothing about the session now:
    // the level is applied regardless, and a spoken turn restores the app's own.
    val before = if (switchModel) "" else info?.reasoningEffort?.trim().orEmpty()

    // Only a spoken turn is undone. The level the session had is the one to
    // return to; when the gateway did not report one, the app's own ordinary
    // level stands in — the next typed turn applies it regardless. Planned
    // before the change is sent: a change whose answer never arrives may still
    // have been applied.
    val restore = voice?.let {
        val target = before.takeIf { value -> value.isNotEmpty() && value != reasoning }
            ?: it.restoreReasoning?.trim()?.takeIf { value -> value.isNotEmpty() }
        target?.takeIf { value -> reasoning.isNotEmpty() && value != reasoning }
    }
    if (voice != null) onRestorePlan(restore)

    if (reasoning.isNotEmpty() && before != reasoning) {
        try {
            rpc.call(
                "config.set",
                buildJsonObject {
                    put("session_id", liveId)
                    put("key", "reasoning")
                    put("value", reasoning)
                },
            )
        } catch (refused: GatewayRpcException) {
            // Refused outright, so nothing changed and nothing needs restoring.
            if (voice != null && before.isNotEmpty() && before != "none") onRestorePlan(null)
            throw refused
        }
    }
    return PreparedTurn(liveId, restore)
}

/**
 * `session.resume` for [storedId]. A session another client already holds live
 * is reused as it is: [eager] builds only a session this call brings up, so an
 * unbuilt one can still come back.
 */
private suspend fun resume(rpc: RpcCaller, storedId: String, eager: Boolean): JsonElement = rpc.call(
    "session.resume",
    buildJsonObject {
        put("session_id", storedId)
        if (eager) put("eager_build", true)
        // The history is read over HTTP; resending it with every turn is only payload.
        put("omit_messages", true)
    },
)

/** The gateway's code for a resume whose agent could not be built. */
private const val RESUME_FAILED = 5000

/**
 * Whether the live session is already known to run [model] on the route
 * [provider] names. Anything short of that counts as different, and the pick
 * is applied explicitly.
 *
 * A session this turn created took the pick at creation, by name, and echoes
 * it back. A fixed provider's reported name is its route — empty until the
 * agent is built. A custom endpoint's is not: the gateway recovers the name
 * from the endpoint URL, lowercased whole, so endpoints whose paths differ in
 * case report the same one, and a session can report the very row picked while
 * running on another. So a custom pick is applied on every turn; resolved by
 * name, it routes to the row picked.
 */
private fun onPickedModel(info: SessionLiveInfo?, model: String, provider: String, created: Boolean): Boolean {
    if (info == null || info.model != model) return false
    if (provider.isEmpty()) return true
    if (created) return info.provider == provider
    if (isCustom(provider) || isCustom(info.provider)) return false
    return info.provider == provider
}

private fun isCustom(provider: String) = provider == "custom" || provider.startsWith("custom:")

/** The `prompt.submit` parameters for a turn, spoken or typed. */
internal fun promptSubmitParams(liveId: String, text: String, voice: VoiceTurn?): JsonObject =
    buildJsonObject {
        put("session_id", liveId)
        put("text", text)
        if (voice != null) {
            put("surface", "voice-live")
            voice.context.takeIf { it.isNotBlank() }?.let { put("voice_context", it) }
        }
    }

private val setupJson = Json { ignoreUnknownKeys = true }
