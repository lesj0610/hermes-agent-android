package io.github.lesj0610.hermes.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

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

    // ── documents ─────────────────────────────────────────────────────────

    /**
     * A picked document, already read into text.
     *
     * There is no document part on the run route — the gateway rejects
     * `file`/`input_file` with `unsupported_content_type` — so a document can
     * only travel as text inside the message. That makes this deliberately
     * narrow: readable text goes, anything else is refused at the picker
     * rather than failing on the server.
     */
    data class Document(val name: String, val text: String, val truncated: Boolean)

    /**
     * Ceiling on document text.
     *
     * Everything here is spent from the same turn's context window, so a large
     * log pasted whole would push the conversation out before the model reads
     * the question. 128 KB is roughly 30k tokens — enough for a source file or
     * a stack trace, not enough to evict the session.
     */
    const val MAX_DOC_BYTES = 128 * 1024

    /**
     * Read [uri] as UTF-8 text, or null if it is not text.
     *
     * "Not text" is decided by a NUL byte in the first block, which is what
     * separates a PDF or a zip from a log. Extension and MIME type are not
     * trusted: `application/octet-stream` is what a lot of providers report for
     * a perfectly readable file.
     *
     * Blocking. Call it off the main thread.
     */
    fun readDocument(context: Context, uri: Uri): Document? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // One byte past the cap, so a file sitting exactly at the limit
                // is not reported as truncated.
                val buffer = ByteArray(MAX_DOC_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.any { it == 0.toByte() }) return null

        val truncated = bytes.size > MAX_DOC_BYTES
        val text = String(
            bytes, 0, minOf(bytes.size, MAX_DOC_BYTES), Charsets.UTF_8,
        )
        return Document(displayName(context, uri), text, truncated)
    }

    /** The provider's display name, falling back to the last path segment. */
    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
    }

    // ── camera ────────────────────────────────────────────────────────────

    /**
     * A file the camera app can write to, exposed through this app's
     * FileProvider.
     *
     * A `file://` Uri cannot be handed to another app since API 24, and the
     * capture has to land somewhere this app can read back — hence the cache
     * directory plus a grant, rather than MediaStore, which would put every
     * throwaway shot in the user's gallery.
     */
    fun newCameraTarget(context: Context, stamp: Long): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-$stamp.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
