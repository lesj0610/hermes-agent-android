package io.github.lesj0610.hermes.net

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parser is the one place where a wire-format mistake is silent: a dropped
 * frame just looks like the agent went quiet. These cases mirror what the
 * gateway actually writes (`_sse_frame` in api_server.py).
 */
class SseParserTest {

    private suspend fun parse(raw: String): List<SseFrame> =
        ByteReadChannel(raw).sseFrames().toList()

    @Test
    fun `splits consecutive frames on blank lines`() = runTest {
        val frames = parse(
            "data: {\"event\":\"message.delta\",\"delta\":\"a\"}\n\n" +
                "data: {\"event\":\"message.delta\",\"delta\":\"b\"}\n\n",
        )

        assertEquals(2, frames.size)
        assertEquals("{\"event\":\"message.delta\",\"delta\":\"a\"}", frames[0].data)
        assertEquals("{\"event\":\"message.delta\",\"delta\":\"b\"}", frames[1].data)
    }

    @Test
    fun `skips keepalive comments`() = runTest {
        // The server writes ": keepalive\n\n" on a timer between real frames.
        val frames = parse(": keepalive\n\ndata: {\"event\":\"run.completed\"}\n\n")

        assertEquals(1, frames.size)
        assertEquals("{\"event\":\"run.completed\"}", frames[0].data)
    }

    @Test
    fun `joins repeated data lines with newlines`() = runTest {
        val frames = parse("data: line one\ndata: line two\n\n")

        assertEquals(1, frames.size)
        assertEquals("line one\nline two", frames[0].data)
    }

    @Test
    fun `reads the named event form used by the chat completions stream`() = runTest {
        val frames = parse("event: hermes.tool.progress\ndata: {\"status\":\"running\"}\n\n")

        assertEquals("hermes.tool.progress", frames[0].name)
        assertEquals("{\"status\":\"running\"}", frames[0].data)
    }

    @Test
    fun `emits a trailing frame that was never terminated`() = runTest {
        // A tunnel drop mid-stream must not discard what already arrived.
        val frames = parse("data: {\"event\":\"message.delta\",\"delta\":\"tail\"}\n")

        assertEquals(1, frames.size)
        assertEquals("{\"event\":\"message.delta\",\"delta\":\"tail\"}", frames[0].data)
    }

    @Test
    fun `ignores an empty frame`() = runTest {
        assertEquals(emptyList<SseFrame>(), parse("\n\n\n"))
    }
}
