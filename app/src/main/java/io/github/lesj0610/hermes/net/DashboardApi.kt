package io.github.lesj0610.hermes.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The dashboard refused the credentials, or the session could not be established. */
class DashboardAuthException(override val message: String) : Exception(message)

/** Login attempts are throttled per client IP (10 per 60s). Backing off is the only fix. */
class DashboardRateLimitedException(override val message: String) : Exception(message)

/**
 * Client for the dashboard server.
 *
 * Auth is a cookie session, not a bearer token: log in once at
 * `/auth/password-login`, then the cookie rides along. Two consequences shape
 * this class:
 *
 *  - A cookie storage is required, so the client keeps its own rather than
 *    sharing [HermesApi]'s.
 *  - A 401 mid-session means the cookie expired, so calls re-authenticate once
 *    and retry. Exactly once: the server rate-limits password logins to 10 per
 *    minute per IP, and a retry loop would spend that budget and lock the user
 *    out of their own dashboard.
 */
class DashboardApi internal constructor(
    private val baseUrlProvider: suspend () -> String,
    private val credentialsProvider: suspend () -> Pair<String, String>,
    /** Opens the event socket. Replaced in tests by an in-process gateway. */
    private val socketOpener: (suspend () -> FrameTransport)?,
    /** The bound on each cleanup send and wait. */
    private val cleanupTimeoutMillis: Long,
    /** The bound on each setup request — resume, a setting, an attachment, the submit. */
    private val setupTimeoutMillis: Long = SETUP_TIMEOUT_MILLIS,
) {
    constructor(
        baseUrlProvider: suspend () -> String,
        credentialsProvider: suspend () -> Pair<String, String>,
    ) : this(baseUrlProvider, credentialsProvider, null, CLOSE_TIMEOUT_MILLIS)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpCookies) { storage = AcceptAllCookiesStorage() }
        // Projects live behind the dashboard's JSON-RPC socket; there is no REST
        // route for them on either server.
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }

    /** Serialises logins so a burst of parallel 401s cannot fire several at once. */
    private val loginMutex = Mutex()

    private suspend fun url(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    suspend fun login() {
        loginMutex.withLock { doLogin() }
    }

    private suspend fun doLogin() {
        val (username, password) = credentialsProvider()
        if (username.isBlank() || password.isBlank()) {
            throw DashboardAuthException("No dashboard credentials configured")
        }
        val response = client.post(url("/auth/password-login")) {
            contentType(ContentType.Application.Json)
            setBody(PasswordLoginRequest(username = username, password = password))
        }
        when {
            response.status.isSuccess() -> {
                val body: PasswordLoginResponse = response.body()
                if (!body.ok) throw DashboardAuthException("Login rejected")
            }
            response.status == HttpStatusCode.TooManyRequests ->
                throw DashboardRateLimitedException("Too many login attempts; wait a minute")
            response.status == HttpStatusCode.NotFound ->
                throw DashboardAuthException("No password provider on this dashboard")
            else -> throw DashboardAuthException(
                runCatching { response.bodyAsText() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "Login failed (HTTP ${response.status.value})",
            )
        }
    }

    /**
     * Runs [call], logging in once if the session is missing or expired.
     *
     * The retry is deliberately not a loop — see the class note about the
     * server-side login rate limit.
     */
    private suspend fun <T> authed(call: suspend () -> HttpResponse, decode: suspend HttpResponse.() -> T): T {
        var response = call()
        if (response.status == HttpStatusCode.Unauthorized) {
            loginMutex.withLock { doLogin() }
            response = call()
        }
        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.Unauthorized) {
                throw DashboardAuthException("Dashboard rejected the session")
            }
            val detail = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            throw HermesHttpException(
                response.status.value,
                null,
                detail.ifBlank { "HTTP ${response.status.value}" },
            )
        }
        return response.decode()
    }

    // ── profiles ──────────────────────────────────────────────────────────

    suspend fun profiles(): List<Profile> =
        authed({ client.get(url("/api/profiles")) }) { body<ProfileListResponse>().profiles }

    suspend fun activeProfile(): ActiveProfile =
        authed({ client.get(url("/api/profiles/active")) }) { body() }

    /**
     * Sets the sticky active profile. Does not retarget a running gateway —
     * the server is explicit about that, and the UI says so.
     */
    suspend fun setActiveProfile(name: String) {
        authed({
            client.post(url("/api/profiles/active")) {
                contentType(ContentType.Application.Json)
                setBody(ProfileActiveUpdate(name))
            }
        }) { }
    }

    // ── skills ────────────────────────────────────────────────────────────

    /** Returns a bare array rather than an envelope. */
    suspend fun skills(): List<DashboardSkill> =
        authed({ client.get(url("/api/skills")) }) { body() }

    suspend fun toggleSkill(name: String, enabled: Boolean) {
        authed({
            client.put(url("/api/skills/toggle")) {
                contentType(ContentType.Application.Json)
                setBody(SkillToggleRequest(name = name, enabled = enabled))
            }
        }) { }
    }

    // ── gateway filesystem ────────────────────────────────────────────────

    /**
     * Lists a directory on the gateway host.
     *
     * A project's folders are that machine's paths, so they are picked by
     * browsing it rather than with an Android picker, which can only see this
     * phone.
     */
    suspend fun fsList(path: String): FsListResponse =
        authed({ client.get(url("/api/fs/list")) { parameter("path", path) } }) { body() }

    /**
     * Read a file back as a `data:` URL, for a picture a past turn attached.
     *
     * The gateway stores an attachment on its own filesystem and leaves an
     * `@image:<path>` directive in the message, so reopening a conversation has
     * a path and no pixels. This is the route that turns one back into the
     * other; the dashboard caps the size and answers 413 above it, which is
     * treated the same as any other miss.
     */
    suspend fun readDataUrl(path: String): String? = runCatching {
        authed({ client.get(url("/api/fs/read-data-url")) { parameter("path", path) } }) {
            (body<JsonObject>()["dataUrl"] as? JsonPrimitive)?.content
        }
    }.getOrNull()

    suspend fun fsWriteText(path: String, content: String) {
        authed({
            client.post(url("/api/fs/write-text")) {
                contentType(ContentType.Application.Json)
                setBody(FsWriteTextRequest(path = path, content = content))
            }
        }) { }
    }

    // ── projects (JSON-RPC over /api/ws) ──────────────────────────────────

    /**
     * Runs one or more JSON-RPC calls over a single WebSocket session.
     *
     * Opened per batch rather than held open. A persistent socket would need
     * reconnect and backoff handling for a screen that is visited occasionally,
     * and the calls here are user-initiated one at a time; the connection cost
     * is paid where it is visible instead of running a background socket for
     * the life of the app.
     *
     * The server opens with a `gateway.ready` event before it will answer, and
     * emits unrelated events on the same socket throughout, so replies are
     * matched by request id rather than by arrival order.
     */
    private suspend fun <T> rpcSession(block: suspend (RpcSession) -> T): T {
        val transport = openSocket(
            "The dashboard did not issue a WebSocket ticket, so projects cannot be " +
                "reached. Sign in to the dashboard, or check that it runs with auth enabled.",
        )
        return try {
            block(RpcSession(transport, json))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(cleanupTimeoutMillis) { transport.close() }
            }
        }
    }

    /**
     * Opens the dashboard's JSON-RPC socket.
     *
     * The session cookie does not carry the upgrade: the server checks a query
     * credential on /api/ws and refuses the handshake with a plain 403 before
     * any close code is visible. Browsers cannot set headers on a WebSocket
     * upgrade either, which is why the dashboard mints a one-shot ticket for
     * exactly this — 30 seconds, single use, so it is minted per socket rather
     * than cached. [noTicket] is what to say when there was none, since that is
     * the fixable part of a failed handshake.
     */
    private suspend fun openSocket(noTicket: String): FrameTransport {
        socketOpener?.let { return it() }
        val ticket = wsTicket()
        val target = buildString {
            append(url("/api/ws").replaceFirst("http", "ws"))
            ticket?.let { append("?ticket=").append(it) }
        }
        val session = try {
            client.webSocketSession(target)
        } catch (cause: Exception) {
            if (ticket == null) throw DashboardAuthException(noTicket)
            throw cause
        }
        return WebSocketTransport(session)
    }

    /**
     * A single-use credential for one WebSocket upgrade.
     *
     * Null when the dashboard has no ticket route — an ungated loopback
     * dashboard authenticates the socket with its own process token instead,
     * which no external client is given.
     */
    private suspend fun wsTicket(): String? = runCatching {
        authed({ client.post(url("/api/auth/ws-ticket")) }) { body<WsTicketResponse>().ticket }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun projects(): ProjectsPayload =
        rpcSession { it.call("projects.list", buildJsonObject { }) }

    suspend fun createProject(
        name: String,
        description: String?,
        folders: List<String>,
        primaryPath: String?,
    ): Project? = rpcSession { session ->
        session.call<ProjectEnvelope>(
            "projects.create",
            buildJsonObject {
                put("name", name)
                description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                put("folders", buildJsonArray { folders.forEach { add(it) } })
                primaryPath?.takeIf { it.isNotBlank() }?.let { put("primary_path", it) }
            },
        ).project
    }

    // ── slash commands ────────────────────────────────────────────────────

    /** The command set, for the palette. No session needed. */
    suspend fun commandsCatalog(): CommandCatalog =
        rpcSession { it.call("commands.catalog", buildJsonObject { }) }

    /**
     * Compresses a stored session's history.
     *
     * Three calls, because compression acts on a live agent and the app's
     * sessions are rows in a database:
     *
     *   session.resume(stored id) → a live session under a NEW id
     *   session.compress(that id) → summarises, and writes through to the store
     *   session.close(that id)    → or the live session is left behind
     *
     * Verified end to end against a gateway: 41 stored messages became 13, and
     * the change was visible through the gateway's own messages route
     * afterwards, which is what the next turn reads.
     *
     * Slow by nature — it builds an agent and then calls a model — so the
     * caller is expected to show progress rather than block silently.
     */
    suspend fun compressSession(storedSessionId: String): CompressResult = rpcSession { session ->
        val resumed: ResumedSession = session.call(
            "session.resume",
            buildJsonObject { put("session_id", storedSessionId) },
        )
        val live = resumed.liveId.takeIf { it.isNotBlank() }
            ?: throw GatewayRpcException(-1, "The gateway did not return a live session")
        try {
            session.call<CompressResult>(
                "session.compress",
                buildJsonObject { put("session_id", live) },
            )
        } finally {
            // Best effort: a live session left open is a leak on the agent's
            // host, and there is nothing useful to tell the user if the
            // close itself fails.
            runCatching {
                session.call<JsonObject>(
                    "session.close",
                    buildJsonObject { put("session_id", live) },
                )
            }
        }
    }

    /**
     * Runs a skill, quick or plugin command.
     *
     * `command.dispatch` needs no session, but it is not a query: dispatching
     * runs the command, model calls and all. The built-in registry commands are
     * not dispatchable — the server answers "not a quick/plugin/bundle/skill
     * command" — because those are things the desktop's own UI draws rather
     * than work the gateway performs.
     */
    suspend fun dispatchCommand(name: String, arg: String = ""): JsonElement =
        rpcSession {
            it.callRaw(
                "command.dispatch",
                buildJsonObject {
                    put("name", name.removePrefix("/"))
                    put("arg", arg)
                },
            )
        }

    /**
     * Whether the gateway still holds live session [liveId]; null when it could
     * not tell.
     *
     * Read from the process's live list, which attaches to nothing. A resume
     * would reattach the session, and a session a client holds is never let go
     * — each look would restart the wait for it to end. The list includes a
     * session no client holds that has not been reaped yet: that one can still
     * be resumed, so it still counts.
     */
    suspend fun liveSessionOpen(liveId: String): Boolean? {
        val result = try {
            withTimeoutOrNull(setupTimeoutMillis) {
                rpcSession { it.callRaw("session.active_list", buildJsonObject { }) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val rows = (result as? JsonObject)?.get("sessions") as? JsonArray ?: return null
        return rows.any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content == liveId }
    }

    /**
     * Starts a turn on the gateway's event socket and hands back the live run.
     *
     * The socket stays open for the turn: this is the one call that holds it
     * rather than opening it per batch, because the events *are* the turn.
     *
     * A stored session must be resumed into a live one first, and resume
     * answers with a different id than it was given — passing the stored id
     * onward is how `session.compress` earned a "session not found".
     */
    suspend fun startSocketRun(
        storedSessionId: String?,
        text: String,
        images: List<String> = emptyList(),
        /**
         * The model, provider and reasoning level the turn runs with. The HTTP
         * route carries these on every request; the socket used to drop them,
         * so the composer's picks only ever applied to turns that went over HTTP.
         */
        runtime: TurnRuntime = TurnRuntime.DEFAULT,
        /** Present on a turn spoken in a conversation. */
        voice: VoiceTurn? = null,
        /**
         * What cleanup achieved when the turn fails before it is running. The
         * thrown error stays the original one; a restore that did not happen,
         * or a change left unanswered, is reported here, separately, rather
         * than replacing it.
         */
        onCleanup: (Cleanup) -> Unit = {},
        /** A live session this turn must not run on; see [prepareSocketTurn]. */
        heldLiveId: String? = null,
    ): SocketRun {
        val transport = openSocket(
            "The dashboard did not issue a WebSocket ticket, so the conversation cannot run over it.",
        )
        val rpc = RpcSession(transport, json)
        // Holds the turn's gateway resources from the start, so a failure at any
        // step below still closes what was opened and restores what was changed.
        val live = LiveTurn(transport, rpc, cleanupTimeoutMillis)
        try {
            // Every setup request is bounded. A setup that hangs would hold the
            // turn open with nothing to stop, so no answer in time is a failure —
            // an ordinary one, never a cancellation, so the turn still ends.
            suspend fun <T : Any> timed(what: String, request: suspend () -> T): T =
                withTimeoutOrNull(setupTimeoutMillis) { request() }
                    ?: throw GatewayTimeoutException("The gateway did not answer $what in time")

            val prepared = prepareSocketTurn(
                rpc = { method, params -> timed(method) { rpc.callRaw(method, params) } },
                storedSessionId = storedSessionId,
                runtime = runtime,
                voice = voice,
                onLive = { live.liveId = it },
                onStored = { live.storedId = it },
                onRestorePlan = { live.restoreReasoning = it },
                heldLiveId = heldLiveId,
            )

            // Attachments go first, and each one is awaited: `image.attach_bytes`
            // stages the picture on the session, and `prompt.submit` sends
            // whatever is staged when it arrives. Submitting first would send the
            // turn without them.
            //
            // This is the RPC the gateway documents for remote clients, which is
            // what makes a picture usable here at all: the HTTP route carries
            // images but emits no reasoning stream and no tool events, so a turn
            // with an attachment used to arrive as a bare answer.
            images.forEachIndexed { index, dataUrl ->
                timed("image.attach_bytes") {
                    rpc.callRaw(
                        "image.attach_bytes",
                        buildJsonObject {
                            put("session_id", prepared.liveId)
                            // The data URL whole: the server reads the mime prefix off it.
                            put("content_base64", dataUrl)
                            put("filename", "attachment-${index + 1}.jpg")
                        },
                    )
                }
            }

            // Fire and forget: the answer to prompt.submit is the event stream,
            // not its return value, and waiting for the reply would block the
            // reader.
            // Its answer is read with the events, so a refusal still ends the turn.
            val submitId = timed("prompt.submit") {
                rpc.send("prompt.submit", promptSubmitParams(prepared.liveId, text, voice))
            }
            return SocketRun(transport, json, rpc, live, prepared.liveId, submitId)
        } catch (cause: Throwable) {
            onCleanup(live.finish())
            throw cause
        }
    }

    /** Reads a config key — `reasoning` answers with the level in force. */
    suspend fun configGet(key: String): JsonElement =
        rpcSession { it.callRaw("config.get", buildJsonObject { put("key", key) }) }

    /**
     * Runs a read-only informational RPC and returns its raw result.
     *
     * These take no session and mutate nothing — `tools.list`, `plugins.list`,
     * `agents.list`, `config.show` and friends — so the palette can render
     * their output without the resume dance.
     */
    suspend fun readOnlyRpc(method: String): JsonElement =
        rpcSession { it.callRaw(method, buildJsonObject { }) }

    suspend fun renameProject(id: String, name: String): ProjectsPayload = rpcSession { session ->
        session.call<JsonObject>(
            "projects.update",
            buildJsonObject {
                put("id", id)
                put("name", name)
            },
        )
        session.call("projects.list", buildJsonObject { })
    }

    suspend fun setActiveProject(id: String): ProjectsPayload = rpcSession { session ->
        session.call<JsonObject>("projects.set_active", buildJsonObject { put("id", id) })
        session.call("projects.list", buildJsonObject { })
    }

    suspend fun archiveProject(id: String, archived: Boolean): ProjectsPayload =
        rpcSession { session ->
            session.call<JsonObject>(
                "projects.archive",
                buildJsonObject {
                    put("id", id)
                    put("archived", archived)
                },
            )
            session.call("projects.list", buildJsonObject { })
        }
}

/** The dashboard answered, but the RPC itself failed. */
class GatewayRpcException(val code: Int, override val message: String) : Exception(message)

/** The bound on each cleanup send and each wait for its answer. */
internal const val CLOSE_TIMEOUT_MILLIS = 3_000L

/**
 * The bound on each setup request. Generous: a model switch builds an agent,
 * and an attachment is megabytes over a phone connection.
 */
internal const val SETUP_TIMEOUT_MILLIS = 60_000L
