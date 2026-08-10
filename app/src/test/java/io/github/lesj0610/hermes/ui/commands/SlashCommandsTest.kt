package io.github.lesj0610.hermes.ui.commands

import io.github.lesj0610.hermes.net.CommandCatalog
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The palette's tiering, which is the part that must not drift: a command
 * promised as runnable and then doing nothing is the failure this design set
 * out to avoid.
 */
class SlashCommandsTest {

    private val catalog = CommandCatalog(
        pairs = listOf(
            listOf("/new", "Start a new session"),
            listOf("/compress", "Compact the conversation"),
            listOf("/tools", "List tools"),
            listOf("/arxiv", "Fetch a paper"),
            listOf("/personality", "Switch personality"),
        ),
        skills = mapOf("/arxiv" to JsonNull),
    )

    @Test
    fun `each command carries what this client can do with it`() {
        val byName = buildCommands(catalog).associateBy { it.name }
        assertEquals(CommandAbility.Navigate, byName.getValue("/new").ability)
        assertEquals(CommandAbility.Mutate, byName.getValue("/compress").ability)
        assertEquals(CommandAbility.Query, byName.getValue("/tools").ability)
        // Named by the catalogue as a skill, so the gateway will run it.
        assertEquals(CommandAbility.Dispatch, byName.getValue("/arxiv").ability)
        // Drawn by the desktop's own UI; the gateway has nothing to execute.
        assertEquals(CommandAbility.LocalToDesktop, byName.getValue("/personality").ability)
    }

    @Test
    fun `an unknown command is listed, not dropped`() {
        // The registry grows upstream. A new command should read as "not here
        // yet" rather than silently missing, which is how Schedule disappeared.
        val built = buildCommands(CommandCatalog(pairs = listOf(listOf("/brandnew", "x"))))
        assertEquals(1, built.size)
        assertEquals(CommandAbility.LocalToDesktop, built.single().ability)
    }

    @Test
    fun `runnable commands sort above the rest`() {
        val sorted = filterCommands(buildCommands(catalog), "/")
        assertEquals(CommandAbility.LocalToDesktop, sorted.last().ability)
    }

    @Test
    fun `typing filters by name and by description`() {
        val all = buildCommands(catalog)
        assertEquals(listOf("/tools"), filterCommands(all, "/too").map { it.name })
        assertEquals(listOf("/new"), filterCommands(all, "/Start a new").map { it.name })
    }
}
