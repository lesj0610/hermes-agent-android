package io.github.lesj0610.hermes.ui.markdown

/**
 * The slice of Markdown an agent actually writes, parsed by hand.
 *
 * No dependency: this app draws its own icons for the same reason. A general
 * CommonMark implementation brings a parser, an HTML layer and a renderer to
 * deliver bold text and fenced code, which is most of what arrives here.
 *
 * Two properties matter more than completeness:
 *
 *  - **Never throws, never eats text.** An unmatched marker stays as the
 *    literal characters the model wrote. Losing part of a reply to a parser
 *    edge case is worse than showing an asterisk.
 *  - **Safe mid-stream.** Replies are parsed on every delta, so a fence that
 *    has opened but not closed is a code block in progress rather than a
 *    paragraph full of backticks that will reflow a moment later.
 *
 * Tables are deliberately absent — they need column measurement, and getting
 * them half right is worse than leaving them as text.
 */

sealed interface Block {
    data class Paragraph(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Bullet(val depth: Int, val spans: List<Span>) : Block
    data class Numbered(val marker: String, val depth: Int, val spans: List<Span>) : Block
    data class Quote(val spans: List<Span>) : Block

    /** [language] is whatever followed the fence, unvalidated — it is a label. */
    data class Code(val language: String?, val code: String) : Block
    data object Rule : Block
}

/** One run of text with the marks that apply to it. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val RULE = Regex("""^\s*([-*_])\s*(\1\s*){2,}$""")
private val FENCE = Regex("""^\s*```+\s*(\S+)?\s*$""")

/** Splits [text] into blocks. Total: every line lands somewhere. */
fun parseMarkdown(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n")))
        paragraph.clear()
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.matchEntire(line)

        if (fence != null) {
            flushParagraph()
            val language = fence.groupValues[1].takeIf { it.isNotBlank() }
            val body = mutableListOf<String>()
            i++
            // An unterminated fence is the common case while streaming: the
            // rest of what has arrived is the block so far.
            while (i < lines.size && FENCE.matchEntire(lines[i]) == null) {
                body += lines[i]
                i++
            }
            if (i < lines.size) i++ // consume the closing fence
            blocks += Block.Code(language, body.joinToString("\n"))
            continue
        }

        when {
            line.isBlank() -> flushParagraph()

            RULE.matchEntire(line) != null -> {
                flushParagraph()
                blocks += Block.Rule
            }

            HEADING.matchEntire(line) != null -> {
                flushParagraph()
                val m = HEADING.matchEntire(line)!!
                blocks += Block.Heading(m.groupValues[1].length, parseInline(m.groupValues[2]))
            }

            BULLET.matchEntire(line) != null -> {
                flushParagraph()
                val m = BULLET.matchEntire(line)!!
                blocks += Block.Bullet(m.groupValues[1].length / 2, parseInline(m.groupValues[2]))
            }

            NUMBERED.matchEntire(line) != null -> {
                flushParagraph()
                val m = NUMBERED.matchEntire(line)!!
                blocks += Block.Numbered(
                    m.groupValues[2],
                    m.groupValues[1].length / 2,
                    parseInline(m.groupValues[3]),
                )
            }

            QUOTE.matchEntire(line) != null -> {
                flushParagraph()
                blocks += Block.Quote(parseInline(QUOTE.matchEntire(line)!!.groupValues[1]))
            }

            else -> paragraph += line
        }
        i++
    }
    flushParagraph()
    return blocks
}

private val LINK = Regex("""\[([^\]\n]*)\]\(([^)\s]+)\)""")

/**
 * Marks inside one block.
 *
 * Code spans are found first and their contents are never re-examined: a
 * `**` inside backticks is two asterisks the model meant literally.
 */
fun parseInline(text: String): List<Span> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<Span>()
    var index = 0

    while (index < text.length) {
        val tick = text.indexOf('`', index)
        if (tick < 0) {
            spans += parseEmphasis(text.substring(index))
            break
        }
        val close = text.indexOf('`', tick + 1)
        if (close < 0) {
            // Unmatched: literal, as written.
            spans += parseEmphasis(text.substring(index))
            break
        }
        if (tick > index) spans += parseEmphasis(text.substring(index, tick))
        spans += Span(text.substring(tick + 1, close), code = true)
        index = close + 1
    }
    return spans.filter { it.text.isNotEmpty() }
}

private fun parseEmphasis(text: String): List<Span> {
    val out = mutableListOf<Span>()
    var rest = text

    while (rest.isNotEmpty()) {
        val link = LINK.find(rest)
        val marker = listOf("**", "__", "~~", "*", "_")
            .mapNotNull { m -> rest.indexOf(m).takeIf { it >= 0 }?.let { m to it } }
            .minByOrNull { it.second }

        // Whichever comes first, if either does.
        if (link != null && (marker == null || link.range.first < marker.second)) {
            if (link.range.first > 0) out += Span(rest.substring(0, link.range.first))
            out += Span(link.groupValues[1], link = link.groupValues[2])
            rest = rest.substring(link.range.last + 1)
            continue
        }
        if (marker == null) {
            out += Span(rest)
            break
        }

        val (mark, at) = marker
        val close = rest.indexOf(mark, at + mark.length)
        if (close < 0) {
            // Unmatched marker: the rest is plain text including the marker.
            out += Span(rest)
            break
        }
        if (at > 0) out += Span(rest.substring(0, at))
        val inner = rest.substring(at + mark.length, close)
        out += Span(
            inner,
            bold = mark == "**" || mark == "__",
            italic = mark == "*" || mark == "_",
            strike = mark == "~~",
        )
        rest = rest.substring(close + mark.length)
    }
    return out
}
