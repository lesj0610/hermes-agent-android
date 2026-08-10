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
        // The socket carries the same cookie as the REST calls, so make sure
        // there is one: a 4401 close is much harder to read than a 401 body.
        runCatching { activeProfile() }
        val target = url("/api/ws").replaceFirst("http", "ws")
        return client.webSocketSession(target).let { session ->
            try {
                block(RpcSession(session, json))
            } finally {
                runCatching { session.close() }
            }
        }
    }

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
