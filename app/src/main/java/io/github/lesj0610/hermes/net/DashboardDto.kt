package io.github.lesj0610.hermes.net

import kotlinx.serialization.Serializable

/**
 * Wire types for the dashboard server (`hermes dashboard` / `hermes serve`,
 * default port 9119).
 *
 * This is a different server from the gateway api_server with a different auth
 * model — a cookie session rather than a bearer token — so its types live apart
 * from Dto.kt to keep the two surfaces from being confused for one another.
 */

@Serializable
data class PasswordLoginRequest(
    /** Registered auth provider name. `basic` is the username/password one. */
    val provider: String = "basic",
    val username: String,
    val password: String,
    val next: String = "",
)

@Serializable
data class PasswordLoginResponse(val ok: Boolean = false, val next: String? = null)

// ── profiles ──────────────────────────────────────────────────────────────

@Serializable
data class ProfileListResponse(val profiles: List<Profile> = emptyList())

@Serializable
data class Profile(
    val name: String,
    val description: String? = null,
    val path: String? = null,
)

/**
 * Two different notions of "current", and the distinction matters.
 *
 * [active] is the sticky default that new CLI invocations pick up — what
 * `hermes profile use` writes. [current] is the profile the running dashboard
 * process is itself scoped to. Switching the active profile does **not**
 * retarget an already-running gateway, so the UI shows both rather than
 * implying one switch changed everything.
 */
@Serializable
data class ActiveProfile(
    val active: String = "default",
    val current: String = "default",
)

@Serializable
data class ProfileActiveUpdate(val name: String)

// ── skills ────────────────────────────────────────────────────────────────

/**
 * `GET /api/skills` returns a bare array, not an envelope.
 *
 * Distinct from [Skill] in GatewayDto: the gateway's `/v1/skills` only lists
 * names, while this carries the enabled flag that makes the panel actionable.
 */
@Serializable
data class DashboardSkill(
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    /** `hub`, `bundled`, or `agent` — where the skill came from. */
    val provenance: String? = null,
    val usage: Int = 0,
)

@Serializable
data class SkillToggleRequest(
    val name: String,
    val enabled: Boolean,
    val profile: String? = null,
)
