package io.github.lesj0610.hermes.data

/** One rendered row of a conversation. */
sealed interface TranscriptItem {
    val key: String

    data class UserText(override val key: String, val text: String) : TranscriptItem

    /**
     * Assistant prose. [streaming] stays true while `message.delta` frames are
     * still landing, which is what drives the caret in the UI.
     */
    data class AssistantText(
        override val key: String,
        val text: String,
        val streaming: Boolean,
    ) : TranscriptItem

    data class Reasoning(override val key: String, val text: String) : TranscriptItem

    data class ToolCall(
        override val key: String,
        val tool: String,
        val preview: String?,
        val state: ToolState,
        val durationSeconds: Double? = null,
        val error: String? = null,
    ) : TranscriptItem

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
 * True when [text] only repeats assistant prose already in the transcript.
 *
 * The server truncates its reasoning relay to 500 characters, so the repeat is
 * a prefix rather than an exact match — comparing for equality would never
 * catch it. Whitespace is normalised because the relay strips reasoning tags
 * before sending, which collapses lines the streamed copy still has.
 *
 * Only the last assistant message is considered: an earlier one repeating
 * itself is not what this guards against, and scanning the whole transcript
 * would suppress a genuine restatement.
 */
fun List<TranscriptItem>.echoesReasoning(text: String): Boolean {
    val candidate = text.normalizedForEcho()
    if (candidate.isEmpty()) return false
    val lastAssistant = lastOrNull { it is TranscriptItem.AssistantText }
        as? TranscriptItem.AssistantText ?: return false
    val spoken = lastAssistant.text.normalizedForEcho()
    if (spoken.isEmpty()) return false

    // Only one direction. The relay truncates at 500 characters, so a repeat is
    // always a prefix OF the reply — never the other way round.
    //
    // Testing the reverse as well was wrong and silenced reasoning entirely:
    // while a reply is still short, almost any text "starts with" it, so every
    // intermediate narration matched something and was dropped.
    if (!spoken.startsWith(candidate)) return false

    // A handful of characters matching proves nothing — "네" is the start of
    // countless sentences. Below that, keep the block.
    return candidate.length >= ECHO_MIN_CHARS
}

/** Short agreements collide by chance; a real repeat is far longer. */
private const val ECHO_MIN_CHARS = 24

private fun String.normalizedForEcho(): String =
    trim().replace(Regex("\\s+"), " ")
