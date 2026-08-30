package io.github.lesj0610.hermes.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

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
class DashboardApi(
    private val baseUrlProvider: suspend () -> String,
    private val credentialsProvider: suspend () -> Pair<String, String>,
) {
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
        // The session cookie does not carry the upgrade: the server checks a
        // query credential on /api/ws and refuses the handshake with a plain
        // 403 before any close code is visible. Browsers cannot set headers on
        // a WebSocket upgrade either, which is why the dashboard mints a
        // one-shot ticket for exactly this — 30 seconds, single use, so it is
        // minted per socket rather than cached.
        val ticket = wsTicket()
        val target = buildString {
            append(url("/api/ws").replaceFirst("http", "ws"))
            ticket?.let { append("?ticket=").append(it) }
        }
        val session = try {
            client.webSocketSession(target)
        } catch (cause: Exception) {
            // The handshake failure alone reads as a network fault. Say which
            // credential was missing, since that is the fixable part.
            if (ticket == null) {
                throw DashboardAuthException(
                    "The dashboard did not issue a WebSocket ticket, so projects cannot be " +
                        "reached. Sign in to the dashboard, or check that it runs with auth enabled.",
                )
            }
            throw cause
        }
        return try {
            block(RpcSession(session, json))
        } finally {
            runCatching { session.close() }
        }
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
     * Starts a turn on the gateway's event socket and hands back the live run.
     *
     * The socket stays open for the turn: this is the one call that holds it
     * rather than opening it per batch, because the events *are* the turn.
     *
     * A stored session must be resumed into a live one first, and resume
     * answers with a different id than it was given — passing the stored id
     * onward is how `session.compress` earned a "session not found".
     */
    suspend fun startSocketRun(storedSessionId: String?, text: String): SocketRun {
        val ticket = wsTicket()
        val target = buildString {
            append(url("/api/ws").replaceFirst("http", "ws"))
            ticket?.let { append("?ticket=").append(it) }
        }
        val ws = try {
            client.webSocketSession(target)
        } catch (cause: Exception) {
            if (ticket == null) {
                throw DashboardAuthException(
                    "The dashboard did not issue a WebSocket ticket, so the conversation " +
                        "cannot run over it.",
                )
            }
            throw cause
        }

        val rpc = RpcSession(ws, json)
        val live = if (storedSessionId.isNullOrBlank()) {
            rpc.call<JsonObject>("session.create", buildJsonObject { })
                .let { (it["session_id"] as? JsonPrimitive)?.content }
        } else {
            rpc.call<ResumedSession>(
                "session.resume",
                buildJsonObject { put("session_id", storedSessionId) },
            ).liveId
        }
        if (live.isNullOrBlank()) {
            runCatching { ws.close() }
            throw GatewayRpcException(-1, "The gateway did not return a live session")
        }

        val run = SocketRun(ws, json, live)
        // Fire and forget: the answer to prompt.submit is the event stream, not
        // its return value, and waiting for the reply would block the reader.
        ws.send(
            Frame.Text(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "prompt.submit")
                        put(
                            "params",
                            buildJsonObject {
                                put("session_id", live)
                                put("text", text)
                            },
                        )
                    },
                ),
            ),
        )
        return run
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

/**
 * One open WebSocket, able to issue JSON-RPC calls on it.
 *
 * Frames that are not the reply being waited for are events — the same socket
 * carries the gateway's live broadcasts — and are skipped rather than treated
 * as protocol errors.
 */
internal class RpcSession(
    private val session: DefaultClientWebSocketSession,
    @PublishedApi internal val codec: Json,
) {
    private var nextId = 1

    suspend inline fun <reified T> call(method: String, params: JsonObject): T =
        decode(callRaw(method, params))

    /** Exposed so the inline [call] can reach the private codec. */
    inline fun <reified T> decode(element: JsonElement): T =
        codec.decodeFromJsonElement(serializer(), element)

    suspend fun callRaw(method: String, params: JsonObject): JsonElement {
        val id = nextId++
        session.send(
            Frame.Text(
                codec.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("method", method)
                        put("params", params)
                    },
                ),
            ),
        )

        while (true) {
            val frame = session.incoming.receive() as? Frame.Text ?: continue
            val message = runCatching {
                codec.parseToJsonElement(frame.readText()).jsonObject
            }.getOrNull() ?: continue
            val replyId = (message["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            if (replyId != id) continue

            (message["error"] as? JsonObject)?.let { error ->
                throw GatewayRpcException(
                    code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
                    message = (error["message"] as? JsonPrimitive)?.content
                        ?: "RPC $method failed",
                )
            }
            return message["result"] ?: JsonObject(emptyMap())
        }
    }
}
