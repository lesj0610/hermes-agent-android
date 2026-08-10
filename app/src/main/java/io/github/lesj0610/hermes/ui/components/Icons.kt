package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icon set, drawn rather than imported.
 *
 * Material's icon artifacts are a separate dependency and this app needs four
 * glyphs from them. Drawing them keeps the download small and keeps the weights
 * consistent — the bundled icons are designed against a 24dp grid with a 2dp
 * stroke, which does not match the hairline strokes used elsewhere here.
 */

private const val ICON_DP = 22
private const val STROKE_DP = 1.6f

/** Strokes every icon shares, so they read as one family. */
private fun DrawScope.iconStroke() = Stroke(width = STROKE_DP.dp.toPx(), cap = StrokeCap.Round)

/**
 * Inset by half the stroke width: a stroked shape drawn flush to the canvas
 * edge has its outer half clipped away, which thins two sides and not the
 * others.
 */
private fun DrawScope.iconBounds(): Rect {
    val pad = STROKE_DP.dp.toPx() / 2f
    return Rect(pad, pad, size.width - pad, size.height - pad)
}

/** Three-line menu. */
@Composable
fun HamburgerIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Column(
        modifier.size(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
            if (index < 2) Box(Modifier.height(4.dp))
        }
    }
}

/** A square with a plus in it: start a new session. */
@Composable
fun NewSessionIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(bounds.width, bounds.height),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = iconStroke(),
        )
        val arm = bounds.width * 0.26f
        drawLine(
            color, Offset(bounds.center.x - arm, bounds.center.y),
            Offset(bounds.center.x + arm, bounds.center.y),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
            color, Offset(bounds.center.x, bounds.center.y - arm),
            Offset(bounds.center.x, bounds.center.y + arm),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
    }
}

/**
 * A pane split into a narrow rail and a wide body: arrange the layout.
 *
 * The rail is filled rather than outlined so the icon says *which* side is the
 * rail — an outline split by a line reads the same whichever way round it is.
 */
@Composable
fun LayoutIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val radius = CornerRadius(5.dp.toPx())
        val divider = bounds.left + bounds.width * 0.38f

        val shape = Path().apply { addRoundRect(RoundRect(bounds, radius)) }
        clipPath(shape) {
            drawRect(
                color = color.copy(alpha = 0.45f),
                topLeft = Offset(bounds.left, bounds.top),
                size = Size(divider - bounds.left, bounds.height),
            )
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(bounds.width, bounds.height),
            cornerRadius = radius,
            style = iconStroke(),
        )
        drawLine(
            color, Offset(divider, bounds.top), Offset(divider, bounds.bottom),
            STROKE_DP.dp.toPx(),
        )
    }
}

/** A speech bubble: the conversation. */
@Composable
fun ChatIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        // The bubble occupies the upper part; the lower strip is left for the
        // tail, so the glyph still sits on the same baseline as its neighbours.
        val body = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom - bounds.height * 0.22f)
        drawRoundRect(
            color = color,
            topLeft = Offset(body.left, body.top),
            size = Size(body.width, body.height),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = iconStroke(),
        )
        val tail = Path().apply {
            moveTo(body.left + body.width * 0.24f, body.bottom)
            lineTo(body.left + body.width * 0.24f, bounds.bottom)
            lineTo(body.left + body.width * 0.52f, body.bottom)
        }
        drawPath(tail, color, style = iconStroke())
    }
}

/** A clock face: scheduled runs. */
@Composable
fun ClockIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val centre = bounds.center
        val radius = bounds.width / 2f
        drawCircle(color, radius = radius, center = centre, style = iconStroke())
        drawLine(
            color, centre, Offset(centre.x, centre.y - radius * 0.52f),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
            color, centre, Offset(centre.x + radius * 0.40f, centre.y + radius * 0.28f),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
    }
}

/** Stacked units with status lights: the gateway. */
@Composable
fun ServerIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val gap = bounds.height * 0.16f
        val unit = (bounds.height - gap) / 2f
        repeat(2) { row ->
            val top = bounds.top + row * (unit + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(bounds.left, top),
                size = Size(bounds.width, unit),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = iconStroke(),
            )
            drawCircle(
                color,
                radius = 1.3.dp.toPx(),
                center = Offset(bounds.left + bounds.width * 0.22f, top + unit / 2f),
            )
        }
    }
}

/** A four-up grid: the workspace, which is a board of profiles and skills. */
@Composable
fun GridIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val gap = bounds.width * 0.16f
        val cell = (bounds.width - gap) / 2f
        for (row in 0..1) {
            for (col in 0..1) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(
                        bounds.left + col * (cell + gap),
                        bounds.top + row * (cell + gap),
                    ),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = iconStroke(),
                )
            }
        }
    }
}

/** A magnifier: filter the session list. */
@Composable
fun SearchIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val radius = bounds.width * 0.33f
        val centre = Offset(bounds.left + radius + 1.dp.toPx(), bounds.top + radius + 1.dp.toPx())
        drawCircle(color, radius = radius, center = centre, style = iconStroke())
        // The handle leaves the lens on the diagonal, at the radius, so it meets
        // the circle rather than crossing into it.
        val edge = radius * 0.7071f
        drawLine(
            color,
            Offset(centre.x + edge, centre.y + edge),
            Offset(bounds.right, bounds.bottom),
            STROKE_DP.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

/** Sliders: adjust how the columns are arranged. */
@Composable
fun SlidersIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        // Knobs at different offsets per track, which is what distinguishes
        // this from a plain list at small sizes.
        val knobs = listOf(0.68f, 0.34f, 0.55f)
        knobs.forEachIndexed { row, at ->
            val y = bounds.top + bounds.height * (0.14f + row * 0.36f)
            drawLine(
                color, Offset(bounds.left, y), Offset(bounds.right, y),
                STROKE_DP.dp.toPx(), StrokeCap.Round,
            )
            drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(bounds.left + bounds.width * at, y))
        }
    }
}


/** The usual cogwheel. */
@Composable
fun SettingsIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val centre = bounds.center
        val body = bounds.width * 0.30f
        val tooth = bounds.width * 0.14f

        // Eight teeth on the 45° diagonals, drawn as radial stubs. Real gear
        // outlines need a path with 32 vertices to look right; at 22dp that
        // detail is below the pixel grid anyway.
        repeat(8) { index ->
            val angle = index * (Math.PI / 4.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color,
                Offset(centre.x + dx * body, centre.y + dy * body),
                Offset(centre.x + dx * (body + tooth), centre.y + dy * (body + tooth)),
                STROKE_DP.dp.toPx() * 1.15f,
                StrokeCap.Round,
            )
        }
        drawCircle(color, radius = body, center = centre, style = iconStroke())
        drawCircle(color, radius = bounds.width * 0.11f, center = centre, style = iconStroke())
    }
}
