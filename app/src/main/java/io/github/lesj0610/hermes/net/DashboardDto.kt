package io.github.lesj0610.hermes.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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

// ── projects ──────────────────────────────────────────────────────────────

/**
 * A named, multi-folder workspace, stored server-side in the profile's
 * `projects.db`.
 *
 * This is the desktop's Projects feature, not a phone-local list — which is the
 * point of it: a project made here is the same project the desktop opens, and
 * the sessions grouped under it are grouped by the folders recorded here.
 *
 * Reached over the dashboard's `/api/ws` JSON-RPC bridge; the gateway's own HTTP
 * surface has no projects route.
 */
@Serializable
data class Project(
    val id: String,
    val slug: String = "",
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    @SerialName("board_slug") val boardSlug: String? = null,
    @SerialName("primary_path") val primaryPath: String? = null,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    val folders: List<ProjectFolder> = emptyList(),
)

@Serializable
data class ProjectFolder(
    val path: String,
    val label: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("added_at") val addedAt: Long = 0,
)

@Serializable
data class ProjectsPayload(
    val projects: List<Project> = emptyList(),
    @SerialName("active_id") val activeId: String? = null,
)

@Serializable
data class ProjectEnvelope(val project: Project? = null)

// ── gateway filesystem ────────────────────────────────────────────────────

/**
 * One entry from `GET /api/fs/list`.
 *
 * The paths are the gateway host's, not the phone's — which is why a project's
 * folders are picked by browsing this rather than with an Android file picker.
 */
@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean = false,
)

@Serializable
data class FsListResponse(
    val entries: List<FsEntry> = emptyList(),
    val error: String? = null,
)

@Serializable
data class FsWriteTextRequest(val path: String, val content: String)

/**
 * `POST /api/auth/ws-ticket` — a one-shot credential for a WebSocket upgrade.
 *
 * The socket cannot carry the session cookie or an Authorization header, so the
 * dashboard mints this instead. Single use, 30 seconds.
 */
@Serializable
data class WsTicketResponse(
    val ticket: String = "",
    @SerialName("ttl_seconds") val ttlSeconds: Int = 0,
)

// ── slash commands ────────────────────────────────────────────────────────

/**
 * `commands.catalog` — the slash command set, straight from the registry the
 * desktop reads.
 *
 * Answers without a session, which is what makes a palette possible here at
 * all: everything else on that socket wants a live gateway session.
 *
 * `pairs` is `[["/new", "Start a new session …"], …]` — a list of two-element
 * lists rather than objects, so it is decoded as such.
 */
@Serializable
data class CommandCatalog(
    val pairs: List<List<String>> = emptyList(),
    val categories: List<CommandCategory> = emptyList(),
    /**
     * Skill commands, keyed by `/name`.
     *
     * These are the ones `command.dispatch` actually runs — the built-in
     * registry entries are drawn by the desktop's own UI and have no
     * server-side execution at all. Keeping the map means the palette can tell
     * the two apart instead of guessing from the name.
     */
    val skills: Map<String, JsonElement> = emptyMap(),
    @SerialName("skill_count") val skillCount: Int = 0,
    val warning: String? = null,
)

@Serializable
data class CommandCategory(
    val name: String = "",
    val pairs: List<List<String>> = emptyList(),
)

/** What `session.resume` reports: the live id it created, and what it loaded. */
@Serializable
data class ResumedSession(
    @SerialName("session_id") val liveId: String = "",
    val resumed: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
)

/** `session.compress` — the headline is already formatted by the server. */
@Serializable
data class CompressResult(
    val status: String = "",
    val removed: Int = 0,
    @SerialName("before_messages") val beforeMessages: Int = 0,
    @SerialName("after_messages") val afterMessages: Int = 0,
    @SerialName("before_tokens") val beforeTokens: Long = 0,
    @SerialName("after_tokens") val afterTokens: Long = 0,
    val summary: CompressSummary? = null,
    val compressed: Boolean = true,
    val message: String? = null,
)

@Serializable
data class CompressSummary(
    val noop: Boolean = false,
    val aborted: Boolean = false,
    val headline: String? = null,
    @SerialName("token_line") val tokenLine: String? = null,
    val note: String? = null,
)
