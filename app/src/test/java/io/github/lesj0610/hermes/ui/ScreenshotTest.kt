package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.PendingApproval
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.ReasoningEffort
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.ModelChoice
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.settings.SettingsPane
import io.github.lesj0610.hermes.ui.artifacts.Artifact
import io.github.lesj0610.hermes.ui.artifacts.ArtifactKind
import io.github.lesj0610.hermes.ui.artifacts.ArtifactsPane
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.components.ChatIcon
import io.github.lesj0610.hermes.ui.components.ClockIcon
import io.github.lesj0610.hermes.ui.components.DrawerContent
import io.github.lesj0610.hermes.ui.components.DrawerEntry
import io.github.lesj0610.hermes.ui.components.GridIcon
import io.github.lesj0610.hermes.ui.components.PaneDivider
import io.github.lesj0610.hermes.ui.components.ServerIcon
import io.github.lesj0610.hermes.ui.search.SearchPane
import io.github.lesj0610.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        // The window is resized to match, not just the Surface inside it. The
        // class-level qualifier is 411dp, so without this a 690dp capture was
        // measured against a 411dp window and came out squeezed — the render
        // would have shown a layout bug the app does not have, and hidden the
        // proportions being checked.
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-xhdpi")
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
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * The composer with every control it can carry: model, reasoning effort,
     * dictation and voice mode. This is the row most at risk of not fitting on
     * a narrow phone, which is why it is captured at phone width.
     */
    @Test
    fun chatEmpty() {
        capture("chat-empty", 411, 891) {
            ChatPane(
                state = ChatState(),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                modelLabel = "opus-5",
                modelChoices = listOf(
                    ModelChoice("nous", "Nous", "opus-5", reasoning = true),
                ),
                effort = ReasoningEffort.High,
                voiceAvailable = true,
            )
        }
    }

    private val sampleSessions = listOf(
        SessionSummary(id = "1", title = "FP4 dequant 벤치", model = "opus-5",
            preview = "중간 결과는 1.83 ms/iter…"),
        SessionSummary(id = "2", title = "게이트웨이 로그 점검", model = "opus-5",
            preview = "승인 대기 · bash"),
        SessionSummary(id = "3", title = "Termux 설치 경로 확인",
            toolCallCount = 5, endedAt = "2026-08-08", endReason = "completed"),
    )

    /** Tablet width, so rail proportions and the wider transcript can be judged. */
    @Test
    fun chatTablet() {
        capture("chat-tablet", 900, 800) {
            ChatPane(
                state = ChatState(sessionId = "s1", items = transcript, phase = RunPhase.Running("r1")),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * The drawer at its natural width, which is where the three-band layout —
     * destinations, session list, pinned bottom row — either reads or does not.
     */
    @Test
    fun drawer() {
        capture("drawer", 300, 891) {
            DrawerContent(
                modelLabel = "opus-5",
                connectionLabel = "연결됨",
                connectionColor = Color(0xFF4ADE80),
                destinations = listOf(
                    DrawerEntry("대화", true) { ChatIcon(tint = it) },
                    DrawerEntry("예약", false) { ClockIcon(tint = it) },
                    DrawerEntry("게이트웨이", false) { ServerIcon(tint = it) },
                    DrawerEntry("워크스페이스", false) { GridIcon(tint = it) },
                ),
                onDestination = {},
                sessions = sampleSessions,
                selectedSessionId = "1",
                onSession = {},
                onSearch = {},
                onNewChat = {},
                settingsSelected = false,
                onSettings = {},
                pinned = true,
                pinEnabled = true,
                onTogglePin = {},
                arrangeLabel = "배치",
                arranging = false,
                onArrange = {},
            )
        }
    }

    /**
     * The docked drawer beside the transcript, at the unfolded Fold 5's width.
     *
     * This frame is assembled the way the shell assembles it — drawer, divider,
     * content — rather than through HermesShell, which needs a ViewModel. It
     * exists because the seam between the two is where an unpainted gap shows,
     * and a gap there reads as a second divider.
     */
    @Test
    fun dockedDrawer() {
        capture("shell-docked", 690, 800) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(300.dp).fillMaxSize()) {
                    DrawerContent(
                        modelLabel = "opus-5",
                        connectionLabel = "연결됨",
                        connectionColor = Color(0xFF4ADE80),
                        destinations = listOf(
                            DrawerEntry("대화", true) { ChatIcon(tint = it) },
                            DrawerEntry("예약", false) { ClockIcon(tint = it) },
                            DrawerEntry("게이트웨이", false) { ServerIcon(tint = it) },
                        ),
                        onDestination = {},
                        sessions = sampleSessions,
                        selectedSessionId = "1",
                        onSession = {},
                        onSearch = {},
                        onNewChat = {},
                        settingsSelected = false,
                        onSettings = {},
                        pinned = true,
                        pinEnabled = true,
                        onTogglePin = {},
                        arrangeLabel = "배치",
                        arranging = false,
                        onArrange = {},
                    )
                }
                PaneDivider(onDelta = {}, onCommit = {})
                ChatPane(
                    state = ChatState(sessionId = "s1", items = transcript, phase = RunPhase.Running("r1")),
                    onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** The empty state, which is what search looks like the moment it opens. */
    @Test
    fun search() {
        capture("search", 411, 891) {
            SearchPane(
                sessions = sampleSessions,
                selectedSessionId = null,
                onSelect = {},
                onClose = {},
            )
        }
    }

    /**
     * The settings hub, which is the whole point of the restructure: every
     * subject one row, each row showing what it is currently set to. If a row
     * has to be opened to find out what it holds, this render will show it.
     */
    @Test
    fun settingsHub() {
        capture("settings", 411, 891) {
            SettingsPane(
                settings = HermesSettings(
                    baseUrl = "http://192.168.0.20:8642",
                    layoutMode = LayoutMode.Auto,
                ),
                connection = Connection.Connected("2.4.1", 38),
                dashboardState = DashboardState.Ready,
                models = listOf(ModelEntry(id = "opus-5")),
                permissions = PermissionState(canNotify = true, batteryExempt = false),
                onSaveServer = { _, _, _ -> },
                onSaveDashboard = { _, _, _, _ -> },
                onSelectModel = {},
                onSelectLanguage = {},
                onToggleApprovals = {},
                onToggleCompletion = {},
                onSelectLayoutMode = {},
                onSetUiScale = {},
                onRequestNotifications = {},
                onRequestBackground = {},
                activeModel = "opus-5",
                health = DetailedHealth(status = "ok", version = "2.4.1", gatewayState = "running"),
                toolsets = listOf(
                    Toolset(name = "bash", enabled = true),
                    Toolset(name = "browser", enabled = false),
                    Toolset(name = "files", enabled = true),
                ),
                agentSkills = listOf(Skill("research"), Skill("review")),
                profiles = listOf(Profile("default"), Profile("vllm")),
                activeProfile = ActiveProfile(active = "vllm", current = "vllm"),
                dashboardSkills = listOf(
                    DashboardSkill(name = "research", enabled = true),
                    DashboardSkill(name = "excel", enabled = false),
                ),
            )
        }
    }

    /**
     * Artifacts, including the case the screen exists to be honest about: a
     * gateway-local file the phone cannot open.
     */
    @Test
    fun artifacts() {
        capture("artifacts", 411, 891) {
            ArtifactsPane(
                artifacts = listOf(
                    Artifact(
                        id = "1", kind = ArtifactKind.Image, value = "/tmp/bench/decode_fp4.png",
                        label = "decode_fp4.png", sessionId = "1", sessionTitle = "FP4 dequant 벤치",
                        timestamp = null,
                    ),
                    Artifact(
                        id = "2", kind = ArtifactKind.File, value = "/home/u/hermes/report.pdf",
                        label = "report.pdf", sessionId = "1", sessionTitle = "FP4 dequant 벤치",
                        timestamp = null,
                    ),
                    Artifact(
                        id = "3", kind = ArtifactKind.Link, value = "https://github.com/vllm-project/vllm/pull/39074",
                        label = "39074", sessionId = "2", sessionTitle = "게이트웨이 로그 점검",
                        timestamp = null,
                    ),
                ),
                scan = ArtifactScan(running = false, scanned = 20, total = 20, available = 63),
                onOpenSession = {},
                onRescan = {},
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
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
