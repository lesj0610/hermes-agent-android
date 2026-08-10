package io.github.lesj0610.hermes.ui.artifacts

import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.StoredMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a run left behind: the images, files and links mentioned in a session.
 *
 * There is no artifacts route on the gateway. The desktop client does not use
 * one either — it reads the session's own messages and picks out anything that
 * looks like a produced thing (see `apps/desktop/src/app/artifacts/`). The rules
 * below are that logic, ported, so both clients call the same things artifacts.
 *
 * Kept as plain functions with no Android or network types, which is what lets
 * the classification be tested directly.
 */

enum class ArtifactKind { Image, File, Link }

data class Artifact(
    val id: String,
    val kind: ArtifactKind,
    /** The path or URL as it appeared in the transcript. */
    val value: String,
    /** Last path segment — the file name, which is what identifies it in a list. */
    val label: String,
    val sessionId: String,
    val sessionTitle: String,
    val timestamp: String?,
) {
    /** True when the value is reachable by the phone on its own. */
    val remote: Boolean
        get() = value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("data:", true)
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
private val URL_RE = Regex("""https?://[^\s<>"')]+""")
private val PATH_RE = Regex(
    """(^|[\s("'`])((?:/|~/|\.\.?/)[^\s"'`<>]+(?:\.[a-z0-9]{1,8})?)""",
    RegexOption.IGNORE_CASE,
)
private val IMAGE_EXT = Regex("""\.(?:png|jpe?g|gif|webp|svg|bmp)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
private val FILE_EXT = Regex(
    """\.(?:png|jpe?g|gif|webp|svg|bmp|pdf|txt|json|md|csv|zip|tar|gz|mp3|wav|mp4|mov)(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)
private val KEY_HINT = Regex(
    "(path|file|url|image|artifact|output|download|result|target)",
    RegexOption.IGNORE_CASE,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Trailing punctuation belongs to the sentence, not to the path. */
private fun normalizeValue(value: String): String =
    value.trim().trimEnd(')', ',', '.', ';')

private fun looksLikePathOrUrl(value: String): Boolean =
    value.startsWith("http://") || value.startsWith("https://") ||
        value.startsWith("file://") || value.startsWith("data:image/") ||
        value.startsWith("/") || value.startsWith("./") ||
        value.startsWith("../") || value.startsWith("~/")

internal fun looksLikeArtifact(value: String): Boolean {
    if (Regex("""^(?:https?://|data:image/)""").containsMatchIn(value)) return true
    if (looksLikePathOrUrl(value) &&
        (IMAGE_EXT.containsMatchIn(value) || FILE_EXT.containsMatchIn(value))
    ) {
        return true
    }
    // An absolute path with a dot in it: extensions this list does not know
    // about are still files the run produced.
    return value.startsWith("/") && value.contains(".")
}

internal fun artifactKind(value: String): ArtifactKind = when {
    value.startsWith("data:image/") || IMAGE_EXT.containsMatchIn(value) -> ArtifactKind.Image
    value.startsWith("/") || value.startsWith("./") || value.startsWith("../") ||
        value.startsWith("~/") || value.startsWith("file://") -> ArtifactKind.File
    else -> ArtifactKind.Link
}

internal fun artifactLabel(value: String): String {
    val withoutQuery = value.substringBefore('?')
    return withoutQuery.split('/', '\\').lastOrNull { it.isNotBlank() } ?: value
}

/**
 * Every artifact in one session's messages, deduplicated by value.
 *
 * Only assistant and tool messages are read: what the user typed is the request,
 * not the result, and scanning it would list every path the user mentioned as
 * though the run had produced it.
 */
fun collectArtifacts(
    session: SessionSummary,
    messages: List<StoredMessage>,
): List<Artifact> {
    val found = LinkedHashMap<String, Artifact>()
    val title = session.title?.takeIf { it.isNotBlank() }
        ?: session.preview?.takeIf { it.isNotBlank() }
        ?: session.id

    messages.forEach { message ->
        val role = message.role?.lowercase()
        if (role != "assistant" && role != "tool") return@forEach

        val push = push@{ candidate: String ->
            val value = normalizeValue(candidate)
            if (value.isBlank() || !looksLikeArtifact(value)) return@push
            val key = "${session.id}:$value"
            if (found.containsKey(key)) return@push
            found[key] = Artifact(
                id = key,
                kind = artifactKind(value),
                value = value,
                label = artifactLabel(value),
                sessionId = session.id,
                sessionTitle = title,
                timestamp = message.timestamp ?: session.lastActive ?: session.startedAt,
            )
        }

        val text = message.text
        if (text.isNotBlank()) {
            collectFromText(text, push)
            // Tool results arrive as a JSON blob in the same field. Walking it
            // catches paths that live in a key rather than in prose — which is
            // where a write tool reports what it wrote.
            collectFromJson(text, push)
        }
    }

    return found.values.toList()
}

private fun collectFromText(text: String, push: (String) -> Unit) {
    MARKDOWN_IMAGE.findAll(text).forEach { push(it.groupValues[2]) }

    MARKDOWN_LINK.findAll(text).forEach { match ->
        // An image is already handled above and its link form starts one
        // character earlier; without this every image is listed twice.
        if (match.range.first > 0 && text[match.range.first - 1] == '!') return@forEach
        val value = match.groupValues[2]
        if (looksLikeArtifact(value)) push(value)
    }

    URL_RE.findAll(text).forEach { if (looksLikeArtifact(it.value)) push(it.value) }
    PATH_RE.findAll(text).forEach { push(it.groupValues[2]) }
}

private fun collectFromJson(text: String, push: (String) -> Unit) {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return
    val parsed = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrNull() ?: return

    fun walk(node: kotlinx.serialization.json.JsonElement, keyPath: String) {
        when (node) {
            is JsonPrimitive -> {
                if (!node.isString) return
                val value = normalizeValue(node.content)
                if (value.isBlank()) return
                if ((KEY_HINT.containsMatchIn(keyPath) || looksLikePathOrUrl(value)) &&
                    looksLikeArtifact(value)
                ) {
                    push(value)
                }
            }
            is JsonArray -> node.forEachIndexed { index, child -> walk(child, "$keyPath.$index") }
            is JsonObject -> node.forEach { (key, child) ->
                walk(child, if (keyPath.isEmpty()) key else "$keyPath.$key")
            }
            else -> Unit
        }
    }

    walk(parsed, "")
}
