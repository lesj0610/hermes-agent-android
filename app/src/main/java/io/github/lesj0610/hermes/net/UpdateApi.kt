package io.github.lesj0610.hermes.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A published release, reduced to what an update needs. */
data class Release(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkBytes: Long,
)

/**
 * Reads the project's own GitHub releases.
 *
 * Deliberately its own client rather than the gateway's: that one carries the
 * API token on every request, and this one talks to a third party. A token
 * belongs to the machine it was issued for and must not leave for github.com.
 *
 * Unauthenticated, which is what a public repository needs and keeps the app
 * from ever holding a GitHub credential. The rate limit that applies is 60
 * requests an hour per address — the app spends one per launch.
 */
class UpdateApi(private val repository: String = REPOSITORY) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    /**
     * The latest published release, or null when there is nothing to report.
     *
     * Null covers every ordinary failure — offline, rate-limited, no releases
     * yet, a release with no APK attached. An update check that cannot reach
     * the network is not an error the user needs to see.
     */
    suspend fun latest(): Release? {
        val response = runCatching {
            client.get("https://api.github.com/repos/$repository/releases/latest") {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
        }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null
        if (response.status == HttpStatusCode.NotFound) return null

        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrNull() ?: return null

        if (body.bool("draft") || body.bool("prerelease")) return null

        val tag = body.str("tag_name") ?: return null
        val asset = (body["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            // The first APK attached. Releases here carry exactly one.
            ?.firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?: return null

        val url = asset.str("browser_download_url") ?: return null
        // Only ever github.com. A redirect elsewhere is not followed to an
        // installer, whatever the release says.
        if (!url.startsWith("https://github.com/")) return null

        return Release(
            version = tag.removePrefix("v"),
            tag = tag,
            notes = body.str("body").orEmpty().trim(),
            apkUrl = url,
            apkBytes = (asset["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        )
    }

    fun close() = client.close()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    companion object {
        const val REPOSITORY = "lesj0610/hermes-agent-android"
    }
}

/**
 * Whether [candidate] is newer than [current].
 *
 * Compared field by field as numbers, because the strings do not sort: "1.10"
 * is after "1.9" and before it alphabetically. A field that is not a number
 * compares as zero rather than throwing — a tag is written by a human.
 */
fun isNewer(candidate: String, current: String): Boolean {
    val a = candidate.trim().removePrefix("v").split('.', '-')
    val b = current.trim().removePrefix("v").split('.', '-')
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val right = b.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (left != right) return left > right
    }
    return false
}
