package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.PendingApproval
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.sessions.SessionsPane
import io.github.lesj0610.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real screens to PNG on the JVM — no device, no emulator.
 *
 * This exists so a layout change can be looked at in seconds instead of going
 * through build, install and a phone. Output lands in
 * `app/build/outputs/roborazzi/`, which opens directly in the editor.
 *
 * The SDK is pinned below the project's compileSdk on purpose: Robolectric only
 * needs a runtime it ships support for, and that is independent of what the app
 * compiles against.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        compose.setContent {
            HermesTheme {
                // Surface, not a bare Box: in the app the Scaffold paints the
                // background, and without it these renders came out as light
                // text on white — a harness artefact that would have read as a
                // contrast bug in the app.
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(width.dp, height.dp),
                ) {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val transcript = listOf(
        TranscriptItem.UserText("u1", "디코드 커널 벤치 한 번 돌려주고 이전 결과랑 비교해줘"),
        TranscriptItem.AssistantText("a1", "벤치 스크립트부터 확인하겠습니다.", streaming = false),
        TranscriptItem.ToolCall("t1", "read_file", "bench/decode_fp4.py · 214줄", ToolState.Completed, 0.4),
        TranscriptItem.ToolCall(
            "t2", "bash",
            "python bench/decode_fp4.py --seq 4096\n[24/50] 1.83 ms/iter",
            ToolState.Running,
        ),
        TranscriptItem.AssistantText("a2", "중간 결과는 1.83 ms/iter", streaming = true),
    )

    @Test
    fun chatPhone() {
        capture("chat-phone", 411, 891) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = transcript,
                    phase = RunPhase.Running("r1"),
                ),
                onSend = {}, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun chatEmpty() {
        capture("chat-empty", 411, 891) {
            ChatPane(
                state = ChatState(),
                onSend = {}, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun sessionsList() {
        capture("sessions-phone", 411, 891) {
            SessionsPane(
                sessions = listOf(
                    SessionSummary(id = "1", title = "FP4 dequant 벤치", model = "opus-5",
                        preview = "중간 결과는 1.83 ms/iter…"),
                    SessionSummary(id = "2", title = "게이트웨이 로그 점검", model = "opus-5",
                        preview = "승인 대기 · bash", endedAt = "2026-08-09"),
                    SessionSummary(id = "3", title = "Termux 설치 경로 확인",
                        toolCallCount = 5, endedAt = "2026-08-08", endReason = "completed"),
                ),
                selectedId = "1",
                onSelect = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** Tablet width, so rail proportions and the wider transcript can be judged. */
    @Test
    fun chatTablet() {
        capture("chat-tablet", 900, 800) {
            ChatPane(
                state = ChatState(sessionId = "s1", items = transcript, phase = RunPhase.Running("r1")),
                onSend = {}, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun approvalPending() {
        capture("chat-approval", 411, 891) {
            ChatPane(
                state = ChatState(
                    items = transcript.dropLast(1),
                    phase = RunPhase.AwaitingApproval(
                        "r1",
                        PendingApproval(
                            runId = "r1",
                            command = "rm -rf bench/results.old",
                            choices = listOf("once", "session", "always", "deny"),
                            smartDenied = false,
                        ),
                    ),
                ),
                onSend = {}, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
