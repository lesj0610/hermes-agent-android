package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.RailPanel
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEditorTest {

    @Test
    fun `options drop panels the gateway cannot serve`() {
        assertEquals(
            listOf(RailPanel.None, RailPanel.Sessions, RailPanel.Activity),
            railPanelOptions(showCron = false, showGateway = false),
        )
    }

    @Test
    fun `hiding a rail is always offered`() {
        // None needs no server support, so it survives the strictest filter.
        assertEquals(RailPanel.None, railPanelOptions(false, false).first())
    }

    @Test
    fun `cycling wraps around`() {
        val options = railPanelOptions(showCron = true, showGateway = true)
        assertEquals(RailPanel.Sessions, nextRailPanel(RailPanel.None, options))
        assertEquals(RailPanel.None, nextRailPanel(options.last(), options))
    }

    @Test
    fun `cycling recovers from a value no longer offered`() {
        // Chosen against a gateway with cron, then reconnected to one without.
        val options = railPanelOptions(showCron = false, showGateway = false)
        assertEquals(RailPanel.None, nextRailPanel(RailPanel.Cron, options))
    }

    @Test
    fun `an unsupported stored choice renders as hidden without being rewritten`() {
        assertEquals(
            RailPanel.None,
            effectiveRailPanel(RailPanel.Cron, showCron = false, showGateway = true),
        )
        assertEquals(
            RailPanel.Cron,
            effectiveRailPanel(RailPanel.Cron, showCron = true, showGateway = true),
        )
    }

    @Test
    fun `panels needing no capability are unaffected`() {
        assertEquals(
            RailPanel.Sessions,
            effectiveRailPanel(RailPanel.Sessions, showCron = false, showGateway = false),
        )
    }
}
