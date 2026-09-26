package io.github.lesj0610.hermes.data

import io.github.lesj0610.hermes.net.StoredMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A reopened session, read back as the turn that produced it.
 *
 * The rows are shaped the way `/api/sessions/{id}/messages` returns them: the
 * thought on the assistant row, the calls beside it with their arguments as a
 * JSON string, and each result pointing back with `tool_call_id`.
 */
class StoredTranscriptTest {

    private var next = 0
    private val key: (String) -> String = { prefix -> "$prefix-${next++}" }

    private fun user(text: String) = StoredMessage(role = "user", content = JsonPrimitive(text))

    private fun assistant(text: String, reasoning: String? = null, toolCalls: String? = null) = StoredMessage(
        role = "assistant",
        content = JsonPrimitive(text),
        reasoning = reasoning,
        toolCalls = toolCalls?.let(Json::parseToJsonElement),
    )

    private fun tool(result: String, callId: String, name: String? = null) = StoredMessage(
        role = "tool",
        content = JsonPrimitive(result),
        toolName = name,
        toolCallId = callId,
    )

    private val readCall = """
        [{"id": "call_1", "call_id": "call_1", "type": "function",
          "function": {"name": "read_file", "arguments": "{\"path\": \"/repo/app/Theme.kt\"}"}}]
    """.trimIndent()

    @Test
    fun `thinking, calls and results come back in the order they happened`() {
        val items = storedToTranscript(
            listOf(
                user("테마 파일 봐줘"),
                assistant("", reasoning = "파일부터 읽어야 한다.", toolCalls = readCall),
                tool("""{"content": "object Theme"}""", callId = "call_1"),
                assistant("Theme.kt 확인했습니다.", reasoning = "내용을 요약한다."),
            ),
            key,
        )

        assertEquals(
            listOf(
                TranscriptItem.UserText::class,
                TranscriptItem.Reasoning::class,
                TranscriptItem.ToolCall::class,
                TranscriptItem.Reasoning::class,
                TranscriptItem.AssistantText::class,
            ),
            items.map { it::class },
        )
        val call = items[2] as TranscriptItem.ToolCall
        // The name and the target come from the call, not the result row.
        assertEquals("read_file", call.tool)
        assertEquals("Theme.kt", call.target)
        assertEquals(ToolState.Completed, call.state)
    }

    @Test
    fun `a stored thought was never timed and is never pending`() {
        val thought = storedToTranscript(listOf(assistant("답", reasoning = "생각")), key)
            .first() as TranscriptItem.Reasoning
        assertFalse(thought.pending)
        assertNull(thought.durationSeconds)
    }

    @Test
    fun `calls stored as JSON text resolve too, under either id`() {
        // Older rows kept the array as its JSON text, and a result may point
        // at `call_id` rather than `id`.
        val calls = readCall.replace("\"id\": \"call_1\", \"call_id\": \"call_1\"", "\"id\": \"row_9\", \"call_id\": \"fc_9\"")
        val items = storedToTranscript(
            listOf(
                StoredMessage(role = "assistant", content = JsonPrimitive(""), toolCalls = JsonPrimitive(calls)),
                tool("ok", callId = "fc_9"),
            ),
            key,
        )
        assertEquals("Theme.kt", (items.single() as TranscriptItem.ToolCall).target)
    }

    @Test
    fun `arguments stored as an object are read as well`() {
        val items = storedToTranscript(
            listOf(
                assistant(
                    "",
                    toolCalls = """[{"id": "c1", "function": {"name": "terminal", "arguments": {"command": "npm test"}}}]""",
                ),
                tool("passed", callId = "c1"),
            ),
            key,
        )
        val call = items.single() as TranscriptItem.ToolCall
        assertEquals("terminal", call.tool)
        assertEquals("npm test", call.target)
    }

    @Test
    fun `a failed result reads as failed`() {
        val items = storedToTranscript(
            listOf(assistant("", toolCalls = readCall), tool("""{"error": "No such file"}""", callId = "call_1")),
            key,
        )
        assertEquals(ToolState.Failed, (items.single() as TranscriptItem.ToolCall).state)
    }

    @Test
    fun `a result with no matching call still shows, by its own name`() {
        val call = storedToTranscript(listOf(tool("done", callId = "missing", name = "memory")), key)
            .single() as TranscriptItem.ToolCall
        assertEquals("memory", call.tool)
        assertNull(call.target)
    }

    @Test
    fun `a blank thought adds no row`() {
        val items = storedToTranscript(listOf(assistant("답", reasoning = "  \n ")), key)
        assertEquals(listOf(TranscriptItem.AssistantText::class), items.map { it::class })
    }
}
