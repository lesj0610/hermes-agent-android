package io.github.lesj0610.hermes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Display maths, laid out.
 *
 * A fraction is two rows and a rule; an exponent is smaller and raised. Neither
 * survives being flattened into one line of text, which is why inline maths and
 * this take different paths from the same tree.
 */
@Composable
fun MathBlock(latex: String, style: TextStyle, modifier: Modifier = Modifier) {
    val node = remember(latex) { parseMath(latex) }
    // Resolved once here: the fraction rule and the vinculum are drawn, not
    // typed, so they need a real colour rather than an inherited Unspecified.
    val resolved = if (style.color == Color.Unspecified) {
        style.copy(color = LocalContentColor.current)
    } else {
        style
    }
    Box(
        modifier
            .fillMaxWidth()
            // Long expressions scroll rather than wrap: a broken equation is
            // harder to read than one that runs off the edge.
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        MathNodeView(node, resolved, resolved.fontSize * 1.05f)
    }
}

@Composable
internal fun MathNodeView(node: MathNode, style: TextStyle, size: TextUnit) {
    when (node) {
        is MathNode.Sym -> Text(
            text = node.text,
            style = style.copy(
                fontSize = size,
                fontFamily = FontFamily.Serif,
                // The maths convention: variables lean, digits and operators
                // stay upright.
                fontStyle = if (node.text.any { it.isLetter() } && node.text.length <= 2) {
                    FontStyle.Italic
                } else {
                    FontStyle.Normal
                },
            ),
        )

        is MathNode.Upright -> Text(
            text = flatUpright(node.body),
            style = style.copy(fontSize = size),
        )

        is MathNode.Seq -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.items.forEach { MathNodeView(it, style, size) }
        }

        is MathNode.Sup -> Row(verticalAlignment = Alignment.Top) {
            // The base drops by roughly the height the exponent gains, which
            // keeps the pair reading as one term.
            Box(Modifier.padding(top = (size.value * 0.26f).dp)) {
                MathNodeView(node.base, style, size)
            }
            MathNodeView(node.exponent, style, size.script())
        }

        is MathNode.Sub -> Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.padding(bottom = (size.value * 0.20f).dp)) {
                MathNodeView(node.base, style, size)
            }
            MathNodeView(node.subscript, style, size.script())
        }

        is MathNode.Frac -> Fraction(node, style, size)

        is MathNode.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "√", style = style.copy(fontSize = size, fontFamily = FontFamily.Serif))
            // The vinculum, drawn rather than typed: it has to span whatever
            // the radicand turns out to measure.
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .drawBehind {
                        drawLine(
                            color = style.color,
                            start = Offset(0f, 0.5f),
                            end = Offset(this.size.width, 0.5f),
                            strokeWidth = 1f,
                        )
                    }
                    .padding(top = 2.dp, start = 1.dp, end = 2.dp),
            ) {
                MathNodeView(node.body, style, size)
            }
        }
    }
}

/**
 * Numerator over denominator, with the rule spanning the wider of the two.
 *
 * Laid out by hand because the obvious `Column` does not work: a fraction sits
 * inside a horizontally scrolling row, so the incoming width constraint is
 * infinite and a `fillMaxWidth` rule measures to nothing. Measuring the two
 * halves first gives the rule a width to be.
 */
@Composable
private fun Fraction(node: MathNode.Frac, style: TextStyle, size: TextUnit) {
    val gap = with(LocalDensity.current) { 2.dp.roundToPx() }
    val thickness = with(LocalDensity.current) { 1.dp.roundToPx() }.coerceAtLeast(1)

    Layout(
        modifier = Modifier.padding(horizontal = 3.dp),
        content = {
            MathNodeView(node.numerator, style, size * 0.94f)
            MathNodeView(node.denominator, style, size * 0.94f)
            Box(Modifier.background(style.color))
        },
    ) { measurables, constraints ->
        // Unbounded: an expression is one line and scrolls if it must.
        val free = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
        val numerator = measurables[0].measure(free)
        val denominator = measurables[1].measure(free)
        val width = maxOf(numerator.width, denominator.width)
        val rule = measurables[2].measure(Constraints.fixed(width, thickness))

        layout(width, numerator.height + gap * 2 + thickness + denominator.height) {
            numerator.place((width - numerator.width) / 2, 0)
            rule.place(0, numerator.height + gap)
            denominator.place((width - denominator.width) / 2, numerator.height + gap * 2 + thickness)
        }
    }
}

/** `\text{…}` holds prose, so its tree is only ever characters. */
private fun flatUpright(node: MathNode): String = when (node) {
    is MathNode.Sym -> node.text
    is MathNode.Seq -> node.items.joinToString("") { flatUpright(it) }
    is MathNode.Upright -> flatUpright(node.body)
    is MathNode.Sup -> flatUpright(node.base) + "^" + flatUpright(node.exponent)
    is MathNode.Sub -> flatUpright(node.base) + "_" + flatUpright(node.subscript)
    is MathNode.Frac -> flatUpright(node.numerator) + "/" + flatUpright(node.denominator)
    is MathNode.Sqrt -> "√" + flatUpright(node.body)
}

/**
 * A script's size. Scripts shrink, but only so far — a script of a script has
 * to stay legible. (TextUnit defines compareTo without implementing Comparable,
 * so coerceAtLeast is not available here.)
 */
private fun TextUnit.script(): TextUnit {
    val shrunk = this * 0.72f
    return if (shrunk < MATH_MIN_SIZE) MATH_MIN_SIZE else shrunk
}

private val MATH_MIN_SIZE = 9.sp
