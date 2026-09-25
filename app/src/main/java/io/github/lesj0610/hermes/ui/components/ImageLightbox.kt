package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.core.Attachments

/** How far in a pinch can go. Past this the source pixels are gone anyway. */
private const val MAX_SCALE = 6f

/** What a double tap jumps to, and jumps back from. */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * One picture, full screen, pinch to zoom.
 *
 * The transcript draws attachments small enough to read around, which is the
 * wrong size for the thing itself — a screenshot of a terminal is legible in
 * the bubble only by accident. Tapping opens it here instead.
 *
 * Decoded at the display's own size rather than the thumbnail's: this is the
 * view where the detail is the point.
 */
@Composable
fun ImageLightbox(dataUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // The dialog's default width would letterbox a full-screen viewer.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val density = LocalDensity.current
        val closeLabel = stringResource(R.string.image_close)

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .semantics { contentDescription = closeLabel },
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val bitmap = remember(dataUrl, widthPx) {
                Attachments.decodeDataUrl(dataUrl, widthPx.coerceAtLeast(1))?.asImageBitmap()
            }

            var scale by remember(dataUrl) { mutableFloatStateOf(1f) }
            var offset by remember(dataUrl) { mutableStateOf(Offset.Zero) }

            if (bitmap == null) {
                // Nothing to show and no way to zoom it; a tap still leaves.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                )
                return@BoxWithConstraints
            }

            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(dataUrl) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            // Panning only means something once there is more
                            // picture than screen; at rest it snaps back so a
                            // stray drag cannot leave the image off-centre.
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(dataUrl) {
                        detectTapGestures(
                            // A tap on an unzoomed picture closes; once zoomed
                            // it does not, because that is a missed pan.
                            onTap = { if (scale <= 1.01f) onDismiss() },
                            onDoubleTap = { point ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    // Zoom toward the tap rather than the
                                    // centre: the detail someone wants is the
                                    // one they pointed at.
                                    val centre = Offset(size.width / 2f, size.height / 2f)
                                    offset = (centre - point) * (DOUBLE_TAP_SCALE - 1f)
                                }
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
