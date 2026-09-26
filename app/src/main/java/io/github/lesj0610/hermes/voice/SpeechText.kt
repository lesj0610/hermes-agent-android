package io.github.lesj0610.hermes.voice

/**
 * A reply as it streams, turned into sentences that can be spoken.
 *
 * Speech has to start before the reply ends — that is the point of a spoken
 * conversation — so text is released a sentence at a time, as soon as a
 * sentence is known to be finished. Three things decide that:
 *
 *  - a boundary: terminal punctuation followed by whitespace, or a line end;
 *  - a pause: with nothing new for [settleMillis], text ending on terminal
 *    punctuation is released, and after [stallMillis] it is released whatever
 *    it ends on — a narration line with no full stop ("잠깐 확인해 볼게요")
 *    must not wait for the end of the turn;
 *  - a length cap: past [maxChars] without a boundary, the text is cut at the
 *    last space, or at the cap itself when there is none — a long unbroken run
 *    of CJK, or an address — so nothing waits indefinitely.
 *
 * What is released is cleaned for the ear; the transcript keeps the original.
 * Markdown is read the way a person reads it aloud: link text without the
 * address, emphasis without its marks, code blocks and tables skipped, and the
 * model's `<think>` blocks and `MEDIA:` directives never spoken. A construct
 * split across deltas is held until it closes, so half a link is never read.
 *
 * Not thread-safe: one stream per reply, driven from one thread.
 */
class SpeechTextStream(
    /** Said once where a code block starts, or nothing when null. */
    private val codeNote: String? = null,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    private val stallMillis: Long = DEFAULT_STALL_MILLIS,
) {
    private val pending = StringBuilder()

    /** Whether [pending] starts at the beginning of a line, where line syntax applies. */
    private var pendingAtLineStart = true
    private var inFence = false
    private var inThink = false
    private var lastInputAt = 0L

    /** Text is waiting for a boundary, a pause or the end. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    /** Feeds a delta; answers the sentences it completed. */
    fun append(delta: String, now: Long): List<String> {
        if (delta.isEmpty()) return emptyList()
        pending.append(delta)
        lastInputAt = now
        return drain(Release.Boundary)
    }

    /** Called periodically while text is pending; releases it after a pause. */
    fun onTick(now: Long): List<String> {
        if (pending.isEmpty()) return emptyList()
        val idle = now - lastInputAt
        return when {
            idle >= stallMillis -> drain(Release.All)
            idle >= settleMillis -> drain(Release.Settled)
            else -> emptyList()
        }
    }

    /** The reply is over: everything left, and the stream reset. */
    fun finish(): List<String> {
        val out = drain(Release.All)
        pending.clear()
        pendingAtLineStart = true
        inFence = false
        inThink = false
        return out
    }

    private enum class Release { Boundary, Settled, All }

    private fun drain(release: Release): List<String> {
        val out = ArrayList<String>()
        // Whole lines first: a line end is always a boundary.
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline)
            pending.delete(0, newline + 1)
            out += sentences(speakLine(line, pendingAtLineStart))
            pendingAtLineStart = true
        }
        // A tail far past the cap is cut again until it fits: one cut per delta
        // would let an unbroken run fall further behind with every token.
        while (releaseTail(release, out) && release != Release.All && pending.length > maxChars) Unit
        return out
    }

    /**
     * The unfinished last line: released up to its last safe boundary, or held.
     * Adds what it releases to [out]; answers whether it consumed any text.
     */
    private fun releaseTail(release: Release, out: MutableList<String>): Boolean {
        if (pending.isEmpty()) return false
        val all = release == Release.All

        // Inside code nothing is spoken, but the line must be kept whole until
        // it ends: it may be the fence that closes the block.
        if (inFence) {
            if (all) pending.clear()
            return all
        }
        if (inThink) {
            val close = pending.indexOf(THINK_CLOSE)
            if (close < 0) {
                // Thinking is never spoken. Keep only what could be the start of
                // the closing tag.
                if (all) {
                    pending.clear()
                } else if (pending.length > THINK_CLOSE.length) {
                    pending.delete(0, pending.length - THINK_CLOSE.length)
                }
                return false
            }
            inThink = false
            pending.delete(0, close + THINK_CLOSE.length)
            pendingAtLineStart = false
            releaseTail(release, out)
            return true
        }

        val tail = pending.toString()
        // A line that may still turn into a fence, a table row or a directive is
        // held until it says which; the whole line decides how it is read.
        if (pendingAtLineStart && mayBeLineSyntax(tail) && !all) return false

        val cut = when (release) {
            Release.All -> tail.length
            Release.Settled -> if (endsSentence(tail) && balanced(tail)) tail.length else lastBoundary(tail)
            Release.Boundary -> lastBoundary(tail)
        }.let { boundary ->
            if (boundary > 0 || tail.length <= maxChars) boundary else forcedCut(tail)
        }
        if (cut <= 0) return false

        val released = tail.substring(0, cut)
        val atLineStart = pendingAtLineStart
        pending.delete(0, cut)
        // What follows a release is mid-line; leading space is not content.
        while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
        pendingAtLineStart = false
        out += sentences(speakLine(released, atLineStart))
        return true
    }

    /** The end of the last sentence in [text] that can be released safely, or 0. */
    private fun lastBoundary(text: String): Int {
        var index = text.length - 1
        while (index > 0) {
            if (text[index].isWhitespace()) {
                var end = index
                while (end > 0 && text[end - 1] in CLOSERS) end--
                if (end > 0 && text[end - 1] in TERMINAL && balanced(text.substring(0, index))) return index
            }
            index--
        }
        return 0
    }

    /** Past the cap with no boundary: the last balanced space, else the cap. */
    private fun forcedCut(text: String): Int {
        var index = minOf(maxChars, text.length - 1)
        while (index > maxChars / 2) {
            if (text[index].isWhitespace() && balanced(text.substring(0, index))) return index
            index--
        }
        return maxChars
    }

    /** One line (or part of one) as it should sound. Updates fence and think state. */
    private fun speakLine(raw: String, atLineStart: Boolean): String {
        val trimmed = raw.trim()
        if (atLineStart && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            inFence = !inFence
            return if (inFence) codeNote.orEmpty() else ""
        }
        if (inFence) return ""

        // Strip thinking, which may open and close anywhere, even mid-line.
        val visible = StringBuilder()
        var rest = raw
        while (rest.isNotEmpty()) {
            if (inThink) {
                val close = rest.indexOf(THINK_CLOSE)
                if (close < 0) {
                    rest = ""
                } else {
                    inThink = false
                    rest = rest.substring(close + THINK_CLOSE.length)
                }
            } else {
                val open = rest.indexOf(THINK_OPEN)
                if (open < 0) {
                    visible.append(rest)
                    rest = ""
                } else {
                    visible.append(rest, 0, open)
                    inThink = true
                    rest = rest.substring(open + THINK_OPEN.length)
                }
            }
        }
        return clean(visible.toString(), atLineStart)
    }

    private fun sentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return SENTENCE_SPLIT.split(text)
            .flatMap { sentence -> capped(sentence.trim()) }
            .filter { piece -> piece.any { it.isLetterOrDigit() } }
    }

    /** A sentence longer than the cap, broken at spaces (or at the cap). */
    private fun capped(sentence: String): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val space = rest.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            out += rest.substring(0, space).trim()
            rest = rest.substring(space).trim()
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 220
        const val DEFAULT_SETTLE_MILLIS = 700L
        const val DEFAULT_STALL_MILLIS = 2_000L

        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private const val TERMINAL = ".!?…。！？"
        private const val CLOSERS = "\"'”’)]」』"

        private val SENTENCE_SPLIT = Regex("(?<=[.!?…。！？][\"'”’)\\]」』]?)\\s+")

        private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
        private val QUOTE = Regex("^\\s{0,3}(>\\s?)+")
        private val LIST = Regex("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+")
        private val TASK = Regex("^\\[[ xX]]\\s+")
        private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
        private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
        private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        private val AUTOLINK = Regex("<(?:https?|mailto):[^>\\s]+>")
        private val URL = Regex("https?://\\S+")
        private val INLINE_CODE = Regex("`([^`]+)`")
        private val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
        private val STRIKE = Regex("~~(.+?)~~")
        private val ITALIC_STAR = Regex("(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])")
        private val ITALIC_UNDERSCORE = Regex("(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])")
        private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val TAG = Regex("</?[A-Za-z][^<>]{0,80}>")
        private val MATH = Regex("(?<!\\\\)\\$+")
        private val SPACES = Regex("\\s+")

        /** A line start that could still become syntax read differently. */
        internal fun mayBeLineSyntax(line: String): Boolean {
            val start = line.trimStart()
            if (start.isEmpty()) return true
            return listOf("```", "~~~", "MEDIA:").any { marker ->
                start.startsWith(marker) || marker.startsWith(start)
            } || start.startsWith("|")
        }

        internal fun endsSentence(text: String): Boolean {
            val trimmed = text.trimEnd().trimEnd { it in CLOSERS }
            return trimmed.isNotEmpty() && trimmed.last() in TERMINAL
        }

        /** Nothing in [text] is left open: code, links, emphasis, tags, thinking. */
        internal fun balanced(text: String): Boolean {
            if (text.count { it == '`' } % 2 != 0) return false
            var depth = 0
            for (ch in text) {
                if (ch == '[') depth++ else if (ch == ']' && depth > 0) depth--
            }
            if (depth > 0) return false
            val target = text.lastIndexOf("](")
            if (target >= 0 && text.indexOf(')', target) < 0) return false
            if (text.windowed(2).count { it == "**" } % 2 != 0) return false
            if (text.lastIndexOf("<think") > text.lastIndexOf(THINK_CLOSE)) return false
            val angle = text.lastIndexOf('<')
            if (angle >= 0 && text.indexOf('>', angle) < 0 && text.length - angle <= 12) return false
            return true
        }

        /** Markdown and markup reduced to what is said. */
        internal fun clean(text: String, atLineStart: Boolean): String {
            var line = text
            if (atLineStart) {
                val trimmed = line.trim()
                if (trimmed.startsWith("MEDIA:") || trimmed.startsWith("|") || RULE.matches(trimmed)) return ""
                line = line.replace(HEADING, "").replace(QUOTE, "").replace(LIST, "").replace(TASK, "")
            }
            line = line
                .replace(IMAGE) { it.groupValues[1] }
                .replace(LINK) { it.groupValues[1] }
                .replace(AUTOLINK, "")
                .replace(URL, "")
                .replace(INLINE_CODE) { it.groupValues[1] }
                .replace(BOLD) { it.groupValues[2] }
                .replace(STRIKE) { it.groupValues[1] }
                .replace(ITALIC_STAR) { it.groupValues[1] }
                .replace(ITALIC_UNDERSCORE) { it.groupValues[1] }
                .replace(BREAK, " ")
                .replace(TAG, "")
                .replace(MATH, "")
            return line.replace(SPACES, " ").trim()
        }
    }
}
