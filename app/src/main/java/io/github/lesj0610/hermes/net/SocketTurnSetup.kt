package io.github.lesj0610.hermes.net

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    onRestorePlan: (String?) -> Unit = {},
    /**
     * Send the reasoning level even when the session reports it already. Set
     * when the previous turn's restore went out and its outcome is unknown: the
     * reported level cannot be trusted, and the latest explicit setting should
     * be this turn's.
     */
    forceReasoning: Boolean = false,
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
    onLive(liveId)
    val info = openedObject?.get("info")?.let { element ->
        runCatching { setupJson.decodeFromJsonElement(SessionLiveInfo.serializer(), element) }.getOrNull()
    }

    val switchModel = model.isNotEmpty() && !onPickedModel(rpc, liveId, info, model, provider)
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

    if (reasoning.isNotEmpty() && (before != reasoning || forceReasoning)) {
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
 * Whether the live session already runs [model] on the endpoint [provider]
 * names. Anything short of a confirmation counts as different, and the pick is
 * applied explicitly.
 *
 * With its agent built, the session reports the picker row it routes by, so an
 * exact match confirms it. Nothing else does: an agent not built yet reports no
 * provider, and bare `custom` is the class every custom endpoint resolves to,
 * not a row. Two custom rows can still be aliases of one endpoint, which only
 * their URLs settle: see [sameEndpoint].
 */
private suspend fun onPickedModel(
    rpc: RpcCaller,
    liveId: String,
    info: SessionLiveInfo?,
    model: String,
    provider: String,
): Boolean {
    if (info == null || info.model != model) return false
    if (provider.isEmpty()) return true
    val reported = info.provider
    if (reported.isEmpty() || reported == "custom") return false
    if (reported == provider) return true
    if (!isCustom(provider) || !isCustom(reported)) return false
    return sameEndpoint(rpc, liveId, reported, provider)
}

private fun isCustom(provider: String) = provider == "custom" || provider.startsWith("custom:")

/**
 * Whether the custom row [provider] points at the same URL as the row the
 * session is on, per `model.options` for the live session, which lists every
 * row's URL.
 *
 * Its current mark follows the session's identity once the agent is built, and
 * the profile default before; it is used only where it agrees with the identity
 * the session itself reports ([reported]).
 */
private suspend fun sameEndpoint(rpc: RpcCaller, liveId: String, reported: String, provider: String): Boolean {
    val options = try {
        rpc.call("model.options", buildJsonObject { put("session_id", liveId) }) as? JsonObject
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return false
    val rows = (options["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val current = rows.firstOrNull { (it["is_current"] as? JsonPrimitive)?.booleanOrNull == true } ?: return false
    if (current.text("slug") != reported) return false
    val picked = rows.firstOrNull { it.text("slug") == provider } ?: return false
    val here = endpoint(current.text("api_url")) ?: return false
    return here == endpoint(picked.text("api_url"))
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

/**
 * An endpoint URL reduced only by what cannot change the endpoint: the scheme
 * and host are case-insensitive, a default port is the same as none, and a
 * trailing slash on the path is not a different resource. The path and query
 * keep their case — `/TenantA/v1` and `/tenanta/v1` can be different services —
 * and anything that does not parse is not comparable at all.
 */
internal fun endpoint(url: String): String? {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = when {
        uri.port == -1 -> ""
        scheme == "http" && uri.port == 80 -> ""
        scheme == "https" && uri.port == 443 -> ""
        else -> ":${uri.port}"
    }
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$host$port$path$query"
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
