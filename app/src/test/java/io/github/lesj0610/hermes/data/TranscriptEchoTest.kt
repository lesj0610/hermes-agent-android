package io.github.lesj0610.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that stopped a truncated copy of the answer appearing beneath the
 * answer, labelled as reasoning.
 */
class TranscriptEchoTest {

    private fun assistant(text: String) = TranscriptItem.AssistantText("a1", text, streaming = false)

    @Test
    fun `a truncated repeat of the reply is an echo`() {
        // The server sends the assistant's own text cut at 500 characters, so
        // the repeat arrives as a prefix and never as an equal string.
        val items = listOf(assistant("Seoul is 24°C and clear, with light wind from the south."))
        assertEquals(true, items.echoesReasoning("Seoul is 24°C and clear, with"))
    }

    @Test
    fun `whitespace differences do not hide an echo`() {
        // The relay strips reasoning tags first, which collapses line breaks the
        // streamed copy still carries.
        val items = listOf(assistant("Checking the forecast.\n\nSeoul is 24°C."))
        assertEquals(true, items.echoesReasoning("Checking the forecast. Seoul is 24°C."))
    }

    @Test
    fun `narration before a tool call is not an echo`() {
        // The step that calls a tool has its own text, and that is worth
        // showing — it is the only thinking this route exposes.
        val items = listOf(assistant("Seoul is 24°C and clear."))
        assertEquals(false, items.echoesReasoning("I will look up the current weather first."))
    }

    @Test
    fun `reasoning arriving before any reply is kept`() {
        assertEquals(false, emptyList<TranscriptItem>().echoesReasoning("Let me check."))
    }

    @Test
    fun `only the latest reply is compared`() {
        // An older message repeating itself is not the duplication this guards
        // against, and scanning everything would swallow a real restatement.
        val items = listOf(
            assistant("Seoul is 24°C."),
            TranscriptItem.ToolCall("t1", "weather", null, ToolState.Completed, 0.2),
            assistant("Busan is 26°C."),
        )
        assertEquals(false, items.echoesReasoning("Seoul is 24°C."))
        assertEquals(true, items.echoesReasoning("Busan is 26°C."))
    }
}
