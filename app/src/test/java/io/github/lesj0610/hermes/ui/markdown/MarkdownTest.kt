package io.github.lesj0610.hermes.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's two obligations: never lose text, and stay sane mid-stream.
 *
 * Everything here is a shape the agent actually produces — the weather reply
 * that prompted this was bullets and bold.
 */
class MarkdownTest {

    private fun textOf(blocks: List<Block>): String = blocks.joinToString("") { block ->
        when (block) {
            is Block.Paragraph -> block.spans.joinToString("") { it.text }
            is Block.Heading -> block.spans.joinToString("") { it.text }
            is Block.Bullet -> block.spans.joinToString("") { it.text }
            is Block.Numbered -> block.spans.joinToString("") { it.text }
            is Block.Quote -> block.spans.joinToString("") { it.text }
            is Block.Code -> block.code
            is Block.Table -> (listOf(block.header) + block.rows)
                .joinToString("") { row -> row.joinToString("") { cell -> cell.joinToString("") { it.text } } }
            is Block.Math -> block.latex
            Block.Rule -> ""
        }
    }

    @Test
    fun `bullets and bold, the shape a reply actually arrives in`() {
        val blocks = parseMarkdown(
            """
            서울 현재 날씨
            - 기온: **23.8°C**
            - 습도: 100%
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is Block.Bullet)
        val bold = (blocks[1] as Block.Bullet).spans.first { it.bold }
        assertEquals("23.8°C", bold.text)
    }

    @Test
    fun `a fence that has not closed yet is a code block in progress`() {
        // Parsed on every delta while streaming. Treating this as a paragraph
        // would show backticks that reflow into a block a moment later.
        val blocks = parseMarkdown("설명\n```bash\ncurl -s localhost")
        assertEquals(2, blocks.size)
        val code = blocks[1] as Block.Code
        assertEquals("bash", code.language)
        assertEquals("curl -s localhost", code.code)
    }

    @Test
    fun `emphasis inside a code span is literal`() {
        val spans = parseInline("run `a ** b` now")
        assertEquals("a ** b", spans.first { it.code }.text)
        assertTrue(spans.none { it.bold })
    }

    @Test
    fun `an unmatched marker stays as written`() {
        // Losing part of a reply to a parser edge case is worse than an
        // asterisk on screen.
        assertEquals("2 * 3 = 6", parseInline("2 * 3 = 6").joinToString("") { it.text })
        assertEquals("배열[0]", parseInline("배열[0]").joinToString("") { it.text })
    }

    @Test
    fun `links keep their target and drop the syntax`() {
        val span = parseInline("see [the docs](https://example.test/a)").first { it.link != null }
        assertEquals("the docs", span.text)
        assertEquals("https://example.test/a", span.link)
    }

    @Test
    fun `no text is lost, whatever the input`() {
        val messy = "# 제목\n- a **b** c\n\n> quote\n\n```\nx\n```\n1. one\n---\nplain *text"
        val out = textOf(parseMarkdown(messy))
        for (fragment in listOf("제목", "a ", "b", " c", "quote", "x", "one", "plain")) {
            assertTrue("lost: $fragment", out.contains(fragment))
        }
    }

    @Test
    fun `numbered items keep their own marker`() {
        val blocks = parseMarkdown("1. first\n2. second")
        assertEquals("1.", (blocks[0] as Block.Numbered).marker)
        assertEquals("2.", (blocks[1] as Block.Numbered).marker)
    }

    @Test
    fun `a table needs its alignment row to become one`() {
        val blocks = parseMarkdown(
            """
            | 항목 | 값 |
            |------|---:|
            | 기온 | 23.8 |
            | 습도 | 100 |
            """.trimIndent(),
        )
        val table = blocks.single() as Block.Table
        assertEquals(listOf("항목", "값"), table.header.map { row -> row.joinToString("") { it.text } })
        assertEquals(2, table.rows.size)
        assertEquals(Align.End, table.align[1])
    }

    @Test
    fun `a header without its alignment row stays text while streaming`() {
        // The second line decides. Until it arrives the pipes are prose, and
        // the next delta settles it.
        val blocks = parseMarkdown("| 항목 | 값 |")
        assertTrue(blocks.single() is Block.Paragraph)
    }

    @Test
    fun `a ragged row is squared off against the header`() {
        // A short row would otherwise slide every later cell one column left.
        val rows = (parseMarkdown("|a|b|c|\n|-|-|-|\n|1|2|").single() as Block.Table).rows
        assertEquals(3, rows[0].size)
        assertEquals("", rows[0][2].joinToString("") { it.text })
    }

    @Test
    fun `an empty reply parses to nothing rather than failing`() {
        assertEquals(emptyList<Block>(), parseMarkdown(""))
    }
}
