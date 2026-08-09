package io.github.lesj0610.hermes.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Thrown for any non-2xx response, carrying the server's own error text when it sent one. */
class HermesHttpException(
    val status: Int,
    val code: String?,
    override val message: String,
) : Exception(message)

/** Raised when the server rejects the bearer token, so the UI can point at the key instead of the network. */
class HermesUnauthorizedException(override val message: String) : Exception(message)

/**
 * Client for the Hermes gateway api_server platform.
 *
 * Holds no connection state of its own: base URL and token are read per call
 * from whatever the settings layer currently holds, so changing the server in
 * settings takes effect without rebuilding anything.
 */
class HermesApi(
    private val baseUrlProvider: suspend () -> String,
    private val tokenProvider: suspend () -> String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // The event stream is long-lived by design, so only the connect
            // phase is bounded. A dead socket surfaces as a read failure.
            requestTimeoutMillis = null
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = null
        }
    }

    private suspend fun url(path: String): String {
        val base = baseUrlProvider().trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    /** Resolved ahead of each request: Ktor's builder lambda is not a suspend context. */
    private suspend fun bearer(): String? = tokenProvider().takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private suspend fun <T> HttpResponse.decode(decoder: suspend HttpResponse.() -> T): T {
        if (status.isSuccess()) return decoder()
        val raw = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString<ApiErrorEnvelope>(raw) }.getOrNull()
        val message = parsed?.error?.message ?: raw.ifBlank { "HTTP ${status.value}" }
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            throw HermesUnauthorizedException(message)
        }
        throw HermesHttpException(status.value, parsed?.error?.code, message)
    }

    // ── metadata ──────────────────────────────────────────────────────────

    /** Unauthenticated on the server side — usable as a pure reachability probe. */
    suspend fun health(): HealthResponse =
        client.get(url("/health")).decode { body() }

    suspend fun models(): List<ModelEntry> {
        val auth = bearer()
        return client.get(url("/v1/models")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body<ModelListResponse>().data }
    }

    // ── sessions ──────────────────────────────────────────────────────────

    suspend fun sessions(limit: Int = 50, offset: Int = 0): SessionListResponse {
        val auth = bearer()
        return client.get(url("/api/sessions")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            parameter("limit", limit)
            parameter("offset", offset)
        }.decode { body() }
    }

    suspend fun messages(sessionId: String): List<StoredMessage> {
        val auth = bearer()
        return client.get(url("/api/sessions/$sessionId/messages")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body<MessageListResponse>().data }
    }

    // ── operational surface ───────────────────────────────────────────────

    /**
     * What this gateway supports. Read once at connect so panels the server
     * cannot serve are hidden rather than left to fail with 404s.
     */
    suspend fun capabilities(): Capabilities {
        val auth = bearer()
        return client.get(url("/v1/capabilities")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body() }
    }

    suspend fun healthDetailed(): DetailedHealth {
        val auth = bearer()
        return client.get(url("/health/detailed")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body() }
    }

    suspend fun toolsets(): List<Toolset> {
        val auth = bearer()
        return client.get(url("/v1/toolsets")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body<ToolsetListResponse>().data }
    }

    suspend fun skills(): List<Skill> {
        val auth = bearer()
        return client.get(url("/v1/skills")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body<SkillListResponse>().data }
    }

    // ── scheduled jobs ────────────────────────────────────────────────────

    suspend fun jobs(includeDisabled: Boolean = true): List<Job> {
        val auth = bearer()
        return client.get(url("/api/jobs")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            parameter("include_disabled", includeDisabled)
        }.decode { body<JobListResponse>().jobs }
    }

    suspend fun pauseJob(jobId: String) = jobAction(jobId, "pause")

    suspend fun resumeJob(jobId: String) = jobAction(jobId, "resume")

    /** Fires the job now. Does not alter its schedule. */
    suspend fun runJob(jobId: String) = jobAction(jobId, "run")

    private suspend fun jobAction(jobId: String, action: String) {
        val auth = bearer()
        client.post(url("/api/jobs/$jobId/$action")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { }
    }

    suspend fun deleteJob(jobId: String) {
        val auth = bearer()
        client.delete(url("/api/jobs/$jobId")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { }
    }

    // ── runs ──────────────────────────────────────────────────────────────

    suspend fun startRun(prompt: String, sessionId: String?, model: String?): RunStarted {
        val auth = bearer()
        return client.post(url("/v1/runs")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(
                RunRequest(
                    input = listOf(InputMessage(role = "user", content = prompt)),
                    sessionId = sessionId,
                    model = model,
                ),
            )
        }.decode { body() }
    }

    suspend fun runStatus(runId: String): RunStatus {
        val auth = bearer()
        return client.get(url("/v1/runs/$runId")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { body() }
    }

    /**
     * Answers a pending approval. [choice] must be one of the strings the
     * server offered in `approval.request.choices` — the app never invents one.
     */
    suspend fun respondToApproval(runId: String, choice: String) {
        val auth = bearer()
        client.post(url("/v1/runs/$runId/approval")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(ApprovalDecision(choice))
        }.decode { }
    }

    suspend fun stopRun(runId: String) {
        val auth = bearer()
        client.post(url("/v1/runs/$runId/stop")) {
            auth?.let { header(HttpHeaders.Authorization, it) }
        }.decode { }
    }

    /**
     * Streams one run. The flow completes when the server closes the stream;
     * terminal events ([RunEvent.Completed], [RunEvent.Failed],
     * [RunEvent.Cancelled]) are emitted first so callers see why it ended.
     */
    fun runEvents(runId: String): Flow<RunEvent> = flow {
        val endpoint = url("/v1/runs/$runId/events")
        val auth = bearer()
        client.prepareGet(endpoint) {
            auth?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val raw = runCatching { response.bodyAsText() }.getOrDefault("")
                if (response.status == HttpStatusCode.Unauthorized) {
                    throw HermesUnauthorizedException(raw.ifBlank { "Unauthorized" })
                }
                throw HermesHttpException(
                    response.status.value,
                    null,
                    raw.ifBlank { "HTTP ${response.status.value}" },
                )
            }
            response.bodyAsChannel().sseFrames().mapNotNull { frame ->
                runCatching { json.decodeFromString<JsonObject>(frame.data) }.getOrNull()?.let(::parseRunEvent)
            }.collect { emit(it) }
        }
    }
}
