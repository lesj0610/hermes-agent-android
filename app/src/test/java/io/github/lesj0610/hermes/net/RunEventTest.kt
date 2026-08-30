package io.github.lesj0610.hermes.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload shapes here are taken from the emit sites in
 * gateway/platforms/api_server.py, not invented.
 */
class RunEventTest {

    private fun event(raw: String): RunEvent? =
        parseRunEvent(Json.decodeFromString<JsonObject>(raw))

    @Test
    fun `message delta carries the text fragment`() {
        val parsed = event("""{"event":"message.delta","run_id":"r1","timestamp":1.5,"delta":"hel"}""")

        assertEquals(RunEvent.MessageDelta("r1", 1.5, "hel"), parsed)
    }

    @Test
    fun `approval request keeps the server choice list verbatim`() {
        val parsed = event(
            """{"event":"approval.request","run_id":"r1","command":"rm -rf x",
               "choices":["once","session","always","deny"],"smart_denied":false}""",
        ) as RunEvent.ApprovalRequest

        assertEquals(listOf("once", "session", "always", "deny"), parsed.choices)
        assertEquals("rm -rf x", parsed.command)
        assertEquals(false, parsed.smartDenied)
    }

    @Test
    fun `smart denied approval keeps the narrowed choice list`() {
        val parsed = event(
            """{"event":"approval.request","run_id":"r1","choices":["once","deny"],"smart_denied":true}""",
        ) as RunEvent.ApprovalRequest

        assertEquals(listOf("once", "deny"), parsed.choices)
        assertTrue(parsed.smartDenied)
    }

    @Test
    fun `tool completed reports duration and error`() {
        val parsed = event(
            """{"event":"tool.completed","tool":"bash","preview":"ok","duration":2.5,"error":null}""",
        ) as RunEvent.ToolCompleted

        assertEquals("bash", parsed.tool)
        assertEquals(2.5, parsed.duration!!, 0.001)
        assertEquals(false, parsed.failed)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun `a successful tool is not a failure carrying the word false`() {
        // This route sends the tool's own is_error flag as a JSON boolean.
        // Reading it as text yielded "false", which is not blank — so every
        // successful call was drawn as failed with "false" for a result.
        val parsed = event(
            """{"event":"tool.completed","tool":"weather","preview":"24C","error":false}""",
        ) as RunEvent.ToolCompleted

        assertEquals(false, parsed.failed)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun `a boolean true marks the call failed without inventing a message`() {
        val parsed = event(
            """{"event":"tool.completed","tool":"bash","error":true}""",
        ) as RunEvent.ToolCompleted

        assertEquals(true, parsed.failed)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun `a sentence is kept, since other surfaces send one`() {
        val parsed = event(
            """{"event":"tool.completed","tool":"bash","error":"exit status 1"}""",
        ) as RunEvent.ToolCompleted

        assertEquals(true, parsed.failed)
        assertEquals("exit status 1", parsed.errorMessage)
    }

    @Test
    fun `run completed parses native token names`() {
        // This route uses input_tokens/output_tokens, not the OpenAI-shaped
        // prompt_tokens/completion_tokens used on /v1/chat/completions.
        val parsed = event(
            """{"event":"run.completed","output":"done",
               "usage":{"input_tokens":120,"output_tokens":45,"total_tokens":165}}""",
        ) as RunEvent.Completed

        assertEquals("done", parsed.output)
        assertEquals(RunUsage(120, 45, 165), parsed.usage)
    }

    @Test
    fun `run completed without usage is still valid`() {
        val parsed = event("""{"event":"run.completed","output":"done"}""") as RunEvent.Completed

        assertNull(parsed.usage)
    }

    @Test
    fun `run failed carries the server message`() {
        val parsed = event("""{"event":"run.failed","error":"boom"}""") as RunEvent.Failed

        assertEquals("boom", parsed.error)
    }

    @Test
    fun `an unrecognised event does not blow up`() {
        // A newer gateway must degrade to "ignored", never to a crash.
        val parsed = event("""{"event":"run.paused","run_id":"r9"}""")

        assertEquals(RunEvent.Unknown("r9", null, "run.paused"), parsed)
    }

    @Test
    fun `a frame without an event key is skipped`() {
        assertNull(event("""{"run_id":"r1"}"""))
    }

    @Test
    fun `stored message content flattens both wire shapes`() {
        val plain = Json.decodeFromString<StoredMessage>(
            """{"role":"user","content":"hello"}""",
        )
        val multimodal = Json.decodeFromString<StoredMessage>(
            """{"role":"user","content":[{"type":"text","text":"a"},{"type":"image"},{"type":"text","text":"b"}]}""",
        )

        assertEquals("hello", plain.text)
        assertEquals("ab", multimodal.text)
    }
}
