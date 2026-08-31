package io.github.lesj0610.hermes.ui.markdown

/**
 * The same blocks, emitted as HTML for the KaTeX renderer.
 *
 * Only replies that contain maths take this path. Everything else stays on the
 * native renderer, which streams without reloading and keeps the transcript one
 * scrolling surface — so the cost of a WebView is paid only where it buys
 * something the native renderer cannot draw.
 *
 * The page is styled from the app's own colours so a reply with an equation in
 * it looks like a reply without one.
 */

/** The colours and metrics the page inherits from the running theme. */
data class HtmlTheme(
    val text: String,
    val muted: String,
    val link: String,
    val raised: String,
    val line: String,
    val fontSizePx: Int,
)

/**
 * A complete document for [markdown].
 *
 * Loaded with a `file:///android_asset/` base URL so the stylesheet, the script
 * and the fonts resolve locally. Nothing here reaches the network.
 */
fun renderHtml(markdown: String, theme: HtmlTheme): String {
    val blocks = parseMarkdown(markdown, preserveMath = true)
    val body = buildString { blocks.forEach { appendBlock(it) } }
    return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="katex/katex.min.css">
        <style>${style(theme)}</style>
        </head><body><div id="content">$body</div>
        <script src="katex/katex.min.js"></script>
        <script>${SCRIPT}</script>
        </body></html>
    """.trimIndent()
}

private fun style(theme: HtmlTheme) = """
    html, body {
      margin: 0; padding: 0;
      background: transparent;
      color: ${theme.text};
      font-size: ${theme.fontSizePx}px;
      line-height: 1.45;
      font-family: system-ui, -apple-system, sans-serif;
      /* Long words and URLs break rather than widening the page, which would
         otherwise force the whole transcript to scroll sideways. */
      overflow-wrap: anywhere;
    }
    #content > *:first-child { margin-top: 0; }
    #content > *:last-child { margin-bottom: 0; }
    p, ul, ol, blockquote, pre, table { margin: 0 0 0.5em 0; }
    h1, h2, h3, h4, h5, h6 { margin: 0.3em 0 0.35em; font-weight: 600; line-height: 1.3; }
    h1, h2 { font-size: 1.25em; }
    h3, h4, h5, h6 { font-size: 1.1em; }
    a { color: ${theme.link}; }
    ul, ol { padding-left: 1.3em; }
    li { margin: 0.15em 0; }
    blockquote {
      margin-left: 0; padding-left: 0.6em;
      border-left: 2px solid ${theme.line};
      color: ${theme.muted};
    }
    code {
      font-family: ui-monospace, monospace;
      background: ${theme.raised};
      border-radius: 3px;
      padding: 0.1em 0.25em;
      font-size: 0.92em;
    }
    /* Code and tables scroll inside their own box; the body never does. */
    pre {
      background: ${theme.raised};
      border-radius: 6px;
      padding: 7px 9px;
      overflow-x: auto;
    }
    pre code { background: none; padding: 0; font-size: 0.86em; }
    pre .lang { display: block; color: ${theme.muted}; font-size: 0.75em; margin-bottom: 3px; }
    .tablewrap { overflow-x: auto; margin: 0 0 0.5em 0; }
    table {
      border-collapse: collapse;
      background: ${theme.raised};
      border-radius: 6px;
      overflow: hidden;
      margin: 0;
    }
    th, td { padding: 5px 8px; border-bottom: 1px solid ${theme.line}; white-space: nowrap; }
    th { font-weight: 600; }
    tr:last-child td { border-bottom: none; }
    hr { border: none; border-top: 1px solid ${theme.line}; margin: 0.6em 0; }
    .katex-display { margin: 0.5em 0; overflow-x: auto; overflow-y: hidden; padding: 2px 0; }
    /* An expression KaTeX rejects keeps its source visible rather than
       becoming a blank space in the middle of a sentence. */
    .tex-error { color: ${theme.muted}; font-family: ui-monospace, monospace; font-size: 0.9em; }
""".trimIndent()

private val SCRIPT = """
    (function () {
      var nodes = document.querySelectorAll('.tex');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var src = el.getAttribute('data-tex');
        try {
          katex.render(src, el, {
            displayMode: el.getAttribute('data-display') === '1',
            throwOnError: false,
            strict: false,
          });
        } catch (e) {
          el.className = 'tex-error';
          el.textContent = src;
        }
      }
      function report() {
        if (window.HermesHost && HermesHost.onHeight) {
          HermesHost.onHeight(document.documentElement.scrollHeight);
        }
      }
      report();
      // Fonts land after first paint and change every line's height, so the
      // measurement is reported again once they have.
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(report);
      window.addEventListener('load', report);
      if (window.ResizeObserver) new ResizeObserver(report).observe(document.body);
    })();
""".trimIndent()

private fun StringBuilder.appendBlock(block: Block) {
    when (block) {
        is Block.Paragraph -> {
            append("<p>")
            appendSpans(block.spans)
            append("</p>")
        }

        is Block.Heading -> {
            val level = block.level.coerceIn(1, 6)
            append("<h").append(level).append('>')
            appendSpans(block.spans)
            append("</h").append(level).append('>')
        }

        // Bullets and numbers arrive one block at a time, so each is its own
        // single-item list. The margins are collapsed in CSS, which reads the
        // same as one list and costs nothing to build.
        is Block.Bullet -> {
            append("<ul style=\"margin:0;padding-left:").append(1.3 + block.depth * 1.1).append("em\"><li>")
            appendSpans(block.spans)
            append("</li></ul>")
        }

        is Block.Numbered -> {
            val start = block.marker.dropLast(1).toIntOrNull() ?: 1
            append("<ol start=\"").append(start)
                .append("\" style=\"margin:0;padding-left:").append(1.5 + block.depth * 1.1).append("em\"><li>")
            appendSpans(block.spans)
            append("</li></ol>")
        }

        is Block.Quote -> {
            append("<blockquote>")
            appendSpans(block.spans)
            append("</blockquote>")
        }

        is Block.Code -> {
            append("<pre>")
            block.language?.let { append("<span class=\"lang\">").append(escape(it)).append("</span>") }
            append("<code>").append(escape(block.code)).append("</code></pre>")
        }

        is Block.Table -> {
            append("<div class=\"tablewrap\"><table><thead><tr>")
            block.header.forEachIndexed { column, cell ->
                append("<th style=\"text-align:").append(align(block.align, column)).append("\">")
                appendSpans(cell)
                append("</th>")
            }
            append("</tr></thead><tbody>")
            block.rows.forEach { row ->
                append("<tr>")
                row.forEachIndexed { column, cell ->
                    append("<td style=\"text-align:").append(align(block.align, column)).append("\">")
                    appendSpans(cell)
                    append("</td>")
                }
                append("</tr>")
            }
            append("</tbody></table></div>")
        }

        is Block.Math -> appendTex(block.latex, display = true)

        Block.Rule -> append("<hr>")
    }
}

private fun align(alignments: List<Align>, column: Int) = when (alignments.getOrNull(column)) {
    Align.Center -> "center"
    Align.End -> "right"
    else -> "left"
}

private fun StringBuilder.appendSpans(spans: List<Span>) {
    spans.forEach { span ->
        span.mathLatex?.let {
            appendTex(it, display = false)
            return@forEach
        }
        val open = StringBuilder()
        val close = StringBuilder()
        fun wrap(tag: String) {
            open.append('<').append(tag).append('>')
            close.insert(0, "</$tag>")
        }
        span.link?.let {
            open.append("<a href=\"").append(escape(it)).append("\">")
            close.insert(0, "</a>")
        }
        if (span.bold) wrap("strong")
        if (span.italic) wrap("em")
        if (span.strike) wrap("s")
        if (span.code) wrap("code")
        append(open).append(escape(span.text)).append(close)
    }
}

/**
 * KaTeX is handed the source through an attribute rather than inline script,
 * so a backslash or a quote in an expression cannot break out of the page.
 */
private fun StringBuilder.appendTex(latex: String, display: Boolean) {
    append("<span class=\"tex\" data-display=\"")
        .append(if (display) '1' else '0')
        .append("\" data-tex=\"")
        .append(escape(latex))
        .append("\"></span>")
}

private fun escape(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/**
 * Whether [text] has maths in it, and so needs the KaTeX renderer.
 *
 * Reuses the parser rather than matching delimiters here, so the two agree on
 * what counts — in particular that `$100 to $200` is money.
 */
fun containsMath(text: String): Boolean =
    parseMarkdown(text, preserveMath = true).any { block ->
        when (block) {
            is Block.Math -> true
            is Block.Paragraph -> block.spans.any { it.mathLatex != null }
            is Block.Heading -> block.spans.any { it.mathLatex != null }
            is Block.Bullet -> block.spans.any { it.mathLatex != null }
            is Block.Numbered -> block.spans.any { it.mathLatex != null }
            is Block.Quote -> block.spans.any { it.mathLatex != null }
            is Block.Table -> (listOf(block.header) + block.rows)
                .any { row -> row.any { cell -> cell.any { it.mathLatex != null } } }
            else -> false
        }
    }
