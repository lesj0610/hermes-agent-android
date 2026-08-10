package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.RailPanel
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEditorTest {

    @Test
    fun `options drop panels the gateway cannot serve`() {
        assertEquals(
            listOf(RailPanel.None, RailPanel.Activity, RailPanel.Cron),
            railPanelOptions(showGateway = false),
        )
    }

    @Test
    fun `schedule is offered whatever the capability report says`() {
        // The gateway reports jobs_admin=false unconditionally while serving
        // every /api/jobs route. Gating on it hid a working screen, so the
        // panel no longer consults it.
        assertEquals(true, railPanelOptions(showGateway = false).contains(RailPanel.Cron))
        assertEquals(true, drawerDestinations().contains(Pane.Cron))
    }

    @Test
    fun `hiding a rail is always offered`() {
        // None needs no server support, so it survives the strictest filter.
        assertEquals(RailPanel.None, railPanelOptions(false, false).first())
    }

    @Test
    fun `cycling wraps around`() {
        val options = railPanelOptions(showGateway = true)
        assertEquals(RailPanel.Activity, nextRailPanel(RailPanel.None, options))
        assertEquals(RailPanel.None, nextRailPanel(options.last(), options))
    }

    @Test
    fun `cycling recovers from a value no longer offered`() {
        // Chosen against a gateway with the panel, then reconnected to one
        // that cannot serve it.
        val options = railPanelOptions(showGateway = false)
        assertEquals(RailPanel.None, nextRailPanel(RailPanel.Dashboard, options))
    }

    @Test
    fun `an unsupported stored choice renders as hidden without being rewritten`() {
        assertEquals(
            RailPanel.None,
            effectiveRailPanel(RailPanel.Gateway, showGateway = false),
        )
        assertEquals(
            RailPanel.Gateway,
            effectiveRailPanel(RailPanel.Gateway, showGateway = true),
        )
    }

    @Test
    fun `the dashboard panel is offered only once a dashboard is configured`() {
        // Gated on configuration rather than a gateway capability: the dashboard
        // is a separate server, so the gateway cannot answer for it.
        assertEquals(
            false,
            railPanelOptions(showGateway = true, showDashboard = false)
                .contains(RailPanel.Dashboard),
        )
        assertEquals(
            true,
            railPanelOptions(showGateway = true, showDashboard = true)
                .contains(RailPanel.Dashboard),
        )
    }

    @Test
    fun `a dashboard rail collapses when the dashboard is removed`() {
        assertEquals(
            RailPanel.None,
            effectiveRailPanel(RailPanel.Dashboard, showGateway = true, showDashboard = false),
        )
        assertEquals(
            RailPanel.Dashboard,
            effectiveRailPanel(RailPanel.Dashboard, showGateway = true, showDashboard = true),
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
            drawerDestinations(showProjects = true),
        )
    }

    @Test
    fun `the session list is neither a destination nor a rail`() {
        // It is the drawer's own body. A row leading to a screen showing the
        // same list would be a detour through what is already open.
        val options = railPanelOptions(showGateway = true, showDashboard = true)

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
        // Projects need a dashboard, which the gateway cannot answer for, so
        // that row is the only one a bare setup loses.
        assertEquals(
            listOf(Pane.Chat, Pane.Artifacts, Pane.Cron),
            drawerDestinations(showProjects = false),
        )
    }

    @Test
    fun `chat survives a gateway that supports nothing else`() {
        assertEquals(Pane.Chat, drawerDestinations().first())
    }

    @Test
    fun `panels needing no capability are unaffected`() {
        assertEquals(
            RailPanel.Activity,
            effectiveRailPanel(RailPanel.Activity, showGateway = false),
        )
    }
}
