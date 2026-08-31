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
 */

sealed interface Block {
    data class Paragraph(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Bullet(val depth: Int, val spans: List<Span>) : Block
    data class Numbered(val marker: String, val depth: Int, val spans: List<Span>) : Block
    data class Quote(val spans: List<Span>) : Block

    /** [language] is whatever followed the fence, unvalidated — it is a label. */
    data class Code(val language: String?, val code: String) : Block

    /**
     * [align] has one entry per column and is the authority on width: a ragged
     * row is padded or truncated to it rather than shifting the grid.
     */
    data class Table(
        val header: List<List<Span>>,
        val rows: List<List<List<Span>>>,
        val align: List<Align>,
    ) : Block

    /** Display maths — `$$…$$` or `\[…\]` — laid out rather than flattened. */
    data class Math(val latex: String) : Block

    data object Rule : Block
}

enum class Align { Start, Center, End }

/** One run of text with the marks that apply to it. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    /** Set in the maths face: upright digits, italic letters. */
    val math: Boolean = false,
    /**
     * The expression this span came from, kept verbatim when the caller asked
     * for it. The native renderer flattens maths and cannot put it back; the
     * HTML renderer hands it to KaTeX and needs it exactly as written.
     */
    val mathLatex: String? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
)

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val RULE = Regex("""^\s*([-*_])\s*(\1\s*){2,}$""")
private val FENCE = Regex("""^\s*```+\s*(\S+)?\s*$""")

/** `|---|:--:|---:|` — the row that turns the line above it into a header. */
private val ALIGN_ROW = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

private val DISPLAY_MATH_OPEN = Regex("""^\s*(\$\$|\\\[)\s*(.*)$""")

/**
 * Splits [text] into blocks. Total: every line lands somewhere.
 *
 * [preserveMath] keeps each inline expression whole and unflattened, for the
 * renderer that passes it to KaTeX rather than drawing it itself.
 */
fun parseMarkdown(text: String, preserveMath: Boolean = false): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n"), preserveMath))
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

        val mathOpen = DISPLAY_MATH_OPEN.matchEntire(line)
        if (mathOpen != null) {
            flushParagraph()
            val closer = if (mathOpen.groupValues[1] == "$$") "$$" else """\]"""
            val first = mathOpen.groupValues[2]
            // `$$ x = 1 $$` on one line, or opened here and closed further down.
            val inlineClose = first.indexOf(closer)
            if (inlineClose >= 0) {
                blocks += Block.Math(first.substring(0, inlineClose).trim())
                i++
                continue
            }
            val body = mutableListOf<String>()
            if (first.isNotBlank()) body += first
            i++
            while (i < lines.size && !lines[i].contains(closer)) {
                body += lines[i]
                i++
            }
            if (i < lines.size) {
                val tail = lines[i].substringBefore(closer)
                if (tail.isNotBlank()) body += tail
                i++
            }
            blocks += Block.Math(body.joinToString(" ").trim())
            continue
        }

        // A table declares itself on its second line, so the header is only a
        // header once the alignment row has arrived — mid-stream it stays a
        // paragraph until then, and settles when the next delta lands.
        if (line.contains('|') && i + 1 < lines.size && ALIGN_ROW.matchEntire(lines[i + 1]) != null) {
            val header = splitRow(line)
            val alignments = splitRow(lines[i + 1]).map { spec ->
                when {
                    spec.startsWith(':') && spec.endsWith(':') -> Align.Center
                    spec.endsWith(':') -> Align.End
                    else -> Align.Start
                }
            }
            if (alignments.size == header.size) {
                flushParagraph()
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                blocks += Block.Table(
                    header = header.map { parseInline(it, preserveMath) },
                    rows = rows.map { row ->
                        // Squared off against the header: a ragged row would
                        // otherwise slide every cell after it into the wrong column.
                        List(header.size) { column -> parseInline(row.getOrElse(column) { "" }, preserveMath) }
                    },
                    align = alignments,
                )
                continue
            }
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
                blocks += Block.Heading(m.groupValues[1].length, parseInline(m.groupValues[2], preserveMath))
            }

            BULLET.matchEntire(line) != null -> {
                flushParagraph()
                val m = BULLET.matchEntire(line)!!
                blocks += Block.Bullet(m.groupValues[1].length / 2, parseInline(m.groupValues[2], preserveMath))
            }

            NUMBERED.matchEntire(line) != null -> {
                flushParagraph()
                val m = NUMBERED.matchEntire(line)!!
                blocks += Block.Numbered(
                    m.groupValues[2],
                    m.groupValues[1].length / 2,
                    parseInline(m.groupValues[3], preserveMath),
                )
            }

            QUOTE.matchEntire(line) != null -> {
                flushParagraph()
                blocks += Block.Quote(parseInline(QUOTE.matchEntire(line)!!.groupValues[1], preserveMath))
            }

            else -> paragraph += line
        }
        i++
    }
    flushParagraph()
    return blocks
}

/** Cells, with the outer pipes dropped and each one trimmed. */
private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private val LINK = Regex("""\[([^\]\n]*)\]\(([^)\s]+)\)""")

/**
 * Marks inside one block.
 *
 * Code spans are found first and their contents are never re-examined: a
 * `**` inside backticks is two asterisks the model meant literally.
 */
fun parseInline(text: String, preserveMath: Boolean = false): List<Span> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<Span>()
    var index = 0

    while (index < text.length) {
        val tick = text.indexOf('`', index)
        if (tick < 0) {
            spans += parseMathAndEmphasis(text.substring(index), preserveMath)
            break
        }
        val close = text.indexOf('`', tick + 1)
        if (close < 0) {
            // Unmatched: literal, as written.
            spans += parseMathAndEmphasis(text.substring(index), preserveMath)
            break
        }
        if (tick > index) spans += parseMathAndEmphasis(text.substring(index, tick), preserveMath)
        spans += Span(text.substring(tick + 1, close), code = true)
        index = close + 1
    }
    return spans.filter { it.text.isNotEmpty() }
}

/**
 * Pulls inline maths out before emphasis runs, so `$a * b$` is a product and
 * not italics.
 */
private fun parseMathAndEmphasis(text: String, preserveMath: Boolean): List<Span> {
    val out = mutableListOf<Span>()
    var index = 0

    while (index < text.length) {
        val math = findInlineMath(text, index)
        if (math == null) {
            out += parseEmphasis(text.substring(index))
            break
        }
        val (start, end, latex) = math
        if (start > index) out += parseEmphasis(text.substring(index, start))
        out += if (preserveMath) {
            listOf(Span(latex, math = true, mathLatex = latex))
        } else {
            inlineMathSpans(latex)
        }
        index = end
    }
    return out
}

private data class MathSpan(val start: Int, val end: Int, val latex: String)

/**
 * The next `$…$` or `\(…\)` at or after [from].
 *
 * `$` is also a currency sign, and "$100 to $200" must not become maths. Two
 * rules settle it: a digit may not follow the opening `$`, and the closing one
 * may not follow a space. Prices fail both; `$x^2$` fails neither.
 */
private fun findInlineMath(text: String, from: Int): MathSpan? {
    var index = from
    while (index < text.length) {
        when {
            text.startsWith("""\(""", index) -> {
                val close = text.indexOf("""\)""", index + 2)
                if (close < 0) return null
                return MathSpan(index, close + 2, text.substring(index + 2, close))
            }
            text[index] == '$' -> {
                val next = text.getOrNull(index + 1)
                if (next == null || next.isDigit() || next.isWhitespace()) {
                    index++
                    continue
                }
                val close = text.indexOf('$', index + 1)
                if (close < 0) return null
                val body = text.substring(index + 1, close)
                // Empty is `$$` mid-paragraph, which is a delimiter and not an
                // expression; the closing `$` becomes the next candidate.
                if (body.isEmpty() || body.last().isWhitespace() || body.contains('\n')) {
                    index = close
                    continue
                }
                return MathSpan(index, close + 1, body)
            }
            else -> index++
        }
    }
    return null
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
