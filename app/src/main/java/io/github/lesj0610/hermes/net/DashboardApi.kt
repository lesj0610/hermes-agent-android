package io.github.lesj0610.hermes.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

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
}
