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
import androidx.compose.ui.graphics.drawscope.rotate
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

/**
 * A single chevron. Points right by default — "there is a page behind this
 * row" — and left for the way back out of one.
 */
@Composable
fun ChevronIcon(modifier: Modifier = Modifier, tint: Color? = null, pointLeft: Boolean = false) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        // Narrower than the icon box: a chevron drawn to the full width reads
        // as a shallow arrow rather than as a pointer.
        val x = bounds.width * 0.28f
        val tipX = if (pointLeft) bounds.center.x - x else bounds.center.x + x
        val baseX = if (pointLeft) bounds.center.x + x else bounds.center.x - x
        val y = bounds.height * 0.26f
        val path = Path().apply {
            moveTo(baseX, bounds.center.y - y)
            lineTo(tipX, bounds.center.y)
            lineTo(baseX, bounds.center.y + y)
        }
        drawPath(path, color, style = iconStroke())
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

/** Three dots: this row has a menu. */
@Composable
fun MoreIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val radius = bounds.width * 0.09f
        repeat(3) { index ->
            drawCircle(
                color,
                radius = radius,
                center = Offset(bounds.center.x, bounds.top + bounds.height * (0.18f + index * 0.32f)),
            )
        }
    }
}

/**
 * A pushpin, side on: keep this at the top of the list.
 *
 * Drawn as head, shoulders and needle rather than as a circle on a stick —
 * a disc with a straight stem is the magnifier this icon set already has, and
 * two rows apart in the same menu they were the same glyph.
 */
@Composable
fun PinIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val headY = bounds.top + bounds.height * 0.10f
        val flangeY = bounds.top + bounds.height * 0.56f
        val headHalf = bounds.width * 0.20f
        val flangeHalf = bounds.width * 0.32f
        val waist = bounds.width * 0.12f

        val body = Path().apply {
            moveTo(bounds.center.x - headHalf, headY)
            lineTo(bounds.center.x + headHalf, headY)
            lineTo(bounds.center.x + waist, flangeY)
            lineTo(bounds.center.x + flangeHalf, flangeY)
            lineTo(bounds.center.x - flangeHalf, flangeY)
            lineTo(bounds.center.x - waist, flangeY)
            close()
        }
        drawPath(body, color, style = iconStroke())
        drawLine(
            color,
            Offset(bounds.center.x, flangeY),
            Offset(bounds.center.x, bounds.bottom),
            STROKE_DP.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

/** A folder with its tab: a directory on the agent's machine. */
@Composable
fun FolderIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val top = bounds.top + bounds.height * 0.18f
        val tabRight = bounds.left + bounds.width * 0.42f
        // The tab is a step up from the body's top edge rather than a separate
        // shape, so the two never separate by a hairline at small sizes.
        val folder = Path().apply {
            moveTo(bounds.left, bounds.bottom)
            lineTo(bounds.left, bounds.top + bounds.height * 0.06f)
            lineTo(tabRight - bounds.width * 0.08f, bounds.top + bounds.height * 0.06f)
            lineTo(tabRight, top)
            lineTo(bounds.right, top)
            lineTo(bounds.right, bounds.bottom)
            close()
        }
        drawPath(folder, color, style = iconStroke())
    }
}

/** A page with a folded corner: what the runs produced. */
@Composable
fun DocumentIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val left = bounds.left + bounds.width * 0.16f
        val right = bounds.right - bounds.width * 0.16f
        val fold = bounds.width * 0.26f
        val page = Path().apply {
            moveTo(right - fold, bounds.top)
            lineTo(left, bounds.top)
            lineTo(left, bounds.bottom)
            lineTo(right, bounds.bottom)
            lineTo(right, bounds.top + fold)
            close()
        }
        drawPath(page, color, style = iconStroke())
        // The fold itself, drawn as the corner it implies rather than as a
        // diagonal across the page.
        val corner = Path().apply {
            moveTo(right - fold, bounds.top)
            lineTo(right - fold, bounds.top + fold)
            lineTo(right, bounds.top + fold)
        }
        drawPath(corner, color, style = iconStroke())
    }
}

/**
 * A paperclip: attach a document.
 *
 * A stadium outline with an inner return, tilted. The previous attempt drew one
 * continuous hooked path and came out as a rounded rectangle at list sizes —
 * the tilt is what separates a clip from a box, and the inner stroke stopping
 * short is what says the wire doubles back.
 */
@Composable
fun PaperclipIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        rotate(degrees = 22f, pivot = bounds.center) {
            val halfWidth = bounds.width * 0.19f
            val top = bounds.top + bounds.height * 0.06f
            val bottom = bounds.bottom - bounds.height * 0.06f
            val body = Rect(
                bounds.center.x - halfWidth, top,
                bounds.center.x + halfWidth, bottom,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(body.left, body.top),
                size = Size(body.width, body.height),
                cornerRadius = CornerRadius(halfWidth),
                style = iconStroke(),
            )
            // The inner wire: down from under the top bend, stopping above the
            // bottom one, which is where a real clip's short leg ends.
            val innerTop = top + bounds.height * 0.18f
            val innerBottom = bottom - bounds.height * 0.30f
            drawLine(
                color,
                Offset(bounds.center.x + halfWidth * 0.45f, innerTop),
                Offset(bounds.center.x + halfWidth * 0.45f, innerBottom),
                STROKE_DP.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

/**
 * A box with an arrow leaving it: this opens somewhere outside the app.
 *
 * Not a chain — two interlocking links need detail that disappears below 20dp,
 * and the arrow is what actually tells you the tap leaves.
 */
@Composable
fun LinkIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val gap = bounds.width * 0.42f
        // An open corner rather than a closed box: the arrow crosses where the
        // outline stops, so the two shapes never collide at small sizes.
        val frame = Path().apply {
            moveTo(bounds.right - gap, bounds.top)
            lineTo(bounds.left, bounds.top)
            lineTo(bounds.left, bounds.bottom)
            lineTo(bounds.right, bounds.bottom)
            lineTo(bounds.right, bounds.top + gap)
        }
        drawPath(frame, color, style = iconStroke())

        val tip = Offset(bounds.right, bounds.top)
        drawLine(
            color,
            Offset(bounds.center.x, bounds.center.y),
            tip,
            STROKE_DP.dp.toPx(),
            StrokeCap.Round,
        )
        val barb = bounds.width * 0.26f
        drawPath(
            Path().apply {
                moveTo(tip.x - barb, tip.y)
                lineTo(tip.x, tip.y)
                lineTo(tip.x, tip.y + barb)
            },
            color,
            style = iconStroke(),
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

// ── row-menu glyphs ───────────────────────────────────────────────────────
//
// A menu of bare words reads as a wall of text; the glyph is what lets the eye
// land on the right line without reading all of them. Drawn at the same weight
// as the rest of the set so a menu does not look like it borrowed its icons.

/** A pencil: change the name. */
@Composable
fun PencilIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        rotate(degrees = 45f, pivot = bounds.center) {
            val half = bounds.width * 0.16f
            val top = bounds.top + bounds.height * 0.10f
            val neck = bounds.bottom - bounds.height * 0.26f
            // Barrel, then the nib as a separate triangle: a single tapered
            // outline loses its point once the stroke is rounded.
            val barrel = Path().apply {
                moveTo(bounds.center.x - half, neck)
                lineTo(bounds.center.x - half, top)
                lineTo(bounds.center.x + half, top)
                lineTo(bounds.center.x + half, neck)
            }
            drawPath(barrel, color, style = iconStroke())
            val nib = Path().apply {
                moveTo(bounds.center.x - half, neck)
                lineTo(bounds.center.x, bounds.bottom)
                lineTo(bounds.center.x + half, neck)
            }
            drawPath(nib, color, style = iconStroke())
            drawLine(
                color,
                Offset(bounds.center.x - half, top + bounds.height * 0.14f),
                Offset(bounds.center.x + half, top + bounds.height * 0.14f),
                STROKE_DP.dp.toPx(),
            )
        }
    }
}

/** Two stacked sheets: copy this to the clipboard. */
@Composable
fun CopyIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val side = bounds.width * 0.66f
        val radius = CornerRadius(3.5.dp.toPx())
        // Back sheet first so the front one's stroke sits over it, which is
        // what makes the two read as stacked rather than as one shape.
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(side, side),
            cornerRadius = radius,
            style = iconStroke(),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.right - side, bounds.bottom - side),
            size = Size(side, side),
            cornerRadius = radius,
            style = iconStroke(),
        )
    }
}

/** A line splitting off to a second node: fork this conversation. */
@Composable
fun BranchIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val node = bounds.width * 0.13f
        val trunkX = bounds.left + bounds.width * 0.26f
        val branchX = bounds.right - bounds.width * 0.20f
        val top = bounds.top + node
        val bottom = bounds.bottom - node

        drawLine(
            color, Offset(trunkX, top), Offset(trunkX, bottom),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
        // The split leaves the trunk halfway and curves up to its own node,
        // which is what separates this from a plain fork of two lines.
        val split = Path().apply {
            moveTo(trunkX, bounds.center.y + bounds.height * 0.12f)
            quadraticTo(
                trunkX + bounds.width * 0.36f, bounds.center.y + bounds.height * 0.12f,
                branchX, top + node * 1.6f,
            )
        }
        drawPath(split, color, style = iconStroke())
        drawCircle(color, radius = node, center = Offset(trunkX, top), style = iconStroke())
        drawCircle(color, radius = node, center = Offset(trunkX, bottom), style = iconStroke())
        drawCircle(color, radius = node, center = Offset(branchX, top), style = iconStroke())
    }
}

/** An arrow leaving a tray: hand this to something else. */
@Composable
fun ExportIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val trayTop = bounds.top + bounds.height * 0.46f
        // Open at the top so the arrow passes through the gap rather than
        // crossing a line.
        val tray = Path().apply {
            moveTo(bounds.left, trayTop)
            lineTo(bounds.left, bounds.bottom)
            lineTo(bounds.right, bounds.bottom)
            lineTo(bounds.right, trayTop)
        }
        drawPath(tray, color, style = iconStroke())

        val tip = Offset(bounds.center.x, bounds.top)
        drawLine(
            color, Offset(bounds.center.x, bounds.center.y + bounds.height * 0.14f), tip,
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
        val barb = bounds.width * 0.20f
        drawPath(
            Path().apply {
                moveTo(tip.x - barb, tip.y + barb)
                lineTo(tip.x, tip.y)
                lineTo(tip.x + barb, tip.y + barb)
            },
            color,
            style = iconStroke(),
        )
    }
}

/** A lidded box: keep it, out of the way. */
@Composable
fun ArchiveIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val lid = bounds.height * 0.26f
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left, bounds.top),
            size = Size(bounds.width, lid),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = iconStroke(),
        )
        val bodyTop = bounds.top + lid + bounds.height * 0.06f
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left + bounds.width * 0.06f, bodyTop),
            size = Size(bounds.width * 0.88f, bounds.bottom - bodyTop),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = iconStroke(),
        )
        // The pull, which is the detail that says "box" rather than "two rects".
        drawLine(
            color,
            Offset(bounds.center.x - bounds.width * 0.16f, bodyTop + bounds.height * 0.18f),
            Offset(bounds.center.x + bounds.width * 0.16f, bodyTop + bounds.height * 0.18f),
            STROKE_DP.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

/** A bin: this one does not come back. */
@Composable
fun TrashIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val lidY = bounds.top + bounds.height * 0.22f
        drawLine(
            color, Offset(bounds.left, lidY), Offset(bounds.right, lidY),
            STROKE_DP.dp.toPx(), StrokeCap.Round,
        )
        // The handle above the lid line.
        drawPath(
            Path().apply {
                moveTo(bounds.center.x - bounds.width * 0.16f, lidY)
                lineTo(bounds.center.x - bounds.width * 0.16f, bounds.top + bounds.height * 0.08f)
                lineTo(bounds.center.x + bounds.width * 0.16f, bounds.top + bounds.height * 0.08f)
                lineTo(bounds.center.x + bounds.width * 0.16f, lidY)
            },
            color,
            style = iconStroke(),
        )
        val can = Path().apply {
            moveTo(bounds.left + bounds.width * 0.14f, lidY)
            lineTo(bounds.left + bounds.width * 0.20f, bounds.bottom)
            lineTo(bounds.right - bounds.width * 0.20f, bounds.bottom)
            lineTo(bounds.right - bounds.width * 0.14f, lidY)
        }
        drawPath(can, color, style = iconStroke())
    }
}

/** A tick: this is the one in effect. */
@Composable
fun CheckIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        drawPath(
            Path().apply {
                moveTo(bounds.left + bounds.width * 0.14f, bounds.center.y + bounds.height * 0.02f)
                lineTo(bounds.left + bounds.width * 0.38f, bounds.bottom - bounds.height * 0.20f)
                lineTo(bounds.right - bounds.width * 0.10f, bounds.top + bounds.height * 0.22f)
            },
            color,
            style = iconStroke(),
        )
    }
}

/**
 * A circular arrow: run the scan again.
 *
 * Kept for the artifacts sweep only. The lists that re-read themselves have no
 * such control — this one is a deliberate pass over twenty session histories,
 * and an expensive thing should be asked for.
 */
@Composable
fun RefreshIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val radius = bounds.width * 0.36f
        val centre = bounds.center
        // Open at the top right, which is where the head goes: a closed ring
        // with an arrow stuck on it reads as a ring with a defect.
        drawArc(
            color = color,
            startAngle = -40f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = iconStroke(),
        )
        val angle = Math.toRadians(-40.0)
        val tip = Offset(
            centre.x + radius * cos(angle).toFloat(),
            centre.y + radius * sin(angle).toFloat(),
        )
        val barb = bounds.width * 0.20f
        drawPath(
            Path().apply {
                moveTo(tip.x - barb, tip.y - barb * 0.35f)
                lineTo(tip.x, tip.y)
                lineTo(tip.x + barb * 0.30f, tip.y + barb)
            },
            color,
            style = iconStroke(),
        )
    }
}

/**
 * A filled square: interrupt the run.
 *
 * Solid rather than outlined, and square rather than an X. It sits in the same
 * circle the send arrow uses, so the shape has to carry the difference on its
 * own — an outline at this size reads as another glyph in the set, and an X
 * reads as dismiss.
 */
@Composable
fun StopIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalRunColors.current.muted
    Canvas(modifier.size(ICON_DP.dp)) {
        val bounds = iconBounds()
        val side = bounds.width * 0.46f
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.center.x - side / 2f, bounds.center.y - side / 2f),
            size = Size(side, side),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}
