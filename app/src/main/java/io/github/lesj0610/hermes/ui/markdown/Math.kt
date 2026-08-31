package io.github.lesj0610.hermes.ui.markdown

/**
 * The maths an agent writes, parsed by hand.
 *
 * The complete answer is KaTeX behind a WebView, which is what the web clients
 * do. It is the wrong trade here: a WebView per message is heavy, it fights the
 * theme, and it reflows badly while a reply is still streaming.
 *
 * So this covers what actually arrives instead of what LaTeX permits —
 * fractions, roots, sub- and superscripts, and the symbol vocabulary. Matrices,
 * integral limits and auto-sized delimiters are absent; their source shows
 * through as text rather than disappearing.
 */

sealed interface MathNode {
    /** A run of literal characters, symbols already substituted. */
    data class Sym(val text: String) : MathNode
    data class Seq(val items: List<MathNode>) : MathNode
    data class Sup(val base: MathNode, val exponent: MathNode) : MathNode
    data class Sub(val base: MathNode, val subscript: MathNode) : MathNode
    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode
    data class Sqrt(val body: MathNode) : MathNode

    /** `\text{…}` — set upright, not in the italic maths face. */
    data class Upright(val body: MathNode) : MathNode
}

/**
 * Commands that are just a character.
 *
 * Anything missing falls through to its own name, so an unknown command reads
 * as `mathbb` rather than vanishing.
 */
private val SYMBOLS: Map<String, String> = mapOf(
    // Greek, lower
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Greek, upper
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    // Operators
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
    "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "∙",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
    "partial" to "∂", "nabla" to "∇", "infty" to "∞",
    // Relations
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠",
    "ne" to "≠", "approx" to "≈", "sim" to "∼", "simeq" to "≃",
    "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    // Sets and logic
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂",
    "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
    "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬",
    "land" to "∧", "lor" to "∨", "therefore" to "∴", "because" to "∵",
    // Arrows
    "to" to "→", "rightarrow" to "→", "Rightarrow" to "⇒",
    "leftarrow" to "←", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
    "Leftrightarrow" to "⇔", "mapsto" to "↦", "implies" to "⟹",
    // Named functions, kept as words
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log",
    "ln" to "ln", "exp" to "exp", "min" to "min", "max" to "max",
    "lim" to "lim", "det" to "det", "dim" to "dim", "deg" to "deg",
    // Spacing commands collapse to a thin space
    "quad" to "  ", "qquad" to "    ",
    // Misc
    "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "dots" to "…",
    "prime" to "′", "degree" to "°", "angle" to "∠", "perp" to "⊥",
    "parallel" to "∥", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
)

/** Structural characters — a run of literal text stops at any of these. */
private const val BREAKS = "^_{}\\"

/** Parses [latex] into a tree. Total: unknown input degrades to its own text. */
fun parseMath(latex: String): MathNode = MathParser(latex).parseSequence()

private class MathParser(private val source: String) {
    private var index = 0

    fun parseSequence(untilBrace: Boolean = false): MathNode {
        val items = mutableListOf<MathNode>()
        while (index < source.length) {
            if (untilBrace && source[index] == '}') break
            var node = parseAtom() ?: break
            // Scripts bind to whatever precedes them, and chain: x^2_i.
            while (index < source.length && (source[index] == '^' || source[index] == '_')) {
                val superscript = source[index] == '^'
                index++
                val argument = parseAtom() ?: MathNode.Sym("")
                node = if (superscript) MathNode.Sup(node, argument) else MathNode.Sub(node, argument)
            }
            items += node
        }
        return items.singleOrNull() ?: MathNode.Seq(items)
    }

    private fun parseAtom(): MathNode? {
        if (index >= source.length) return null
        return when (val c = source[index]) {
            '}' -> null
            '{' -> {
                index++
                val body = parseSequence(untilBrace = true)
                if (index < source.length && source[index] == '}') index++
                body
            }
            '\\' -> parseCommand()
            ' ' -> {
                // LaTeX spacing is layout, not content; one space is enough to
                // keep "a + b" from closing up.
                while (index < source.length && source[index] == ' ') index++
                MathNode.Sym(" ")
            }
            else -> {
                val start = index
                while (index < source.length && source[index] !in BREAKS && source[index] != ' ') index++
                var text = source.substring(start, index)
                // A script attaches to the last character only: in "abc^2" the
                // exponent belongs to c. Hand the rest back for the next pass.
                if (index < source.length && (source[index] == '^' || source[index] == '_') && text.length > 1) {
                    index -= 1
                    text = text.dropLast(1)
                }
                MathNode.Sym(text)
            }
        }
    }

    private fun parseCommand(): MathNode {
        index++ // the backslash
        val start = index
        while (index < source.length && source[index].isLetter()) index++
        val name = source.substring(start, index)

        if (name.isEmpty()) {
            // An escaped character: \{ \} \% \$ — or a spacing command like \,
            if (index >= source.length) return MathNode.Sym("\\")
            val escaped = source[index]
            index++
            return MathNode.Sym(if (escaped == ',' || escaped == ';' || escaped == '!') "" else escaped.toString())
        }

        return when (name) {
            "frac", "dfrac", "tfrac" -> MathNode.Frac(parseGroup(), parseGroup())
            "sqrt" -> MathNode.Sqrt(parseGroup())
            "text", "textrm", "mathrm", "operatorname" -> MathNode.Upright(parseGroup())
            "mathbf", "mathit", "mathsf", "mathcal", "mathbb", "boldsymbol" -> parseGroup()
            // Delimiters size themselves here; the command itself is dropped and
            // the bracket after it kept.
            "left", "right", "big", "Big", "bigg", "Bigg" -> {
                skipSpaces()
                if (index >= source.length) return MathNode.Sym("")
                val delimiter = source[index]
                index++
                MathNode.Sym(if (delimiter == '.') "" else delimiter.toString())
            }
            else -> MathNode.Sym(SYMBOLS[name] ?: name)
        }
    }

    private fun parseGroup(): MathNode {
        skipSpaces()
        return parseAtom() ?: MathNode.Sym("")
    }

    private fun skipSpaces() {
        while (index < source.length && source[index] == ' ') index++
    }
}

/**
 * Inline maths, flattened into spans.
 *
 * A fraction cannot be drawn on one text line, so inline it becomes `a/b` —
 * which is how it would have been written in prose anyway. Display maths keeps
 * the tree and gets laid out properly.
 */
fun inlineMathSpans(latex: String): List<Span> {
    val out = mutableListOf<Span>()
    flattenMath(parseMath(latex), out, superscript = false, subscript = false)
    return out.filter { it.text.isNotEmpty() }
}

private fun flattenMath(node: MathNode, out: MutableList<Span>, superscript: Boolean, subscript: Boolean) {
    when (node) {
        is MathNode.Sym -> out += Span(
            node.text,
            math = true,
            superscript = superscript,
            subscript = subscript,
        )
        is MathNode.Seq -> node.items.forEach { flattenMath(it, out, superscript, subscript) }
        is MathNode.Upright -> out += Span(
            flatText(node.body),
            superscript = superscript,
            subscript = subscript,
        )
        is MathNode.Sup -> {
            flattenMath(node.base, out, superscript, subscript)
            // Already raised or lowered: a script of a script stays where it is
            // rather than climbing off the line.
            flattenMath(node.exponent, out, superscript = !subscript, subscript = subscript)
        }
        is MathNode.Sub -> {
            flattenMath(node.base, out, superscript, subscript)
            flattenMath(node.subscript, out, superscript = superscript, subscript = !superscript)
        }
        is MathNode.Frac -> {
            val numerator = flatText(node.numerator)
            val denominator = flatText(node.denominator)
            out += Span(
                "${parenthesise(numerator)}/${parenthesise(denominator)}",
                math = true,
                superscript = superscript,
                subscript = subscript,
            )
        }
        is MathNode.Sqrt -> out += Span(
            "√${parenthesise(flatText(node.body))}",
            math = true,
            superscript = superscript,
            subscript = subscript,
        )
    }
}

/** Only where it changes the reading: a+b over c is not a+b/c. */
private fun parenthesise(text: String): String =
    if (text.length > 1 && text.any { it in "+-±×÷ " }) "($text)" else text

private fun flatText(node: MathNode): String = when (node) {
    is MathNode.Sym -> node.text
    is MathNode.Seq -> node.items.joinToString("") { flatText(it) }
    is MathNode.Upright -> flatText(node.body)
    is MathNode.Sup -> flatText(node.base) + "^" + flatText(node.exponent)
    is MathNode.Sub -> flatText(node.base) + "_" + flatText(node.subscript)
    is MathNode.Frac -> parenthesise(flatText(node.numerator)) + "/" + parenthesise(flatText(node.denominator))
    is MathNode.Sqrt -> "√" + parenthesise(flatText(node.body))
}
