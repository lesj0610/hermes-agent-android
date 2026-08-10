package io.github.lesj0610.hermes.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire types for the operational surface: scheduled jobs, gateway health, and
 * the server's own capability report.
 *
 * Same leniency rule as Dto.kt — the server owns the schema, so unknown keys
 * are ignored and anything not strictly needed is nullable.
 */

// ── capabilities ──────────────────────────────────────────────────────────

/**
 * What this gateway admits to supporting.
 *
 * Used to hide panels a given server cannot serve, so the app degrades on an
 * older or differently-configured gateway instead of showing a screen that
 * only ever produces 404s. The shape is deliberately loose: `features` is a
 * free-form map and new keys appear over time.
 */
@Serializable
data class Capabilities(
    val mode: String? = null,
    /**
     * The model this gateway is running, straight from the capabilities probe.
     *
     * Worth taking here because this route answers on every reachable gateway
     * and costs nothing, while the inventory behind `/api/model/options` builds
     * provider catalogues and can fail on its own.
     */
    val model: String? = null,
    val features: JsonObject? = null,
    val endpoints: JsonObject? = null,
) {
    private fun feature(name: String): Boolean? =
        (features?.get(name) as? JsonPrimitive)?.booleanOrNull

    /** True unless the server explicitly says otherwise — absence is not denial. */
    private fun featureOrDefault(name: String, default: Boolean = true): Boolean =
        feature(name) ?: default

    val approvalEvents: Boolean get() = featureOrDefault("approval_events")
    // `jobs_admin` is deliberately not exposed. The gateway hardcodes it to
    // false while registering every /api/jobs route, so reading it as "can
    // this server manage jobs" hid a working screen.
    val healthDetailed: Boolean get() = featureOrDefault("health_detailed")
}

// ── scheduled jobs ────────────────────────────────────────────────────────

@Serializable
data class JobListResponse(val jobs: List<Job> = emptyList())

/**
 * Field names mirror `cron/jobs.py:_normalize_job_record`, which is what the
 * endpoint actually returns — notably `schedule_display` (a humanised string
 * the server has already formatted) and `state`, a derived value that must be
 * preferred over `enabled` because a half-paused record can be enabled and
 * paused at once.
 */
@Serializable
data class Job(
    val id: String,
    val name: String,
    val prompt: String? = null,
    val schedule: String? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val state: String? = null,
    val enabled: Boolean = true,
    val skills: List<String> = emptyList(),
    val deliver: JsonElement? = null,
    val origin: String? = null,
    @SerialName("latest_execution") val latestExecution: JsonElement? = null,
) {
    val isPaused: Boolean get() = state.equals("paused", ignoreCase = true) || !enabled

    /** Prefer the server's formatted string; fall back to the raw expression. */
    val scheduleLabel: String?
        get() = scheduleDisplay?.takeIf { it.isNotBlank() } ?: schedule?.takeIf { it.isNotBlank() }

    /** `deliver` is a string on the common path and an object when it carries routing. */
    val deliverLabel: String?
        get() = when (val d = deliver) {
            null -> null
            is JsonPrimitive -> d.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonObject -> d["platform"]?.jsonPrimitive?.content
            else -> null
        }
}

// ── gateway health ────────────────────────────────────────────────────────

@Serializable
data class DetailedHealth(
    val status: String? = null,
    val version: String? = null,
    val pid: Int? = null,
    @SerialName("gateway_state") val gatewayState: String? = null,
    @SerialName("gateway_busy") val gatewayBusy: Boolean? = null,
    @SerialName("gateway_drainable") val gatewayDrainable: Boolean? = null,
    @SerialName("active_agents") val activeAgents: Int? = null,
    @SerialName("exit_reason") val exitReason: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val platforms: JsonElement? = null,
    val readiness: JsonElement? = null,
)

// ── toolsets and skills ───────────────────────────────────────────────────
//
// Not panels of their own: read-only lists with nothing to act on are a poor
// destination on a phone. They surface as counts and names inside the gateway
// panel, where they are context rather than a place to go.

@Serializable
data class ToolsetListResponse(val data: List<Toolset> = emptyList())

@Serializable
data class Toolset(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = true,
    val tools: List<String> = emptyList(),
)

@Serializable
data class SkillListResponse(val data: List<Skill> = emptyList())

@Serializable
data class Skill(val name: String)
