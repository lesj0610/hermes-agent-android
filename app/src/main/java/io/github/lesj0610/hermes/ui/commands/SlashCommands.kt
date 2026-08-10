package io.github.lesj0610.hermes.ui.commands

import io.github.lesj0610.hermes.net.CommandCatalog

/**
 * The slash command set, and what this client can actually do with each one.
 *
 * The list comes from the gateway's own registry (`commands.catalog`), so it is
 * whatever the desktop shows — 95 entries at the time of writing. What differs
 * is what happens on tap, and that is decided here.
 *
 * Every command is listed even when it cannot run. Hiding the ones that do not
 * work would leave someone hunting for a command they know exists, which is
 * exactly how the Schedule screen went missing for a release without anyone
 * being able to see why. A dimmed row with a reason is the honest form.
 */

enum class CommandAbility {
    /** Does something this app already has a screen or control for. */
    Navigate,

    /** Reads server state over a session-less RPC and shows the result. */
    Query,

    /** Rewrites the stored session, which the next turn will read. */
    Mutate,

    /** Real on the desktop, unreachable from here. The row says why. */
    Unavailable,
}

/** What tapping a runnable command does. */
enum class CommandAction {
    NewSession, OpenSessions, OpenProjects, OpenArtifacts, OpenSchedule, OpenSettings,
    PickModel, PickReasoning, Search, Compress,
}

data class SlashCommand(
    val name: String,
    val description: String,
    val ability: CommandAbility,
    val action: CommandAction? = null,
    /** RPC to call for [CommandAbility.Query]. */
    val method: String? = null,
)

/**
 * Commands wired to something in this app.
 *
 * Deliberately short. Each entry is one this client genuinely performs — not a
 * near-equivalent — because a command that quietly does something adjacent is
 * worse than one that says it cannot run.
 */
private val NAVIGATE: Map<String, CommandAction> = mapOf(
    "/new" to CommandAction.NewSession,
    "/sessions" to CommandAction.OpenSessions,
    "/resume" to CommandAction.OpenSessions,
    "/search" to CommandAction.Search,
    "/projects" to CommandAction.OpenProjects,
    "/project" to CommandAction.OpenProjects,
    "/artifacts" to CommandAction.OpenArtifacts,
    "/cron" to CommandAction.OpenSchedule,
    "/jobs" to CommandAction.OpenSchedule,
    "/settings" to CommandAction.OpenSettings,
    "/config" to CommandAction.OpenSettings,
    "/model" to CommandAction.PickModel,
    "/reasoning" to CommandAction.PickReasoning,
)

/**
 * Commands answered by a read-only RPC that needs no session.
 *
 * Verified session-less on the gateway: these handlers either take no session
 * at all or degrade without one, unlike the rest of that surface.
 */
private val QUERY: Map<String, String> = mapOf(
    "/tools" to "tools.list",
    "/toolsets" to "toolsets.list",
    "/plugins" to "plugins.list",
    "/agents" to "agents.list",
    "/insights" to "insights.get",
    "/commands" to "commands.catalog",
)

/** Rewrites stored history, so the effect outlives the call. */
private val MUTATE: Map<String, CommandAction> = mapOf(
    "/compress" to CommandAction.Compress,
    "/compact" to CommandAction.Compress,
)

/**
 * Turns the server's catalogue into rows, tagged with what this client can do.
 *
 * Unknown commands are kept and marked unavailable rather than dropped: the
 * registry grows, and a new command should appear as "not here yet" rather
 * than not appear.
 */
fun buildCommands(catalog: CommandCatalog): List<SlashCommand> =
    catalog.pairs.mapNotNull { pair ->
        val name = pair.getOrNull(0)?.trim().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        val description = pair.getOrNull(1).orEmpty()
        val key = name.lowercase()
        when {
            MUTATE.containsKey(key) -> SlashCommand(
                name, description, CommandAbility.Mutate, action = MUTATE[key],
            )
            NAVIGATE.containsKey(key) -> SlashCommand(
                name, description, CommandAbility.Navigate, action = NAVIGATE[key],
            )
            QUERY.containsKey(key) -> SlashCommand(
                name, description, CommandAbility.Query, method = QUERY[key],
            )
            else -> SlashCommand(name, description, CommandAbility.Unavailable)
        }
    }

/**
 * Filters by what has been typed after the slash.
 *
 * Runnable commands sort first. With 95 entries and most of them unavailable,
 * a plain alphabetical list would bury the handful that work behind rows that
 * cannot be tapped.
 */
fun filterCommands(commands: List<SlashCommand>, query: String): List<SlashCommand> {
    val term = query.removePrefix("/").trim().lowercase()
    val matched = if (term.isEmpty()) {
        commands
    } else {
        commands.filter {
            it.name.removePrefix("/").lowercase().startsWith(term) ||
                it.name.lowercase().contains(term) ||
                it.description.lowercase().contains(term)
        }
    }
    return matched.sortedWith(
        compareBy(
            { it.ability == CommandAbility.Unavailable },
            { it.name },
        ),
    )
}
