package io.github.lesj0610.hermes.data

/** One rendered row of a conversation. */
sealed interface TranscriptItem {
    val key: String

    /**
     * What the user sent. [images] are the `data:` URLs that went with it, kept
     * so the turn reads back the way it was written — a message whose picture
     * vanished the moment it was sent gives no sign the picture went at all.
     */
    data class UserText(
        override val key: String,
        val text: String,
        val images: List<String> = emptyList(),
        /**
         * Paths this turn attached, as the gateway persisted them. Present on
         * a reopened session until the pictures themselves have been fetched.
         */
        val imagePaths: List<String> = emptyList(),
    ) : TranscriptItem

    /**
     * Assistant prose. [streaming] stays true while `message.delta` frames are
     * still landing, which is what drives the caret in the UI.
     */
    data class AssistantText(
        override val key: String,
        val text: String,
        val streaming: Boolean,
        /** Pictures the agent produced, once fetched. */
        val images: List<String> = emptyList(),
        /** `MEDIA:` paths this reply referred to, before they are fetched. */
        val imagePaths: List<String> = emptyList(),
    ) : TranscriptItem

    /**
     * A block of the model's thinking.
     *
     * Timed on this device from its first token to the next thing the turn did,
     * the way the desktop measures a block — so a turn that thinks three times
     * reports three durations, not a running total. A block that arrived whole
     * (the HTTP route's single `reasoning.available`, or stored history) was
     * never timed, and says so by having neither end.
     */
    data class Reasoning(
        override val key: String,
        val text: String,
        val startedAtMillis: Long? = null,
        val completedAtMillis: Long? = null,
    ) : TranscriptItem {
        /** Still streaming: it started here and has not been closed. */
        val pending: Boolean get() = startedAtMillis != null && completedAtMillis == null

        /** Whole seconds, or null for a block that was never timed. */
        val durationSeconds: Long?
            get() {
                val start = startedAtMillis ?: return null
                val end = completedAtMillis ?: return null
                return ((end - start) / 1000).coerceAtLeast(0)
            }
    }

    data class ToolCall(
        override val key: String,
        val tool: String,
        val preview: String?,
        val state: ToolState,
        val durationSeconds: Double? = null,
        val error: String? = null,
        /**
         * What the call acted on — a command line, a file, a query — read from
         * its arguments when it started.
         *
         * Separate from [preview] on purpose: that is replaced by the result
         * when the call finishes, and a run summary that names what was done
         * cannot be rebuilt from what it printed.
         */
        val target: String? = null,
        /** A skill load that read one of the skill's files, not its instructions. */
        val readsResource: Boolean = false,
    ) : TranscriptItem {
        val pending: Boolean get() = state == ToolState.Running || state == ToolState.AwaitingApproval
    }

    /** A run-level failure, rendered inline so it cannot be missed. */
    data class Failure(override val key: String, val error: UiError) : TranscriptItem
}

enum class ToolState { Running, AwaitingApproval, Completed, Failed }

/** A pending approval, mirrored straight from `approval.request`. */
data class PendingApproval(
    val runId: String,
    val command: String?,
    /** Rendered verbatim. The app never computes this set. */
    val choices: List<String>,
    val smartDenied: Boolean,
)

/** Where a run currently is. */
sealed interface RunPhase {
    data object Idle : RunPhase
    data class Running(val runId: String) : RunPhase
    data class AwaitingApproval(val runId: String, val approval: PendingApproval) : RunPhase
    data class Stopping(val runId: String) : RunPhase
}

/**
 * An error the UI has to show. Kept as a type rather than a formatted string so
 * the message can be translated at render time — the engine has no Context and
 * must not bake English into state.
 */
sealed interface UiError {
    /** The bearer token was rejected. Points the user at settings, not the network. */
    data object Unauthorized : UiError

    /** The run failed and the server sent no explanation. */
    data object RunFailed : UiError

    /** Server- or platform-authored text, shown as-is. */
    data class Raw(val text: String) : UiError
}

data class ChatState(
    val sessionId: String? = null,
    val items: List<TranscriptItem> = emptyList(),
    val phase: RunPhase = RunPhase.Idle,
    val error: UiError? = null,
    /** Wall-clock start of the current run, for the status bar timer. Null when idle. */
    val runStartedAtMillis: Long? = null,
    /** Token usage reported by the last `run.completed` of this session. */
    val lastUsage: io.github.lesj0610.hermes.net.RunUsage? = null,
) {
    val isBusy: Boolean get() = phase !is RunPhase.Idle
    val pendingApproval: PendingApproval?
        get() = (phase as? RunPhase.AwaitingApproval)?.approval
}

/**
 * Closes whatever is still open at the tail: a reply that was streaming, or a
 * reasoning block that was — which is what stamps the block's duration.
 *
 * Only the tail can be open. Everything before it was closed when the next
 * thing arrived, so this stays constant-time on a long transcript.
 */
internal fun List<TranscriptItem>.finishStreaming(
    now: Long = System.currentTimeMillis(),
): List<TranscriptItem> {
    val last = lastOrNull()
    return when {
        last is TranscriptItem.AssistantText && last.streaming -> dropLast(1) + last.copy(streaming = false)
        last is TranscriptItem.Reasoning && last.pending -> dropLast(1) + last.copy(completedAtMillis = now)
        else -> this
    }
}

/**
 * Place a whole-thought reasoning block, as the HTTP route delivers it.
 *
 * That route sends the thought once, after the answer has already streamed, so
 * appending it puts the thinking below its own conclusion. It goes above the
 * last answer instead, which is the order the desktop shows and the order the
 * socket route produces naturally.
 *
 * A turn that already has reasoning keeps it: on the socket the thought streams
 * as deltas and this event is the echo that follows, and drawing both would say
 * the same thing twice.
 *
 * Both checks are bounded to the current turn — everything after the last user
 * message. Reopened sessions now carry reasoning from earlier turns, and a
 * whole-transcript check would have dropped every new thought because an old
 * one existed, or slotted it above an earlier turn's answer.
 */
fun List<TranscriptItem>.withReasoning(block: TranscriptItem.Reasoning): List<TranscriptItem> {
    val turnStart = indexOfLast { it is TranscriptItem.UserText } + 1
    if (subList(turnStart, size).any { it is TranscriptItem.Reasoning }) return this
    val answer = indexOfLast { it is TranscriptItem.AssistantText }.takeIf { it >= turnStart }
    return if (answer != null) toMutableList().apply { add(answer, block) } else this + block
}

/** A stored turn, split into what was written and what was attached. */
data class StoredUserTurn(val text: String, val imagePaths: List<String>)

/**
 * The directive a user turn stores an attachment under.
 *
 * The gateway persists an attached picture as a path on its own line, caption
 * first and directives last, and the desktop draws them as images.
 */
const val USER_IMAGE_DIRECTIVE = "@image:"

/**
 * The directive an assistant turn stores a produced image under.
 *
 * The agent answers with `MEDIA:<absolute path>` on its own line. The HTTP
 * route rewrites those into base64 data URLs on the way out — the socket does
 * not, and neither does stored history, so the app resolves them itself and
 * gets the same picture on every route.
 */
const val ASSISTANT_MEDIA_DIRECTIVE = "MEDIA:"

/**
 * Pull path directives out of a stored message.
 *
 * Rendering them as prose put a raw filesystem path in the transcript where a
 * picture belonged — not a missing image, a leaked server path.
 *
 * A path containing spaces is wrapped by the writer in whichever of `` ` ``,
 * `"` or `'` it does not itself contain, so all three are unwrapped. Only a
 * line that *begins* with the directive counts: the same characters inside a
 * sentence are prose.
 */
fun parseAttachmentRefs(raw: String, directive: String): StoredUserTurn {
    if (!raw.contains(directive)) return StoredUserTurn(raw, emptyList())
    val kept = mutableListOf<String>()
    val paths = mutableListOf<String>()
    raw.lines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith(directive)) {
            unquote(trimmed.removePrefix(directive).trim())
                .takeIf { it.isNotEmpty() }
                ?.let { paths += it }
        } else {
            kept += line
        }
    }
    return StoredUserTurn(kept.joinToString("\n").trim(), paths)
}

/** The user-turn spelling of [parseAttachmentRefs]. */
fun parseStoredUserTurn(raw: String): StoredUserTurn =
    parseAttachmentRefs(raw, USER_IMAGE_DIRECTIVE)

private fun unquote(value: String): String {
    for (quote in listOf('`', '"', '\'')) {
        if (value.length >= 2 && value.first() == quote && value.last() == quote) {
            return value.substring(1, value.length - 1)
        }
    }
    return value
}
