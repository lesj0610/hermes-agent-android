package io.github.lesj0610.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a whole-thought reasoning block lands.
 *
 * The HTTP route sends `reasoning.available` once, after the answer has already
 * streamed. Appending it there put the thinking underneath its own conclusion —
 * a bug this app shipped once before. It belongs above the answer, which is the
 * order the socket route produces on its own and the order the desktop shows.
 */
class TranscriptReasoningTest {

    private fun reasoning(text: String = "17 × 23 을 나누어 계산합니다.") =
        TranscriptItem.Reasoning("r-1", text)

    @Test
    fun `reasoning is placed above the answer, not after it`() {
        val items = listOf(
            TranscriptItem.UserText("u-1", "17 곱하기 23은?"),
            TranscriptItem.AssistantText("a-1", "391 입니다.", streaming = false),
        )
        val out = items.withReasoning(reasoning())
        assertEquals(
            listOf("u-1", "r-1", "a-1"),
            out.map { it.key },
        )
    }

    @Test
    fun `it goes above the last answer when several turns are present`() {
        val items = listOf(
            TranscriptItem.AssistantText("a-1", "먼저 확인하겠습니다.", streaming = false),
            TranscriptItem.ToolCall("t-1", "bash", null, ToolState.Completed, 0.2),
            TranscriptItem.AssistantText("a-2", "391 입니다.", streaming = false),
        )
        assertEquals(
            listOf("a-1", "t-1", "r-1", "a-2"),
            items.withReasoning(reasoning()).map { it.key },
        )
    }

    @Test
    fun `reasoning that already streamed is never doubled`() {
        // On the socket the thought arrives as deltas and this event follows as
        // an echo. Drawing both says the same thing twice.
        val items = listOf(
            TranscriptItem.Reasoning("r-0", "이미 흘러온 생각"),
            TranscriptItem.AssistantText("a-1", "391 입니다.", streaming = false),
        )
        val out = items.withReasoning(reasoning())
        assertSame(items, out)
        assertEquals(1, out.count { it is TranscriptItem.Reasoning })
    }

    @Test
    fun `with no answer yet it is appended`() {
        val items = listOf(TranscriptItem.UserText("u-1", "질문"))
        val out = items.withReasoning(reasoning())
        assertEquals(listOf("u-1", "r-1"), out.map { it.key })
    }

    @Test
    fun `an empty transcript takes the block alone`() {
        assertEquals(listOf("r-1"), emptyList<TranscriptItem>().withReasoning(reasoning()).map { it.key })
    }

    @Test
    fun `the thought's text survives intact`() {
        val text = "17 × 23\n= 17 × 20 + 17 × 3\n= 340 + 51"
        val out = listOf(TranscriptItem.AssistantText("a-1", "391", streaming = false))
            .withReasoning(TranscriptItem.Reasoning("r-1", text))
        val block = out.first { it is TranscriptItem.Reasoning } as TranscriptItem.Reasoning
        assertEquals(text, block.text)
        assertTrue(out.size == 2)
    }
}
