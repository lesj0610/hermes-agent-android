package io.github.lesj0610.hermes.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turning a picked image into something the run request can carry.
 *
 * The gateway has no upload route, so an attachment travels inline as a
 * `data:` URL inside the message's content parts. That makes size the whole
 * problem: a modern phone photo is several megabytes, base64 adds a third
 * again, and the run request is a single JSON body.
 */
object Attachments {

    /**
     * Longest edge after downscaling.
     *
     * Vision models tile their input at around this resolution and gain nothing
     * from more, so sending a 4000px photo costs bandwidth and tokens to
     * produce an image the model immediately shrinks.
     */
    const val MAX_EDGE = 1568

    private const val JPEG_QUALITY = 85

    /**
     * Read [uri] and return it as a `data:image/jpeg;base64,…` URL, or null if
     * it could not be decoded.
     *
     * Decoded twice on purpose: the first pass reads only the bounds, so the
     * sample size is chosen before any pixels are allocated. Loading a large
     * photo at full size to measure it is how an image picker turns into an
     * OutOfMemoryError on the device that took the picture.
     *
     * Blocking. Call it off the main thread.
     */
    fun toDataUrl(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // inSampleSize only halves, so the result can still be up to twice the
        // target on its longest edge; this brings it the rest of the way.
        val scaled = scaleToFit(decoded)
        val bytes = ByteArrayOutputStream().also { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }.toByteArray()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    /** The largest power-of-two reduction that stays at or above [MAX_EDGE]. */
    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
