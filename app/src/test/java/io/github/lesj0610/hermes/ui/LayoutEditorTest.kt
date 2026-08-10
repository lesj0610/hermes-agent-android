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
    fun `the dashboard panel is offered only once a dashboard is configured`() {
        // Gated on configuration rather than a gateway capability: the dashboard
        // is a separate server, so the gateway cannot answer for it.
        assertEquals(
            false,
            railPanelOptions(showCron = true, showGateway = true, showDashboard = false)
                .contains(RailPanel.Dashboard),
        )
        assertEquals(
            true,
            railPanelOptions(showCron = true, showGateway = true, showDashboard = true)
                .contains(RailPanel.Dashboard),
        )
    }

    @Test
    fun `a dashboard rail collapses when the dashboard is removed`() {
        assertEquals(
            RailPanel.None,
            effectiveRailPanel(RailPanel.Dashboard, showCron = true, showGateway = true, showDashboard = false),
        )
        assertEquals(
            RailPanel.Dashboard,
            effectiveRailPanel(RailPanel.Dashboard, showCron = true, showGateway = true, showDashboard = true),
        )
    }

    @Test
    fun `chat leads the drawer, then whatever the servers support`() {
        // Reachability no longer depends on window size: the drawer carries the
        // same destinations everywhere, which is what fixed panels being
        // stranded in a two-pane window.
        assertEquals(
            listOf(Pane.Chat, Pane.Cron, Pane.Gateway, Pane.Dashboard),
            drawerDestinations(showCron = true, showGateway = true, showDashboard = true),
        )
    }

    @Test
    fun `sessions and activity are not destinations`() {
        // Sessions is the drawer's own body; Activity is a view of the open
        // transcript, already in the centre. Neither earns a row.
        val destinations = drawerDestinations(showCron = true, showGateway = true, showDashboard = true)

        assertEquals(false, destinations.contains(Pane.Sessions))
    }

    @Test
    fun `the drawer drops panels this gateway cannot serve`() {
        assertEquals(
            listOf(Pane.Chat, Pane.Gateway),
            drawerDestinations(showCron = false, showGateway = true, showDashboard = false),
        )
    }

    @Test
    fun `chat survives a gateway that supports nothing else`() {
        // With every capability absent the drawer must still lead somewhere.
        assertEquals(listOf(Pane.Chat), drawerDestinations(showCron = false, showGateway = false))
    }

    @Test
    fun `panels needing no capability are unaffected`() {
        assertEquals(
            RailPanel.Sessions,
            effectiveRailPanel(RailPanel.Sessions, showCron = false, showGateway = false),
        )
    }
}
