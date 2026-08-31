package io.github.lesj0610.hermes.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * A reply, rendered.
 *
 * Two renderers, one entry point. Replies without maths — nearly all of them —
 * are drawn natively: they stream without reloading, they select as part of the
 * transcript, and the list scrolls as one surface.
 *
 * A reply with an equation in it goes to KaTeX instead, because matrices,
 * integral limits and auto-sized delimiters are a typesetting engine's job and
 * approximating them produces maths that is subtly wrong. That only happens
 * once the reply is complete: reloading a page on every delta would flicker,
 * and a half-written expression is not yet an expression.
 */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    streaming: Boolean = false,
) {
    val typeset = remember(text, streaming) { !streaming && containsMath(text) }
    if (typeset) {
        TypesetText(text, modifier, style)
    } else {
        MarkdownText(text, modifier, style)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TypesetText(text: String, modifier: Modifier, style: TextStyle) {
    val colors = LocalRunColors.current
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val onSurface = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary

    val html = remember(text, colors, style, onSurface, accent) {
        renderHtml(
            text,
            HtmlTheme(
                text = css(if (style.color == Color.Unspecified) onSurface else style.color),
                muted = css(colors.muted),
                link = css(accent),
                raised = css(colors.panelRaised),
                line = css(colors.line),
                fontSizePx = with(density) { style.fontSize.toPx() }.toInt().coerceAtLeast(10),
            ),
        )
    }

    // The page reports its own height; until it does the view has none, so an
    // unmeasured WebView never claims a screenful of empty space.
    var height by remember(text) { mutableFloatStateOf(0f) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                // The transcript scrolls; the page inside it must not, or the
                // two fight over every drag.
                isNestedScrollingEnabled = false
                settings.javaScriptEnabled = true
                // Everything it needs is in the APK. Nothing is fetched.
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeight(value: Int) {
                            post { height = value.toFloat() }
                        }
                    },
                    "HermesHost",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        // A tap on a link opens it outside, as it does
                        // everywhere else in the app.
                        uriHandler.openUri(request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { view ->
            if (view.getTag(HTML_TAG) != html) {
                view.setTag(HTML_TAG, html)
                view.loadDataWithBaseURL(
                    "file:///android_asset/",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
    )
}

/** Compose colours are ARGB ints; CSS wants #rrggbb. */
private fun css(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

private val HTML_TAG = "hermes_html".hashCode()
