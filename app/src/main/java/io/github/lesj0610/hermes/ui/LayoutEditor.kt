package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.RailPanel

/**
 * Which panels the rail may show on this gateway.
 *
 * Filtered by what the server actually supports, so the editor never offers a
 * choice that would produce an empty rail: picking "Schedule" against a gateway
 * with no job support would otherwise look like a bug rather than a limitation.
 *
 * [RailPanel.None] is always offered — hiding the rail needs no server support.
 */
fun railPanelOptions(
    showCron: Boolean,
    showGateway: Boolean,
    showDashboard: Boolean = false,
): List<RailPanel> =
    buildList {
        add(RailPanel.None)
        add(RailPanel.Activity)
        if (showCron) add(RailPanel.Cron)
        if (showGateway) add(RailPanel.Gateway)
        // Gated on a configured dashboard rather than on a gateway capability:
        // it is a different server entirely, and offering it unconfigured would
        // hand the user an empty rail.
        if (showDashboard) add(RailPanel.Dashboard)
    }

/**
 * Destinations listed at the top of the navigation drawer, in order.
 *
 * Chat leads and is always present — it is where the app opens and returns to.
 * Schedule appears only where the server behind it exists.
 *
 * Sessions is absent on purpose: the drawer body holds the session list itself,
 * so a row leading to a separate screen showing the same list would be a detour
 * through something the drawer already displays. Activity is absent because it
 * has no pane of its own — it is a view of the open transcript, already in the
 * centre.
 *
 * Gateway and workspace are absent because they are settings, not destinations:
 * server state, toolsets, profiles and skills are things you configure and then
 * check on, and as top-level rows they put two read-only lists at the same level
 * as the conversation. They live under Settings, which is reachable from the
 * drawer's bottom row. The rail can still show either one beside the transcript.
 */
fun drawerDestinations(showCron: Boolean): List<Pane> = buildList {
    add(Pane.Chat)
    // Unconditional: artifacts are read out of the session histories every
    // gateway serves, not from a route one might lack.
    add(Pane.Artifacts)
    if (showCron) add(Pane.Cron)
}

/**
 * The next panel when the rail's cycle button is tapped.
 *
 * Wraps around, and tolerates a current value that is no longer offered — which
 * happens when a panel was chosen against one gateway and the app later
 * connects to one that lacks it. Falling back to the first option keeps the
 * control working instead of dead-ending on a value not in the list.
 */
fun nextRailPanel(current: RailPanel, options: List<RailPanel>): RailPanel {
    if (options.isEmpty()) return current
    val index = options.indexOf(current)
    if (index < 0) return options.first()
    return options[(index + 1) % options.size]
}

/**
 * What a rail should actually render, given what the server supports.
 *
 * A stored choice is not silently rewritten in settings — the gateway may come
 * back — but it is not drawn either, so the rail collapses instead of showing an
 * empty panel.
 */
fun effectiveRailPanel(
    stored: RailPanel,
    showCron: Boolean,
    showGateway: Boolean,
    showDashboard: Boolean = false,
): RailPanel =
    when (stored) {
        RailPanel.Cron -> if (showCron) stored else RailPanel.None
        RailPanel.Gateway -> if (showGateway) stored else RailPanel.None
        RailPanel.Dashboard -> if (showDashboard) stored else RailPanel.None
        else -> stored
    }
