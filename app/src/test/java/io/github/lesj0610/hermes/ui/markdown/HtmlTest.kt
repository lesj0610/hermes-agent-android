package io.github.lesj0610.hermes.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document handed to KaTeX.
 *
 * The rendering itself is KaTeX's problem and is not re-tested here. What is
 * tested is everything around it: that maths reaches the page as written, that
 * a reply cannot inject script into its own page, and that the two renderers
 * agree on which replies contain maths at all.
 */
class HtmlTest {

    private val theme = HtmlTheme(
        text = "#E4EBF0",
        muted = "#8A98A5",
        link = "#7FB2E5",
        raised = "#212C35",
        line = "#2C3A45",
        fontSizePx = 14,
    )

    private fun html(markdown: String) = renderHtml(markdown, theme)

    @Test
    fun `maths reaches the page exactly as written`() {
        // KaTeX needs the source, not an approximation of it — a mangled
        // backslash is a different expression.
        val out = html("$$\n\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}\n$$")
        assertTrue(out, out.contains("""\int_0^\infty e^{-x^2}\,dx = \frac{\sqrt{\pi}}{2}"""))
        assertTrue(out.contains("""data-display="1""""))
    }

    @Test
    fun `inline maths is marked inline, display maths is not`() {
        val out = html("mass is ${'$'}E = mc^2${'$'} here")
        assertTrue(out.contains("""data-display="0""""))
        assertTrue(out.contains("""data-tex="E = mc^2""""))
    }

    @Test
    fun `a reply cannot break out of its own page`() {
        // The reply is untrusted text. It arrives from a model that read tool
        // output, which read the internet.
        val out = html("""<script>alert(1)</script> and <img src=x onerror=alert(2)>""")
        assertFalse(out, out.contains("<script>alert"))
        assertFalse(out, out.contains("<img src=x"))
        assertTrue(out.contains("&lt;script&gt;"))
    }

    @Test
    fun `an expression cannot escape its attribute`() {
        val out = html("""${'$'}a" onload="alert(1)${'$'}""")
        assertFalse(out, out.contains("""onload="alert"""))
        assertTrue(out.contains("&quot;"))
    }

    @Test
    fun `everything the native renderer draws is in the document`() {
        val out = html(
            """
            # 제목

            - 항목 **굵게**
            - 항목 `코드`

            > 인용

            | a | b |
            |---|--:|
            | 1 | 2 |

            ```bash
            echo hi
            ```

            ---
            [링크](https://example.test)
            """.trimIndent(),
        )
        for (fragment in listOf(
            "<h1>", "<ul", "<strong>", "<code>", "<blockquote>", "<table>",
            "text-align:right", "<pre>", "echo hi", "<hr>", "https://example.test",
        )) {
            assertTrue("missing: $fragment", out.contains(fragment))
        }
    }

    @Test
    fun `the page never reaches the network`() {
        val out = html("${'$'}x${'$'}")
        assertTrue(out.contains("""href="katex/katex.min.css""""))
        assertTrue(out.contains("""src="katex/katex.min.js""""))
        assertFalse(out, out.contains("//cdn"))
        assertFalse(out, out.contains("https://cdn"))
    }

    @Test
    fun `only replies with maths take the typeset path`() {
        assertTrue(containsMath("${'$'}x^2${'$'}"))
        assertTrue(containsMath("$$\nx\n$$"))
        assertTrue(containsMath("""\(x\)"""))
        assertTrue(containsMath("| a |\n|---|\n| ${'$'}x${'$'} |"))
        // Money is not maths, and neither is prose about dollars.
        assertFalse(containsMath("it costs ${'$'}100 to ${'$'}200"))
        assertFalse(containsMath("plain **bold** and `code`"))
        assertFalse(containsMath("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun `numbered lists keep their own numbering`() {
        // Each item is its own list, so without a start attribute every item
        // would render as 1.
        val out = html("3. third\n4. fourth")
        assertTrue(out, out.contains("""start="3""""))
        assertTrue(out.contains("""start="4""""))
    }

    @Test
    fun `an empty reply still produces a valid page`() {
        val out = html("")
        assertTrue(out.contains("<div id=\"content\"></div>"))
    }

    @Test
    fun `theme colours reach the stylesheet`() {
        val out = html("${'$'}x${'$'}")
        assertTrue(out.contains("color: #E4EBF0"))
        assertTrue(out.contains("font-size: 14px"))
        assertEquals(1, Regex("background: transparent").findAll(out).count())
    }
}
