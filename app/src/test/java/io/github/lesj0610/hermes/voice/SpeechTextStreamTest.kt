package io.github.lesj0610.hermes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A streamed reply, turned into speech a sentence at a time.
 *
 * The deltas below are split where a model's tokens split them — mid-link,
 * mid-fence, mid-tag — because that is exactly where a naive reader would say
 * half a URL or read a code block aloud.
 */
class SpeechTextStreamTest {

    @Test
    fun `a sentence is released as soon as it is finished`() {
        val stream = SpeechTextStream()
        assertEquals(listOf("안녕하세요."), stream.append("안녕하세요. 오늘", 0))
        assertEquals(listOf("오늘 날씨는 맑습니다."), stream.append(" 날씨는 맑습니다. ", 10))
        assertFalse(stream.hasPending)
    }

    @Test
    fun `a link split across deltas is read as its text, once it closes`() {
        val stream = SpeechTextStream()
        assertEquals(emptyList<String>(), stream.append("자세한 내용은 [문서", 0))
        assertEquals(emptyList<String>(), stream.append("](https://ex.com/a", 1))
        assertEquals(listOf("자세한 내용은 문서를 보세요."), stream.append(")를 보세요. 다음", 2))
        assertEquals(listOf("다음"), stream.finish())
    }

    @Test
    fun `a code block is skipped, with a note where it starts`() {
        val stream = SpeechTextStream(codeNote = "코드 생략.")
        assertEquals(listOf("설명입니다."), stream.append("설명입니다.\n```py", 0))
        assertEquals(listOf("코드 생략."), stream.append("thon\nprint(1)\n", 1))
        assertEquals(emptyList<String>(), stream.append("```\n끝입니다.", 2))
        assertEquals(listOf("끝입니다."), stream.finish())
    }

    @Test
    fun `thinking is never spoken, even when it opens and closes mid-line`() {
        val stream = SpeechTextStream()
        assertEquals(emptyList<String>(), stream.append("<think>생각", 0))
        assertEquals(listOf("답변입니다."), stream.append(" 중</think>답변입니다. 이어서", 1))
        assertEquals(listOf("이어서"), stream.finish())
    }

    @Test
    fun `a tag split across deltas is held rather than read`() {
        val stream = SpeechTextStream()
        assertEquals(listOf("시작합니다."), stream.append("시작합니다. <thi", 0))
        assertEquals(listOf("보이는 말."), stream.append("nk>숨은 생각</think>보이는 말. ", 1))
    }

    @Test
    fun `MEDIA directives are not read, even arriving a few letters at a time`() {
        val stream = SpeechTextStream()
        assertEquals(listOf("완료했습니다."), stream.append("완료했습니다.\nMED", 0))
        assertEquals(emptyList<String>(), stream.append("IA:/tmp/a.png", 1))
        assertEquals(emptyList<String>(), stream.finish())
    }

    @Test
    fun `an unbroken run is cut at the cap instead of waiting forever`() {
        val stream = SpeechTextStream(maxChars = 50)
        val released = stream.append("가".repeat(120), 0)
        assertEquals(listOf("가".repeat(50), "가".repeat(50)), released)
        assertEquals(listOf("가".repeat(20)), stream.finish())
    }

    @Test
    fun `a long sentence is cut at a space near the cap`() {
        val stream = SpeechTextStream(maxChars = 40)
        val words = (1..20).joinToString(" ") { "단어$it" }
        val released = stream.append(words, 0) + stream.finish()
        assertTrue(released.all { it.length <= 40 })
        assertEquals(words, released.joinToString(" "))
    }

    @Test
    fun `a pause releases a finished sentence, a longer one releases anything`() {
        val stream = SpeechTextStream(settleMillis = 700, stallMillis = 2_000)
        assertEquals(emptyList<String>(), stream.append("확인했습니다.", 0))
        assertEquals(emptyList<String>(), stream.onTick(500))
        assertEquals(listOf("확인했습니다."), stream.onTick(800))

        assertEquals(emptyList<String>(), stream.append("잠깐 확인해 볼게요", 1_000))
        assertEquals(emptyList<String>(), stream.onTick(1_800))
        assertEquals(listOf("잠깐 확인해 볼게요"), stream.onTick(3_100))
    }

    @Test
    fun `a decimal point is not a sentence end`() {
        val stream = SpeechTextStream()
        assertEquals(listOf("지연은 1.83초였습니다."), stream.append("지연은 1.83초였습니다. ", 0))
    }

    @Test
    fun `headings and list markers are not read`() {
        val stream = SpeechTextStream()
        assertEquals(
            listOf("결과", "첫째 항목", "둘째 항목"),
            stream.append("## 결과\n- 첫째 항목\n1. 둘째 항목\n", 0),
        )
    }

    @Test
    fun `emphasis and inline code lose their marks, identifiers keep theirs`() {
        val stream = SpeechTextStream()
        assertEquals(
            listOf("중요: npm test를 실행하세요."),
            stream.append("**중요**: `npm test`를 실행하세요. ", 0),
        )
        assertEquals(listOf("call my_function_name now."), stream.append("call my_function_name now. ", 1))
    }

    @Test
    fun `tables and addresses are skipped`() {
        val stream = SpeechTextStream()
        assertEquals(
            listOf("표 앞 문장.", "자세한 건 참고하세요."),
            stream.append("표 앞 문장.\n| a | b |\n|---|---|\n| 1 | 2 |\n자세한 건 https://example.com/x 참고하세요. ", 0),
        )
    }

    @Test
    fun `quotes after the full stop still end the sentence`() {
        val stream = SpeechTextStream()
        assertEquals(
            listOf("그는 \"좋아요.\"", "라고 말했다."),
            stream.append("그는 \"좋아요.\" 라고 말했다. ", 0),
        )
    }

    @Test
    fun `finishing an unclosed fence speaks nothing more and resets`() {
        val stream = SpeechTextStream()
        stream.append("```\ncode without end", 0)
        assertEquals(emptyList<String>(), stream.finish())
        assertEquals(listOf("다시 말합니다."), stream.append("다시 말합니다. ", 1))
    }

    @Test
    fun `balance is judged on what is open`() {
        assertTrue(SpeechTextStream.balanced("링크 [a](b) 끝."))
        assertFalse(SpeechTextStream.balanced("링크 [a](b"))
        assertFalse(SpeechTextStream.balanced("코드 `x"))
        assertFalse(SpeechTextStream.balanced("**강조"))
        assertFalse(SpeechTextStream.balanced("<think>생각"))
    }
}
