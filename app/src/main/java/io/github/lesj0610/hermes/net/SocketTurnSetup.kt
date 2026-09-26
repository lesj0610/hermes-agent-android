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
 */
internal suspend fun prepareSocketTurn(
    rpc: RpcCaller,
    storedSessionId: String?,
    runtime: TurnRuntime,
    voice: VoiceTurn? = null,
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
        rpc.call("session.resume", buildJsonObject { put("session_id", storedSessionId) })
    }
    val openedObject = opened as? JsonObject
    val liveId = (openedObject?.get("session_id") as? JsonPrimitive)?.content?.trim().orEmpty()
    if (liveId.isEmpty()) throw GatewayRpcException(-1, "The gateway did not return a live session")
    val info = openedObject?.get("info")?.let { element ->
        runCatching { setupJson.decodeFromJsonElement(SessionLiveInfo.serializer(), element) }.getOrNull()
    }

    val switchModel = model.isNotEmpty() &&
        (info == null || info.model != model || !sameProvider(info.provider, provider))
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
    if (reasoning.isNotEmpty() && before != reasoning) {
        rpc.call(
            "config.set",
            buildJsonObject {
                put("session_id", liveId)
                put("key", "reasoning")
                put("value", reasoning)
            },
        )
    }

    // Only a spoken turn is undone, and only if it changed something. The level
    // the session had is the one to return to; when the gateway did not report
    // one, the app's own ordinary level stands in — the next typed turn applies
    // it regardless.
    val restore = voice?.let {
        val target = before.takeIf { value -> value.isNotEmpty() && value != reasoning }
            ?: it.restoreReasoning?.trim()?.takeIf { value -> value.isNotEmpty() }
        target?.takeIf { value -> reasoning.isNotEmpty() && value != reasoning }
    }
    return PreparedTurn(liveId, restore)
}

/**
 * Whether the session's reported provider is the one the picker named.
 *
 * Custom endpoints come back under the gateway's canonical identity — derived
 * from the endpoint, e.g. `custom:custom` — which does not round-trip with the
 * picker's slug (`custom:local-(127.0.0.1:8088)`). Compared literally, every
 * turn on a local model re-sent the switch, and a switch re-reads the reasoning
 * level too. Between custom endpoints the model name is what tells them apart.
 */
internal fun sameProvider(reported: String, wanted: String): Boolean {
    if (wanted.isEmpty() || reported == wanted) return true
    return reported.substringBefore(':') == "custom" && wanted.substringBefore(':') == "custom"
}

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
