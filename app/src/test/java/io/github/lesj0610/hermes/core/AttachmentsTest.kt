package io.github.lesj0610.hermes.core

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attachment decoding.
 *
 * The property that matters here is that nothing throws. A picked photo used to
 * disappear without a word, and one of the ways it got there was an exception
 * escaping into the picker's coroutine: `openInputStream` raises on a revoked
 * grant or a provider that has gone away, and the grant a picker hands out is
 * not guaranteed to outlive the pick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a uri that cannot be opened returns null rather than throwing`() {
        assertNull(Attachments.toDataUrl(context, Uri.parse("content://does.not.exist/1")))
    }

    @Test
    fun `a file uri that is not there returns null rather than throwing`() {
        assertNull(Attachments.toDataUrl(context, Uri.parse("file:///nowhere/missing.jpg")))
    }

    @Test
    fun `a uri with no scheme returns null rather than throwing`() {
        assertNull(Attachments.toDataUrl(context, Uri.parse("not a uri at all")))
    }

    @Test
    fun `a document that cannot be opened returns null rather than throwing`() {
        assertNull(Attachments.readDocument(context, Uri.parse("content://does.not.exist/1")))
    }

    @Test
    fun `the sample size is the largest halving that stays above the target edge`() {
        // Picked before any pixels are allocated, which is what keeps a 12MP
        // photo from becoming an OutOfMemoryError on the phone that took it.
        assertEquals(1, Attachments.sampleSize(1000, 800))
        assertEquals(1, Attachments.sampleSize(Attachments.MAX_EDGE, 100))
        assertEquals(2, Attachments.sampleSize(Attachments.MAX_EDGE * 2, 100))
        assertEquals(4, Attachments.sampleSize(Attachments.MAX_EDGE * 4, 100))
        // Orientation does not matter: the longest edge decides.
        assertEquals(4, Attachments.sampleSize(100, Attachments.MAX_EDGE * 4))
    }

    @Test
    fun `sampling never goes below one`() {
        assertEquals(1, Attachments.sampleSize(1, 1))
        assertEquals(1, Attachments.sampleSize(0, 0))
    }
}
