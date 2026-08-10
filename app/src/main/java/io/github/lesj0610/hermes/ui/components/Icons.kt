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

/** A plus: attach an image to the message. */
@Composable
fun PlusIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val arm = bounds.width * 0.34f
        drawLine(
            color, Offset(bounds.center.x - arm, bounds.center.y),
            Offset(bounds.center.x + arm, bounds.center.y),
            STROKE_DP.dp.toPx() * 1.1f, StrokeCap.Round,
        )
        drawLine(
            color, Offset(bounds.center.x, bounds.center.y - arm),
            Offset(bounds.center.x, bounds.center.y + arm),
            STROKE_DP.dp.toPx() * 1.1f, StrokeCap.Round,
        )
    }
}

/** An upward arrow: send the message. */
@Composable
fun SendIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val stroke = STROKE_DP.dp.toPx() * 1.2f
        drawLine(
            color, Offset(bounds.center.x, bounds.bottom),
            Offset(bounds.center.x, bounds.top), stroke, StrokeCap.Round,
        )
        val head = bounds.width * 0.32f
        drawLine(
            color, Offset(bounds.center.x - head, bounds.top + head),
            Offset(bounds.center.x, bounds.top), stroke, StrokeCap.Round,
        )
        drawLine(
            color, Offset(bounds.center.x + head, bounds.top + head),
            Offset(bounds.center.x, bounds.top), stroke, StrokeCap.Round,
        )
    }
}

/** A microphone: dictate one message. */
@Composable
fun MicIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val capsuleWidth = bounds.width * 0.36f
        val capsuleHeight = bounds.height * 0.52f
        val left = bounds.center.x - capsuleWidth / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(left, bounds.top),
            size = Size(capsuleWidth, capsuleHeight),
            cornerRadius = CornerRadius(capsuleWidth / 2f),
            style = iconStroke(),
        )
        // The cradle, drawn as a path so it curves under the capsule. Straight
        // strokes were tried first and read as a box around the capsule rather
        // than as something holding it.
        val cradleHalf = capsuleWidth * 0.78f
        val cradleTop = bounds.top + capsuleHeight * 0.58f
        val cradleBottom = bounds.top + bounds.height * 0.74f
        val cradle = Path().apply {
            moveTo(bounds.center.x - cradleHalf, cradleTop)
            quadraticTo(
                bounds.center.x, cradleBottom + capsuleWidth * 0.45f,
                bounds.center.x + cradleHalf, cradleTop,
            )
        }
        drawPath(cradle, color, style = iconStroke())
        drawLine(
            color, Offset(bounds.center.x, cradleBottom + capsuleWidth * 0.2f),
            Offset(bounds.center.x, bounds.bottom),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
    }
}

/** A waveform: the spoken conversation, where replies are read back. */
@Composable
fun WaveformIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        // Uneven bars, because five of equal height reads as a list rather than
        // as sound.
        val heights = listOf(0.34f, 0.72f, 1.0f, 0.58f, 0.28f)
        val step = bounds.width / heights.size
        heights.forEachIndexed { index, factor ->
            val x = bounds.left + step * (index + 0.5f)
            val half = bounds.height * factor / 2f
            drawLine(
                color,
                Offset(x, bounds.center.y - half),
                Offset(x, bounds.center.y + half),
                STROKE_DP.dp.toPx(),
                StrokeCap.Round,
            )
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

/** A camera body with a lens: take a picture now. */
@Composable
fun CameraIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val bodyTop = bounds.top + bounds.height * 0.24f
        // The viewfinder hump, drawn before the body so the body's stroke sits
        // over its base and the two read as one outline.
        val humpLeft = bounds.left + bounds.width * 0.30f
        val humpRight = bounds.left + bounds.width * 0.55f
        val hump = Path().apply {
            moveTo(humpLeft, bodyTop)
            lineTo(humpLeft + bounds.width * 0.06f, bounds.top + bounds.height * 0.12f)
            lineTo(humpRight - bounds.width * 0.06f, bounds.top + bounds.height * 0.12f)
            lineTo(humpRight, bodyTop)
        }
        drawPath(hump, color, style = iconStroke())
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bodyTop),
            size = Size(bounds.width, bounds.bottom - bodyTop),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = iconStroke(),
        )
        drawCircle(
            color,
            radius = bounds.width * 0.19f,
            center = Offset(bounds.center.x, (bodyTop + bounds.bottom) / 2f),
            style = iconStroke(),
        )
    }
}

/** A framed picture with a hill and a sun: pick from the gallery. */
@Composable
fun PhotoIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val radius = CornerRadius(5.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(bounds.width, bounds.height),
            cornerRadius = radius,
            style = iconStroke(),
        )
        drawCircle(
            color,
            radius = bounds.width * 0.09f,
            center = Offset(
                bounds.left + bounds.width * 0.32f,
                bounds.top + bounds.height * 0.32f,
            ),
        )
        // Clipped to the frame: the hill runs past the right edge, which is what
        // makes it read as a photograph rather than as a triangle in a box.
        val frame = Path().apply { addRoundRect(RoundRect(bounds, radius)) }
        clipPath(frame) {
            val hill = Path().apply {
                moveTo(bounds.left + bounds.width * 0.10f, bounds.bottom)
                lineTo(bounds.left + bounds.width * 0.44f, bounds.top + bounds.height * 0.52f)
                lineTo(bounds.right, bounds.bottom)
            }
            drawPath(hill, color, style = iconStroke())
        }
    }
}

/** A paperclip: attach a document. */
@Composable
fun PaperclipIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val left = bounds.left + bounds.width * 0.26f
        val right = bounds.right - bounds.width * 0.16f
        val top = bounds.top + bounds.height * 0.10f
        val outerBottom = bounds.bottom - bounds.height * 0.06f
        val innerBottom = bounds.bottom - bounds.height * 0.24f
        val mid = (left + right) / 2f

        // Two nested hooks rather than one: a single U reads as a magnet at this
        // size, and the inner return is the part that says "clip".
        val clip = Path().apply {
            moveTo(right, top + bounds.height * 0.30f)
            lineTo(right, outerBottom - bounds.height * 0.16f)
            quadraticTo(right, outerBottom, mid, outerBottom)
            quadraticTo(left, outerBottom, left, outerBottom - bounds.height * 0.16f)
            lineTo(left, top + bounds.height * 0.14f)
            quadraticTo(left, top, mid, top)
            quadraticTo(
                left + bounds.width * 0.55f, top,
                left + bounds.width * 0.55f, top + bounds.height * 0.14f,
            )
            lineTo(left + bounds.width * 0.55f, innerBottom)
        }
        drawPath(clip, color, style = iconStroke())
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
