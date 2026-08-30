package io.github.lesj0610.hermes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * Renders the agent's Markdown.
 *
 * Replies arrive with fenced code, bullets and bold in them, and drawing that
 * as one flat string put the punctuation on screen instead of the structure.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    // Re-parsed only when the text changes, which during a stream is every
    // delta — the parser is a single pass and allocates per block, which is
    // cheaper than the recomposition that follows it either way.
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = LocalRunColors.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(block.spans.annotated(), style = style)

                is Block.Heading -> Text(
                    text = block.spans.annotated(),
                    style = style.copy(
                        // Only two steps: a phone column is too narrow for a
                        // six-level scale to read as a hierarchy.
                        fontSize = if (block.level <= 2) style.fontSize * 1.25f else style.fontSize * 1.1f,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )

                is Block.Bullet -> MarkdownRow(
                    marker = "•",
                    depth = block.depth,
                    spans = block.spans,
                    style = style,
                )

                is Block.Numbered -> MarkdownRow(
                    marker = block.marker,
                    depth = block.depth,
                    spans = block.spans,
                    style = style,
                )

                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .background(colors.line),
                    )
                    Text(
                        text = block.spans.annotated(),
                        style = style,
                        color = colors.muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                is Block.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.panelRaised),
                ) {
                    block.language?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                            modifier = Modifier.padding(start = 9.dp, top = 5.dp),
                        )
                    }
                    // Code scrolls sideways rather than wrapping: a wrapped
                    // command line is a different command line.
                    Text(
                        text = block.code,
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                }

                Block.Rule -> HorizontalDivider(color = colors.line)
            }
        }
    }
}

@Composable
private fun MarkdownRow(marker: String, depth: Int, spans: List<Span>, style: TextStyle) {
    val colors = LocalRunColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth.coerceAtMost(3) * 14).dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = marker, style = style, color = colors.muted)
        Text(text = spans.annotated(), style = style, modifier = Modifier.weight(1f))
    }
}

/** The marks, applied. Links carry their own annotation so a tap opens them. */
@Composable
private fun List<Span>.annotated(): AnnotatedString {
    val colors = LocalRunColors.current
    val uriHandler = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        forEach { span ->
            val spanStyle = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) colors.panelRaised else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            )
            if (span.link != null) {
                withLink(
                    LinkAnnotation.Url(
                        span.link,
                        styles = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                    ) { uriHandler.openUri(span.link) },
                ) {
                    withStyle(spanStyle) { append(span.text) }
                }
            } else {
                withStyle(spanStyle) { append(span.text) }
            }
        }
    }
}
