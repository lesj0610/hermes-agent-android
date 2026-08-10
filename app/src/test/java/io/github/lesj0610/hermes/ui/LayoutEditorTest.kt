package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.RailPanel
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEditorTest {

    @Test
    fun `options drop panels the gateway cannot serve`() {
        assertEquals(
            listOf(RailPanel.None, RailPanel.Activity),
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
        assertEquals(RailPanel.Activity, nextRailPanel(RailPanel.None, options))
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
        //
        // Gateway and workspace are not here: they are settings — read-only
        // server state and configuration you check on — and live under the
        // settings row rather than beside the conversation.
        assertEquals(
            listOf(Pane.Chat, Pane.Projects, Pane.Artifacts, Pane.Cron),
            drawerDestinations(showCron = true, showProjects = true),
        )
    }

    @Test
    fun `the session list is neither a destination nor a rail`() {
        // It is the drawer's own body. A row leading to a screen showing the
        // same list would be a detour through what is already open.
        val options = railPanelOptions(showCron = true, showGateway = true, showDashboard = true)

        assertEquals(false, options.any { it.name == "Sessions" })
    }

    @Test
    fun `docking is judged by width, not by pane count`() {
        // Tablet mode forced on the unfolded Fold 5 asks for three columns from
        // 690dp. The drawer still fits; what does not is the rail as well.
        assertEquals(true, canDockDrawer(widthDp = 690f, drawerWidthDp = 300f))
        assertEquals(false, canDockDrawer(widthDp = 690f, drawerWidthDp = 420f))
    }

    @Test
    fun `the rail yields to the docked drawer rather than the transcript`() {
        // 690 − 300 drawer − 300 rail leaves 90dp of conversation, which is not
        // a conversation. Undocked, the same rail fits.
        assertEquals(false, railFits(widthDp = 690f, occupiedDp = 300f, railWidthDp = 300f))
        assertEquals(true, railFits(widthDp = 690f, occupiedDp = 0f, railWidthDp = 300f))
        assertEquals(true, railFits(widthDp = 1100f, occupiedDp = 300f, railWidthDp = 300f))
    }

    @Test
    fun `the drawer drops panels this gateway cannot serve`() {
        // Projects need a dashboard, which the gateway cannot answer for.
        assertEquals(listOf(Pane.Chat, Pane.Artifacts), drawerDestinations(showCron = false))
    }

    @Test
    fun `chat survives a gateway that supports nothing else`() {
        // With every capability absent the drawer must still lead somewhere.
        assertEquals(listOf(Pane.Chat, Pane.Artifacts), drawerDestinations(showCron = false))
    }

    @Test
    fun `panels needing no capability are unaffected`() {
        assertEquals(
            RailPanel.Activity,
            effectiveRailPanel(RailPanel.Activity, showCron = false, showGateway = false),
        )
    }
}
