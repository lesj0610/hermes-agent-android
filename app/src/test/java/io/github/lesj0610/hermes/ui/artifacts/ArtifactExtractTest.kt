package io.github.lesj0610.hermes.ui.artifacts

import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.StoredMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The extraction rules, which are a port of the desktop client's
 * `artifact-utils.ts`. Both clients have to call the same things artifacts, so
 * these cases are the places the two could drift apart.
 */
class ArtifactExtractTest {

    private val session = SessionSummary(id = "s1", title = "Bench run")

    private fun assistant(text: String) =
        StoredMessage(role = "assistant", content = JsonPrimitive(text))

    private fun collect(vararg messages: StoredMessage) =
        collectArtifacts(session, messages.toList())

    @Test
    fun `a markdown image is an image`() {
        val found = collect(assistant("Here it is:\n![plot](/tmp/out/plot.png)"))
        assertEquals(1, found.size)
        assertEquals(ArtifactKind.Image, found.first().kind)
        assertEquals("plot.png", found.first().label)
    }

    @Test
    fun `an image is not listed twice as its own link`() {
        // The link pattern also matches an image's `[alt](url)` tail, one
        // character further in. Without the preceding-bang check every image
        // appeared twice.
        assertEquals(1, collect(assistant("![a](https://x.test/a.png)")).size)
    }

    @Test
    fun `a bare url is a link and a bare path is a file`() {
        val found = collect(assistant("See https://example.test/report and /var/log/run.log"))
        assertEquals(
            listOf(ArtifactKind.Link, ArtifactKind.File),
            found.map { it.kind },
        )
    }

    @Test
    fun `trailing sentence punctuation is not part of the path`() {
        val found = collect(assistant("Wrote /tmp/out/result.json."))
        assertEquals("/tmp/out/result.json", found.single().value)
    }

    @Test
    fun `a path inside a tool result is found through its key`() {
        val found = collectArtifacts(
            session,
            listOf(
                StoredMessage(
                    role = "tool",
                    content = JsonPrimitive("""{"output_path": "/home/u/report.pdf", "ok": true}"""),
                ),
            ),
        )
        assertEquals("/home/u/report.pdf", found.single().value)
        assertEquals(ArtifactKind.File, found.single().kind)
    }

    @Test
    fun `the user's own message is not a product of the run`() {
        // Otherwise every path the user typed is listed as something the agent
        // made, which is the opposite of what the screen is for.
        assertEquals(
            0,
            collect(StoredMessage(role = "user", content = JsonPrimitive("look at /tmp/a.png"))).size,
        )
    }

    @Test
    fun `the same value twice in one session is one artifact`() {
        assertEquals(
            1,
            collect(
                assistant("wrote /tmp/a.png"),
                assistant("still /tmp/a.png"),
            ).size,
        )
    }

    @Test
    fun `remote is what the phone can open on its own`() {
        val found = collect(assistant("https://x.test/a.png and /tmp/b.png"))
        assertEquals(listOf(true, false), found.map { it.remote })
    }

    @Test
    fun `prose is not an artifact`() {
        assertEquals(0, collect(assistant("The benchmark finished in 1.83 ms/iter.")).size)
    }
}
