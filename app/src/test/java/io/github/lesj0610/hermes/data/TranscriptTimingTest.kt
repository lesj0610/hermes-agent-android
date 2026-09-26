package io.github.lesj0610.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a reasoning block gets its duration, and where a late thought lands once
 * a session holds thoughts from earlier turns.
 */
class TranscriptTimingTest {

    @Test
    fun `closing the tail stamps a live thought with its duration`() {
        val live = TranscriptItem.Reasoning("r-1", "생각 중", startedAtMillis = 10_000)
        assertTrue(live.pending)

        val closed = listOf(live).finishStreaming(now = 22_400).single() as TranscriptItem.Reasoning

        assertFalse(closed.pending)
        assertEquals(12L, closed.durationSeconds)
    }

    @Test
    fun `closing the tail ends a streaming reply`() {
        val reply = TranscriptItem.AssistantText("a-1", "391", streaming = true)
        val closed = listOf(reply).finishStreaming().single() as TranscriptItem.AssistantText
        assertFalse(closed.streaming)
    }

    @Test
    fun `a settled tail is left as it is`() {
        val items = listOf(
            TranscriptItem.Reasoning("r-1", "끝난 생각", startedAtMillis = 1_000, completedAtMillis = 3_000),
        )
        assertSame(items, items.finishStreaming(now = 99_000))
    }

    @Test
    fun `a thought shorter than a second measures zero`() {
        // The label turns zero into "briefly"; the measurement stays honest.
        val quick = TranscriptItem.Reasoning("r-1", "짧게", startedAtMillis = 1_000, completedAtMillis = 1_900)
        assertEquals(0L, quick.durationSeconds)
    }

    @Test
    fun `an earlier turn's thought does not suppress this turn's`() {
        // A reopened session carries reasoning from its history. Checked across
        // the whole transcript, the first new thought after reopening vanished.
        val items = listOf(
            TranscriptItem.UserText("u-1", "첫 질문"),
            TranscriptItem.Reasoning("r-0", "지난 생각"),
            TranscriptItem.AssistantText("a-1", "첫 답", streaming = false),
            TranscriptItem.UserText("u-2", "두 번째 질문"),
            TranscriptItem.AssistantText("a-2", "두 번째 답", streaming = false),
        )
        val out = items.withReasoning(TranscriptItem.Reasoning("r-1", "새 생각"))
        assertEquals(listOf("u-1", "r-0", "a-1", "u-2", "r-1", "a-2"), out.map { it.key })
    }

    @Test
    fun `with no answer in this turn yet, it never slots above an earlier one`() {
        val items = listOf(
            TranscriptItem.UserText("u-1", "첫 질문"),
            TranscriptItem.AssistantText("a-1", "첫 답", streaming = false),
            TranscriptItem.UserText("u-2", "두 번째 질문"),
        )
        val out = items.withReasoning(TranscriptItem.Reasoning("r-1", "새 생각"))
        assertEquals(listOf("u-1", "a-1", "u-2", "r-1"), out.map { it.key })
    }
}
