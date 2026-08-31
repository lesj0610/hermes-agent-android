package io.github.lesj0610.hermes.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison, which decides whether the app tells the user anything.
 *
 * Getting this wrong is not cosmetic in either direction: too eager and the
 * app nags about a version it is already running, too shy and a release is
 * never announced at all.
 */
class UpdateTest {

    @Test
    fun `a later version is newer`() {
        assertTrue(isNewer("1.9", "1.8"))
        assertTrue(isNewer("2.0", "1.9"))
        assertTrue(isNewer("1.8.1", "1.8"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(isNewer("1.9", "1.9"))
        assertFalse(isNewer("1.9.0", "1.9"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(isNewer("1.8", "1.9"))
        assertFalse(isNewer("1.9", "1.10"))
    }

    @Test
    fun `double digits sort as numbers, not as text`() {
        // The one comparison a string sort gets backwards, and the first one
        // this project will actually hit.
        assertTrue(isNewer("1.10", "1.9"))
        assertTrue(isNewer("1.100", "1.99"))
        assertFalse(isNewer("1.9", "1.10"))
    }

    @Test
    fun `a v prefix is not part of the number`() {
        assertTrue(isNewer("v2.0", "1.9"))
        assertFalse(isNewer("v1.9", "1.9"))
    }

    @Test
    fun `a tag that is not a number does not throw`() {
        // Tags are written by hand. A malformed one must fail to announce
        // rather than crash the launch check.
        assertFalse(isNewer("", "1.9"))
        assertFalse(isNewer("nightly", "1.9"))
        assertFalse(isNewer("1.9-rc1", "1.9"))
        assertTrue(isNewer("2.0-rc1", "1.9"))
    }
}
