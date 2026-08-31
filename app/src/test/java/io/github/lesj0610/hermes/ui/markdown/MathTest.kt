package io.github.lesj0610.hermes.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths parser, held to the same two obligations as the Markdown one:
 * never lose text, stay sane mid-stream.
 */
class MathTest {

    @Test
    fun `a fraction keeps both halves`() {
        val node = parseMath("""\frac{a+b}{2}""")
        val fraction = node as MathNode.Frac
        assertEquals("a+b", flat(fraction.numerator))
        assertEquals("2", flat(fraction.denominator))
    }

    @Test
    fun `a script binds to the character before it, not the whole word`() {
        // "abc^2" is c squared. Binding the exponent to "abc" would be a
        // different expression.
        val node = parseMath("abc^2") as MathNode.Seq
        assertEquals("ab", flat(node.items[0]))
        val power = node.items[1] as MathNode.Sup
        assertEquals("c", flat(power.base))
        assertEquals("2", flat(power.exponent))
    }

    @Test
    fun `an unknown command reads as its own name rather than vanishing`() {
        assertEquals("providence", flat(parseMath("""\providence""")))
    }

    @Test
    fun `a font command keeps its argument and drops itself`() {
        // \mathbb{R} is the reals; the face is unavailable, the R is not.
        assertEquals("R", flat(parseMath("""\mathbb{R}""")))
    }

    @Test
    fun `symbols become the character they stand for`() {
        assertEquals("α≤∞", flat(parseMath("""\alpha \leq \infty""")).replace(" ", ""))
    }

    @Test
    fun `inline fractions flatten to prose, with brackets only where needed`() {
        assertEquals("(a+b)/2", inlineMathSpans("""\frac{a+b}{2}""").joinToString("") { it.text })
        assertEquals("a/2", inlineMathSpans("""\frac{a}{2}""").joinToString("") { it.text })
    }

    @Test
    fun `inline scripts are marked for shifting, not rewritten`() {
        val spans = inlineMathSpans("x^2")
        assertEquals("x", spans[0].text)
        assertTrue(spans[1].superscript)
        assertEquals("2", spans[1].text)
    }

    @Test
    fun `currency is not maths`() {
        // "$100 to $200" must survive as written; a digit after the opening
        // dollar rules it out.
        val text = parseInline("${'$'}100 to ${'$'}200").joinToString("") { it.text }
        assertEquals("${'$'}100 to ${'$'}200", text)
    }

    @Test
    fun `inline maths is found and its delimiters dropped`() {
        val spans = parseInline("energy is ${'$'}E = mc^2${'$'} exactly")
        val rendered = spans.joinToString("") { it.text }
        assertTrue(rendered, rendered.contains("E = mc2"))
        assertTrue(spans.any { it.superscript })
        assertTrue(spans.none { it.text.contains("${'$'}") })
    }

    @Test
    fun `a display block is its own block`() {
        val blocks = parseMarkdown("설명\n\n${'$'}${'$'}\nx = \\frac{1}{2}\n${'$'}${'$'}\n\n끝")
        assertEquals(3, blocks.size)
        assertEquals("""x = \frac{1}{2}""", (blocks[1] as Block.Math).latex)
    }

    @Test
    fun `an unclosed display block still renders what has arrived`() {
        // Mid-stream: the fence has opened and the closer has not landed yet.
        val blocks = parseMarkdown("${'$'}${'$'}\nE = mc^2")
        assertEquals("E = mc^2", (blocks[0] as Block.Math).latex)
    }

    private fun flat(node: MathNode): String = when (node) {
        is MathNode.Sym -> node.text
        is MathNode.Seq -> node.items.joinToString("") { flat(it) }
        is MathNode.Upright -> flat(node.body)
        is MathNode.Sup -> flat(node.base) + flat(node.exponent)
        is MathNode.Sub -> flat(node.base) + flat(node.subscript)
        is MathNode.Frac -> flat(node.numerator) + "/" + flat(node.denominator)
        is MathNode.Sqrt -> "√" + flat(node.body)
    }
}
