package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * A draggable rail divider.
 *
 * The visible line stays 1dp so it reads as a seam rather than a control, but
 * the touch target is 12dp wide — a 1dp target is unhittable with a finger, and
 * this is a tablet surface where the alternative is a stylus-only affordance.
 *
 * [onDelta] fires per drag frame with a dp delta and is expected to move
 * transient state; [onCommit] fires once when the gesture ends, which is where
 * persistence belongs. Writing to storage on every frame would queue a DataStore
 * write per pixel.
 */
@Composable
fun PaneDivider(
    onDelta: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRunColors.current
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxHeight()
            .width(12.dp)
            // Painted, not transparent. Between two panes of a Scaffold the
            // background arrives from behind and this changed nothing; the
            // docked drawer sits outside one, and there an unpainted 12dp strip
            // let the window's own black through — a gutter whose two edges read
            // as a thick double line rather than as one seam.
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onCommit()
                    },
                    onDragCancel = {
                        dragging = false
                        onCommit()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    onDelta(dragAmount / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (dragging) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(if (dragging) colors.running else colors.line),
        )
        // Grip pips, so the seam is discoverable as something you can pull.
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (dragging) colors.running else colors.line),
        )
    }
}
