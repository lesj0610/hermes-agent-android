package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dp figures below are real window sizes, not invented ones. Phone
 * landscape being wider than the 840dp breakpoint is exactly the case a
 * width-only rule gets wrong, and tablet portrait sitting under it is the case
 * a two-tier rule gets wrong.
 */
class LayoutDecisionTest {

    private fun layout(w: Int, h: Int, mode: LayoutMode = LayoutMode.Auto) =
        resolveShellLayout(w, h, mode)

    @Test
    fun `phone portrait shows one pane`() {
        assertEquals(ShellLayout.Single, layout(411, 891))
    }

    @Test
    fun `phone landscape stays single despite passing the width breakpoint`() {
        // Pixel 8 Pro on its side: 891dp wide, 411dp tall. Wide enough for a
        // width-only check to say "tablet", far too short for rails.
        assertEquals(ShellLayout.Single, layout(891, 411))
    }

    @Test
    fun `tablet portrait keeps the session rail beside the transcript`() {
        // ~800dp wide: under the expanded breakpoint, so no activity rail, but
        // dropping all the way to one pane would throw away the rail for no
        // reason.
        assertEquals(ShellLayout.Dual, layout(800, 1280))
    }

    @Test
    fun `tablet landscape shows the full desktop-style shell`() {
        assertEquals(ShellLayout.Triple, layout(1280, 800))
    }

    @Test
    fun `a narrow split-screen window collapses to one pane`() {
        assertEquals(ShellLayout.Single, layout(480, 1000))
    }

    @Test
    fun `a foldable changes shell as it opens`() {
        assertEquals(ShellLayout.Single, layout(374, 819))
        assertEquals(ShellLayout.Triple, layout(841, 738))
    }

    @Test
    fun `manual modes ignore the window entirely`() {
        assertEquals(ShellLayout.Triple, layout(320, 320, LayoutMode.Tablet))
        assertEquals(ShellLayout.Single, layout(2560, 1600, LayoutMode.Phone))
    }

    @Test
    fun `breakpoints are inclusive on the lower edge`() {
        assertEquals(ShellLayout.Triple, layout(EXPANDED_WIDTH_DP, MIN_MULTI_PANE_HEIGHT_DP))
        assertEquals(ShellLayout.Dual, layout(EXPANDED_WIDTH_DP - 1, MIN_MULTI_PANE_HEIGHT_DP))
        assertEquals(ShellLayout.Dual, layout(MEDIUM_WIDTH_DP, MIN_MULTI_PANE_HEIGHT_DP))
        assertEquals(ShellLayout.Single, layout(MEDIUM_WIDTH_DP - 1, MIN_MULTI_PANE_HEIGHT_DP))
    }

    @Test
    fun `a short window is single-pane at any width`() {
        assertEquals(ShellLayout.Single, layout(2000, MIN_MULTI_PANE_HEIGHT_DP - 1))
    }
}
