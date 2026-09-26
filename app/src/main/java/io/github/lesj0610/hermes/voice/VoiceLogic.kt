package io.github.lesj0610.hermes.voice

import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.TranscriptItem

/*
 * The parts of voice that decide rather than do: which text is new, what the
 * draft becomes, when to listen again. No Android types here, so every rule
 * below is exercised by plain unit tests.
 */

/** Why speech in or out stopped, in terms the user can act on. */
enum class VoiceFault {
    Network, Audio, Server, Busy, Permission, LanguageUnsupported, LanguageUnavailable,
    NoRecognizer, Recognizer, NoSpeech, SpeechEngine, SpeechData, SpeechLanguage, Speech, TurnRefused,
}

/**
 * The new text of one turn's reply, handed out once.
 *
 * Read from [ChatState], not from events: state is complete whenever it is
 * read, so a reader that falls behind loses nothing, and the turn counters say
 * when the turn is over however it ended — including a socket that never
 * opened, which never makes the chat busy.
 */
class ReplyFollower(val turn: Long) {
    private val consumed = HashMap<String, Int>()
    private var userKey: String? = null
    private var spokeAny = false

    var ended = false
        private set

    data class Update(val text: String, val ended: Boolean)

    fun read(state: ChatState): Update {
        if (ended) return Update("", true)
        if (state.startedTurn < turn) return Update("", false)

        if (userKey == null) {
            // The turn's own message is the newest user row at the moment it
            // started. Seen only after a newer turn began, it cannot be told
            // apart any more — and a superseded reply is not worth reading.
            val key = state.items.lastOrNull { it is TranscriptItem.UserText }?.key
            if (state.startedTurn != turn || key == null) return end()
            userKey = key
        }
        val from = state.items.indexOfFirst { it.key == userKey }
        if (from < 0) return end()

        val text = StringBuilder()
        for (item in state.items.subList(from + 1, state.items.size)) {
            if (item is TranscriptItem.UserText) break
            if (item !is TranscriptItem.AssistantText) continue
            val seen = consumed[item.key]
            val done = seen ?: 0
            // Shorter than already read happens once, when completion lifts
            // `MEDIA:` lines out of the text; all of it was read before that.
            if (item.text.length <= done) continue
            // A new block after tool calls starts on its own line, so a
            // half-finished sentence before it is released rather than joined.
            if (seen == null && spokeAny) text.append('\n')
            text.append(item.text, done, item.text.length)
            consumed[item.key] = item.text.length
            spokeAny = true
        }
        ended = state.endedTurn >= turn || state.startedTurn > turn
        return Update(text.toString(), ended)
    }

    private fun end(): Update {
        ended = true
        return Update("", true)
    }
}

/**
 * Dictation into a draft the user may also be typing into.
 *
 * Partial results replace the span dictation owns; only the final result is
 * kept. What was already typed is never rewritten — dictation goes after it.
 * If the draft changes under dictation, the user has taken over: dictation
 * stops owning anything and the caller cancels recognition, so no late partial
 * or final can overwrite or duplicate what they did.
 *
 * Every method answers the new draft, or null when there is nothing to apply.
 */
class DictationDraft {
    private var base: String? = null
    private var written: String? = null

    val active: Boolean get() = base != null

    fun begin(current: String) {
        base = current
        written = current
    }

    fun partial(current: String, heard: String): String? = write(current, heard, commit = false)

    fun commit(current: String, heard: String): String? = write(current, heard, commit = true)

    /** Recognition ended with nothing final: the provisional words go. */
    fun abandon(current: String): String? {
        val start = base ?: return null
        val untouched = current == written
        release()
        return if (untouched && current != start) start else null
    }

    /** True when [value] is an edit dictation did not make, which ends dictation. */
    fun isUserEdit(value: String): Boolean = active && value != written

    fun release() {
        base = null
        written = null
    }

    private fun write(current: String, heard: String, commit: Boolean): String? {
        val start = base ?: return null
        if (current != written) {
            release()
            return null
        }
        val next = join(start, heard.trim())
        if (commit) release() else written = next
        return if (next != current) next else null
    }

    companion object {
        fun join(base: String, heard: String): String = when {
            heard.isEmpty() -> base
            base.isEmpty() -> heard
            base.last().isWhitespace() -> base + heard
            else -> "$base $heard"
        }
    }
}

/**
 * A spoken conversation as a state machine that performs no I/O.
 *
 *     listen → final result → send → reply streams into speech
 *            → turn ended AND last sentence spoken → listen again
 *
 * Both conditions gate the next listen: the run ending is not the speech
 * ending, and a reply still being read must not have the microphone reopen
 * over it. Every input carries the exchange it belongs to — an epoch for
 * recognition, a turn number for replies and speech — and anything stale is
 * dropped, so a late delta, a callback from a stopped recognizer, or speech
 * from a cancelled reply cannot start playback or listening.
 */
class ConversationLoop(
    private val port: Port,
    private val maxSilentRetries: Int = 2,
) {
    interface Port {
        fun listen(epoch: Long)
        fun stopListening()
        /** Sends [text]; the outcome arrives through [onSent] or [onSendFailed]. */
        fun send(text: String)
        fun speak(turn: Long, text: String)
        fun finishSpeaking(turn: Long)
        fun cancelSpeaking()
        fun notice(fault: VoiceFault)
    }

    enum class Phase { Off, Listening, Sending, Replying, Draining }

    var phase = Phase.Off
        private set
    private var epoch = 0L
    private var turn = 0L
    private var muted = false
    private var silentRetries = 0

    val active: Boolean get() = phase != Phase.Off

    fun start() {
        if (active) return
        silentRetries = 0
        listen()
    }

    fun stop() {
        if (!active) return
        epoch++
        phase = Phase.Off
        turn = 0
        muted = false
        port.stopListening()
        port.cancelSpeaking()
    }

    // ── recognition ───────────────────────────────────────────────────────

    fun onFinal(epoch: Long, text: String) {
        if (!current(epoch, Phase.Listening)) return
        if (text.isBlank()) return onSilence(epoch)
        silentRetries = 0
        phase = Phase.Sending
        port.send(text)
    }

    /** Silence or no match: listened again a limited number of times, then paused. */
    fun onSilence(epoch: Long) {
        if (!current(epoch, Phase.Listening)) return
        if (silentRetries < maxSilentRetries) {
            silentRetries++
            listen()
        } else {
            stopWith(VoiceFault.NoSpeech)
        }
    }

    fun onRecognitionFailed(epoch: Long, fault: VoiceFault) {
        if (!current(epoch, Phase.Listening)) return
        stopWith(fault)
    }

    // ── the turn ──────────────────────────────────────────────────────────

    fun onSent(turn: Long) {
        if (phase != Phase.Sending) return
        this.turn = turn
        muted = false
        phase = Phase.Replying
    }

    fun onSendFailed() {
        if (phase != Phase.Sending) return
        stopWith(VoiceFault.TurnRefused)
    }

    fun onReply(turn: Long, text: String, ended: Boolean) {
        if (turn != this.turn || phase != Phase.Replying) return
        if (text.isNotEmpty() && !muted) port.speak(turn, text)
        if (!ended) return
        phase = Phase.Draining
        // A reply cut short by Stop has nothing left worth hearing.
        if (muted) listen() else port.finishSpeaking(turn)
    }

    /** Every sentence of [turn] has been spoken. */
    fun onSpoken(turn: Long) {
        if (turn != this.turn || phase != Phase.Draining) return
        listen()
    }

    fun onSpeechFailed(turn: Long, fault: VoiceFault) {
        if (turn != this.turn || (phase != Phase.Replying && phase != Phase.Draining)) return
        stopWith(fault)
    }

    /**
     * The user stopped the reply. Speech stops now; the conversation listens
     * again once the turn has ended, without reading the rest.
     */
    fun interruptReply() {
        when (phase) {
            Phase.Replying -> {
                muted = true
                port.cancelSpeaking()
            }
            Phase.Draining -> {
                port.cancelSpeaking()
                listen()
            }
            else -> Unit
        }
    }

    private fun listen() {
        phase = Phase.Listening
        port.listen(++epoch)
    }

    private fun current(epoch: Long, expected: Phase) = epoch == this.epoch && phase == expected

    private fun stopWith(fault: VoiceFault) {
        stop()
        port.notice(fault)
    }
}

/**
 * The recent exchange a spoken turn carries, for the model only.
 *
 * Its first line says how the reply is delivered. The gateway's own note for
 * the surface was written for a voice model that paraphrases the answer; here
 * the phone reads it verbatim, and the model should know it is writing the
 * exact words that will be heard. The note travels on `voice_context`, which
 * reaches the model input only — never the stored user row or the system prompt.
 */
fun voiceContext(items: List<TranscriptItem>, maxLines: Int = 8, maxLineChars: Int = 300): String {
    val lines = ArrayList<String>()
    for (item in items.asReversed()) {
        val line = when (item) {
            is TranscriptItem.UserText -> item.text.takeIf { it.isNotBlank() }?.let { "User: ${it.trim()}" }
            is TranscriptItem.AssistantText -> item.text.takeIf { it.isNotBlank() }?.let { "Assistant: ${it.trim()}" }
            else -> null
        } ?: continue
        lines += if (line.length > maxLineChars) line.take(maxLineChars).trimEnd() + "…" else line
        if (lines.size >= maxLines) break
    }
    return buildString {
        append(VOICE_DELIVERY_NOTE)
        lines.asReversed().forEach { append('\n').append(it.replace('\n', ' ')) }
    }
}

const val VOICE_DELIVERY_NOTE =
    "Delivery: this client reads your reply aloud word for word with the phone's text-to-speech. " +
        "No voice model rephrases it, so write the reply exactly as it should be heard."
