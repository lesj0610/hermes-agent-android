package io.github.lesj0610.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading back a user turn the gateway stored.
 *
 * An attachment is persisted as an `@image:<path>` directive on its own line,
 * caption first and directives last. Rendering that as prose put a raw
 * filesystem path in the bubble where the picture had been.
 */
class StoredUserTurnTest {

    @Test
    fun `a caption and its attachment are separated`() {
        val turn = parseStoredUserTurn(
            "comfyui 사용해서 한국 80년대 복고풍 사진으로 바꿔줘\n" +
                "@image:/home/u/.hermes/images/upload_20260924_111948_1.jpg",
        )
        assertEquals("comfyui 사용해서 한국 80년대 복고풍 사진으로 바꿔줘", turn.text)
        assertEquals(
            listOf("/home/u/.hermes/images/upload_20260924_111948_1.jpg"),
            turn.imagePaths,
        )
    }

    @Test
    fun `several attachments all come back`() {
        val turn = parseStoredUserTurn("둘 비교해줘\n@image:/a/one.jpg\n@image:/a/two.png")
        assertEquals("둘 비교해줘", turn.text)
        assertEquals(listOf("/a/one.jpg", "/a/two.png"), turn.imagePaths)
    }

    @Test
    fun `a picture with no caption leaves empty text`() {
        val turn = parseStoredUserTurn("@image:/a/one.jpg")
        assertEquals("", turn.text)
        assertEquals(listOf("/a/one.jpg"), turn.imagePaths)
    }

    @Test
    fun `a quoted path is unwrapped`() {
        // The writer wraps a path containing spaces in whichever quote the path
        // does not itself contain.
        assertEquals(
            listOf("/a/two words.jpg"),
            parseStoredUserTurn("설명\n@image:\"/a/two words.jpg\"").imagePaths,
        )
        assertEquals(
            listOf("/a/it's here.jpg"),
            parseStoredUserTurn("설명\n@image:`/a/it's here.jpg`").imagePaths,
        )
    }

    @Test
    fun `an ordinary message is untouched`() {
        val text = "이메일 주소는 a@b.com 이고 가격은 @image 아닙니다"
        val turn = parseStoredUserTurn(text)
        assertEquals(text, turn.text)
        assertTrue(turn.imagePaths.isEmpty())
    }

    @Test
    fun `a directive inside a sentence is not a directive`() {
        // Only a line that starts with it counts; the writer always puts each
        // on its own line.
        val text = "이 파일 @image:/a/one.jpg 를 보세요"
        val turn = parseStoredUserTurn(text)
        assertEquals(text, turn.text)
        assertTrue(turn.imagePaths.isEmpty())
    }

    @Test
    fun `an empty message stays empty`() {
        val turn = parseStoredUserTurn("")
        assertEquals("", turn.text)
        assertTrue(turn.imagePaths.isEmpty())
    }
}
