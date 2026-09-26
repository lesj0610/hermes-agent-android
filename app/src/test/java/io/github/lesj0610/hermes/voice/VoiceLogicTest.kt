package io.github.lesj0610.hermes.voice

import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFollowerTest {

    private fun state(vararg items: TranscriptItem, started: Long = 1, ended: Long = 0) =
        ChatState(items = items.toList(), startedTurn = started, endedTurn = ended)

    private val user = TranscriptItem.UserText("u1", "질문")

    @Test
    fun `new text is handed out once, then the end`() {
        val follower = ReplyFollower(1)
        assertEquals(
            ReplyFollower.Update("안녕", false),
            follower.read(state(user, TranscriptItem.AssistantText("a1", "안녕", streaming = true))),
        )
        assertEquals(
            ReplyFollower.Update("하세요.", false),
            follower.read(state(user, TranscriptItem.AssistantText("a1", "안녕하세요.", streaming = true))),
        )
        val withTool = state(
            user,
            TranscriptItem.AssistantText("a1", "안녕하세요.", streaming = false),
            TranscriptItem.ToolCall("t1", "terminal", null, ToolState.Completed),
            TranscriptItem.AssistantText("a2", "결과입니다.", streaming = true),
        )
        // A second block starts on its own line.
        assertEquals(ReplyFollower.Update("\n결과입니다.", false), follower.read(withTool))
        assertEquals(ReplyFollower.Update("", true), follower.read(withTool.copy(endedTurn = 1)))
        assertTrue(follower.ended)
    }

    @Test
    fun `a turn not yet started waits, a superseded one ends`() {
        assertEquals(ReplyFollower.Update("", false), ReplyFollower(2).read(state(user, started = 1)))
        assertEquals(ReplyFollower.Update("", true), ReplyFollower(1).read(state(user, started = 2)))
    }

    @Test
    fun `an earlier turn's reply is never read`() {
        val follower = ReplyFollower(2)
        val update = follower.read(
            state(
                TranscriptItem.UserText("u0", "이전"),
                TranscriptItem.AssistantText("a0", "이전 답", streaming = false),
                TranscriptItem.UserText("u1", "지금"),
                TranscriptItem.AssistantText("a1", "새 답", streaming = true),
                started = 2, ended = 1,
            ),
        )
        assertEquals(ReplyFollower.Update("새 답", false), update)
    }

    @Test
    fun `media lines lifted out on completion are not read twice`() {
        val follower = ReplyFollower(1)
        follower.read(state(user, TranscriptItem.AssistantText("a1", "그림입니다.\nMEDIA:/a.png", streaming = true)))
        val done = follower.read(
            state(user, TranscriptItem.AssistantText("a1", "그림입니다.", streaming = false), ended = 1),
        )
        assertEquals(ReplyFollower.Update("", true), done)
    }

    @Test
    fun `a session switch ends the turn`() {
        val follower = ReplyFollower(1)
        follower.read(state(user))
        assertEquals(ReplyFollower.Update("", true), follower.read(ChatState(startedTurn = 1, endedTurn = 1)))
    }
}

class DictationDraftTest {

    @Test
    fun `partials replace the dictated span and the final is kept`() {
        val draft = DictationDraft()
        draft.begin("메모:")
        assertEquals("메모: 오늘", draft.partial("메모:", "오늘"))
        assertEquals("메모: 오늘 회의", draft.partial("메모: 오늘", "오늘 회의"))
        assertEquals("메모: 오늘 회의는 세 시", draft.commit("메모: 오늘 회의", "오늘 회의는 세 시"))
        assertFalse(draft.active)
    }

    @Test
    fun `a user edit ends dictation without overwriting or duplicating`() {
        val draft = DictationDraft()
        draft.begin("")
        assertEquals("안녕", draft.partial("", "안녕"))
        assertTrue(draft.isUserEdit("안녕 반가"))
        assertNull(draft.partial("안녕 반가", "안녕하세요"))
        assertFalse(draft.active)
        assertNull(draft.commit("안녕 반가", "안녕하세요"))
    }

    @Test
    fun `a failed recognition takes its provisional words back`() {
        val draft = DictationDraft()
        draft.begin("abc")
        draft.partial("abc", "de")
        assertEquals("abc", draft.abandon("abc de"))

        draft.begin("abc")
        draft.partial("abc", "de")
        assertNull(draft.abandon("abc de 수정"))
    }

    @Test
    fun `dictation is joined with one space, never glued or doubled`() {
        assertEquals("a b", DictationDraft.join("a", "b"))
        assertEquals("a b", DictationDraft.join("a ", "b"))
        assertEquals("a\nb", DictationDraft.join("a\n", "b"))
        assertEquals("b", DictationDraft.join("", "b"))
        assertEquals("a", DictationDraft.join("a", ""))
    }
}

class ConversationLoopTest {

    private class Port : ConversationLoop.Port {
        val calls = mutableListOf<String>()
        override fun listen(epoch: Long) { calls += "listen:$epoch" }
        override fun stopListening() { calls += "stopListening" }
        override fun send(text: String) { calls += "send:$text" }
        override fun speak(turn: Long, text: String) { calls += "speak:$turn:$text" }
        override fun finishSpeaking(turn: Long) { calls += "finish:$turn" }
        override fun cancelSpeaking() { calls += "cancelSpeaking" }
        override fun notice(fault: VoiceFault) { calls += "notice:$fault" }
    }

    private fun running(): Pair<Port, ConversationLoop> {
        val port = Port()
        val loop = ConversationLoop(port)
        loop.start()
        loop.onFinal(1, "안녕")
        loop.onSent(7)
        port.calls.clear()
        return port to loop
    }

    @Test
    fun `listens again only after the turn ended and the last sentence was spoken`() {
        val (port, loop) = running()
        loop.onReply(7, "안녕하세요. ", ended = false)
        loop.onReply(7, "", ended = true)
        assertEquals(listOf("speak:7:안녕하세요. ", "finish:7"), port.calls)
        assertEquals(ConversationLoop.Phase.Draining, loop.phase)

        loop.onSpoken(7)
        assertEquals("listen:2", port.calls.last())
    }

    @Test
    fun `a duplicate end is not a second finish`() {
        val (port, loop) = running()
        loop.onReply(7, "", ended = true)
        loop.onReply(7, "", ended = true)
        assertEquals(listOf("finish:7"), port.calls)
    }

    @Test
    fun `nothing stale starts speech or listening after stop`() {
        val (port, loop) = running()
        loop.stop()
        port.calls.clear()
        loop.onReply(7, "늦은 말", ended = false)
        loop.onReply(7, "", ended = true)
        loop.onSpoken(7)
        loop.onFinal(1, "늦은 인식")
        loop.onSilence(1)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `a reply for another turn is ignored`() {
        val (port, loop) = running()
        loop.onReply(6, "이전 턴", ended = true)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `silence is retried a limited number of times, then the conversation pauses`() {
        val port = Port()
        val loop = ConversationLoop(port, maxSilentRetries = 2)
        loop.start()
        loop.onSilence(1)
        loop.onSilence(2)
        loop.onSilence(3)
        assertEquals(listOf("listen:1", "listen:2", "listen:3", "stopListening", "cancelSpeaking", "notice:NoSpeech"), port.calls)
        assertFalse(loop.active)
    }

    @Test
    fun `a stale recognizer's result is dropped`() {
        val port = Port()
        val loop = ConversationLoop(port)
        loop.start()
        loop.onSilence(1)
        loop.onFinal(1, "옛 세션")
        assertEquals(listOf("listen:1", "listen:2"), port.calls)
    }

    @Test
    fun `stopping the reply mutes the rest and listens when the turn ends`() {
        val (port, loop) = running()
        loop.onReply(7, "첫 문장. ", ended = false)
        loop.interruptReply()
        loop.onReply(7, "나머지. ", ended = false)
        loop.onReply(7, "", ended = true)
        assertEquals(listOf("speak:7:첫 문장. ", "cancelSpeaking", "listen:2"), port.calls)
    }

    @Test
    fun `a refused send or a speech failure stops with the cause`() {
        val port = Port()
        val loop = ConversationLoop(port)
        loop.start()
        loop.onFinal(1, "안녕")
        loop.onSendFailed()
        assertEquals("notice:TurnRefused", port.calls.last())
        assertFalse(loop.active)

        val (port2, loop2) = running()
        loop2.onSpeechFailed(7, VoiceFault.SpeechEngine)
        assertEquals("notice:SpeechEngine", port2.calls.last())
        assertFalse(loop2.active)
    }
}

class VoiceContextTest {

    @Test
    fun `the exchange comes newest last, after the delivery note`() {
        val context = voiceContext(
            listOf(
                TranscriptItem.UserText("u1", "날씨 어때?"),
                TranscriptItem.AssistantText("a1", "맑습니다.\n기온은 20도입니다.", streaming = false),
                TranscriptItem.ToolCall("t1", "terminal", null, ToolState.Completed),
            ),
        )
        val lines = context.lines()
        assertEquals(VOICE_DELIVERY_NOTE, lines.first())
        assertEquals(listOf("User: 날씨 어때?", "Assistant: 맑습니다. 기온은 20도입니다."), lines.drop(1))
    }

    @Test
    fun `long lines and long histories are capped`() {
        val items = (1..20).map { TranscriptItem.UserText("u$it", "말".repeat(500)) }
        val lines = voiceContext(items, maxLines = 4, maxLineChars = 50).lines()
        assertEquals(5, lines.size)
        assertTrue(lines.drop(1).all { it.length <= 51 })
    }
}
