package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.core.LayoutMode
import io.github.lesj0610.hermes.core.ReasoningEffort
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.PendingApproval
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.data.UpdateState
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.DetailedHealth
import io.github.lesj0610.hermes.net.ModelChoice
import io.github.lesj0610.hermes.net.ModelEntry
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.Project
import io.github.lesj0610.hermes.net.ProjectFolder
import io.github.lesj0610.hermes.net.ProjectsPayload
import io.github.lesj0610.hermes.net.Release
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.net.Skill
import io.github.lesj0610.hermes.net.Toolset
import io.github.lesj0610.hermes.ui.artifacts.Artifact
import io.github.lesj0610.hermes.ui.artifacts.ArtifactKind
import io.github.lesj0610.hermes.ui.artifacts.ArtifactsPane
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.components.ArchiveIcon
import io.github.lesj0610.hermes.ui.components.BranchIcon
import io.github.lesj0610.hermes.ui.components.CameraIcon
import io.github.lesj0610.hermes.ui.components.ChatIcon
import io.github.lesj0610.hermes.ui.components.CheckIcon
import io.github.lesj0610.hermes.ui.components.ClockIcon
import io.github.lesj0610.hermes.ui.components.CopyIcon
import io.github.lesj0610.hermes.ui.components.DocumentIcon
import io.github.lesj0610.hermes.ui.components.DrawerContent
import io.github.lesj0610.hermes.ui.components.DrawerEntry
import io.github.lesj0610.hermes.ui.components.ExportIcon
import io.github.lesj0610.hermes.ui.components.FolderIcon
import io.github.lesj0610.hermes.ui.components.LinkIcon
import io.github.lesj0610.hermes.ui.components.MoreIcon
import io.github.lesj0610.hermes.ui.components.PaneDivider
import io.github.lesj0610.hermes.ui.components.PaperclipIcon
import io.github.lesj0610.hermes.ui.components.PencilIcon
import io.github.lesj0610.hermes.ui.components.PhotoIcon
import io.github.lesj0610.hermes.ui.components.PinIcon
import io.github.lesj0610.hermes.ui.components.RefreshIcon
import io.github.lesj0610.hermes.ui.components.TrashIcon
import io.github.lesj0610.hermes.ui.components.UpdateBanner
import io.github.lesj0610.hermes.ui.projects.ProjectsPane
import io.github.lesj0610.hermes.ui.search.SearchPane
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.settings.SettingsPane
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
        TranscriptItem.UserText("u1", "벤치 한 번 돌려주고 이전 결과랑 비교해줘"),
        TranscriptItem.AssistantText("a1", "벤치 스크립트부터 확인하겠습니다.", streaming = false),
        TranscriptItem.ToolCall("t1", "read_file", "bench/latency.py · 214줄", ToolState.Completed, 0.4),
        TranscriptItem.ToolCall(
            "t2", "bash",
            "python bench/latency.py --seq 4096\n[24/50] 1.83 ms/iter",
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
        SessionSummary(id = "1", title = "커널 벤치", model = "opus-5",
            preview = "중간 결과는 1.83 ms/iter…"),
        SessionSummary(id = "2", title = "게이트웨이 로그 점검", model = "opus-5",
            preview = "승인 대기 · bash", pinned = true),
        SessionSummary(id = "3", title = "설치 경로 확인",
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
                // The destinations the app actually offers now: gateway and
                // workspace moved under settings.
                destinations = listOf(
                    DrawerEntry("대화", true) { ChatIcon(tint = it) },
                    DrawerEntry("프로젝트", false) { FolderIcon(tint = it) },
                    DrawerEntry("아티팩트", false) { DocumentIcon(tint = it) },
                    DrawerEntry("예약", false) { ClockIcon(tint = it) },
                ),
                onDestination = {},
                sessions = sampleSessions,
                selectedSessionId = "1",
                onSession = {},
                onSessionAction = { _, _ -> },
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
                            DrawerEntry("아티팩트", false) { DocumentIcon(tint = it) },
                            DrawerEntry("예약", false) { ClockIcon(tint = it) },
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
                    baseUrl = "http://gateway.example:8642",
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
                        id = "1", kind = ArtifactKind.Image, value = "/tmp/bench/latency.png",
                        label = "latency.png", sessionId = "1", sessionTitle = "커널 벤치",
                        timestamp = null,
                    ),
                    Artifact(
                        id = "2", kind = ArtifactKind.File, value = "/home/agent/out/report.pdf",
                        label = "report.pdf", sessionId = "1", sessionTitle = "커널 벤치",
                        timestamp = null,
                    ),
                    Artifact(
                        id = "3", kind = ArtifactKind.Link, value = "https://example.org/reports/latency",
                        label = "latency", sessionId = "2", sessionTitle = "게이트웨이 로그 점검",
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

    /** Projects, with the active one marked and its folders shown. */
    @Test
    fun projects() {
        capture("projects", 411, 891) {
            ProjectsPane(
                payload = ProjectsPayload(
                    projects = listOf(
                        Project(
                            id = "p1", slug = "kernels", name = "Kernel work",
                            description = "Decode path benchmarks",
                            primaryPath = "/home/agent/work/kernels",
                            folders = listOf(
                                ProjectFolder(path = "/home/agent/work/kernels", isPrimary = true),
                                ProjectFolder(path = "/home/agent/work/bench"),
                            ),
                        ),
                        Project(
                            id = "p2", slug = "mobile", name = "Mobile client",
                            folders = listOf(ProjectFolder(path = "/home/agent/work/mobile", isPrimary = true)),
                        ),
                    ),
                    activeId = "p1",
                ),
                busy = false,
                error = null,
                dashboardConfigured = true,
                onLoad = {},
                onCreate = { _, _, _ -> },
                onSetActive = {},
                onRename = { _, _ -> },
                onArchive = { _, _ -> },
                onBrowse = { emptyList() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * The icon set, at the size the row menus use it.
     *
     * These are hand-drawn paths and the menus they sit in are popups, which
     * a screenshot of the root does not capture — so they are rendered here
     * instead, where a glyph that came out as a blob is visible.
     */
    @Test
    fun icons() {
        capture("icons", 411, 120) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(12.dp)) {
                    listOf<@Composable () -> Unit>(
                        { PencilIcon(modifier = Modifier.size(22.dp)) },
                        { PinIcon(modifier = Modifier.size(22.dp)) },
                        { CopyIcon(modifier = Modifier.size(22.dp)) },
                        { BranchIcon(modifier = Modifier.size(22.dp)) },
                        { ExportIcon(modifier = Modifier.size(22.dp)) },
                        { ArchiveIcon(modifier = Modifier.size(22.dp)) },
                        { TrashIcon(modifier = Modifier.size(22.dp)) },
                        { CheckIcon(modifier = Modifier.size(22.dp)) },
                        { MoreIcon(modifier = Modifier.size(22.dp)) },
                        { RefreshIcon(modifier = Modifier.size(22.dp)) },
                    ).forEach { icon ->
                        Box(Modifier.padding(end = 12.dp)) { icon() }
                    }
                }
                Row(Modifier.padding(12.dp)) {
                    listOf<@Composable () -> Unit>(
                        { PencilIcon(modifier = Modifier.size(17.dp)) },
                        { PinIcon(modifier = Modifier.size(17.dp)) },
                        { CopyIcon(modifier = Modifier.size(17.dp)) },
                        { BranchIcon(modifier = Modifier.size(17.dp)) },
                        { ExportIcon(modifier = Modifier.size(17.dp)) },
                        { ArchiveIcon(modifier = Modifier.size(17.dp)) },
                        { TrashIcon(modifier = Modifier.size(17.dp)) },
                        { CheckIcon(modifier = Modifier.size(17.dp)) },
                        { FolderIcon(modifier = Modifier.size(17.dp)) },
                        { DocumentIcon(modifier = Modifier.size(17.dp)) },
                        { LinkIcon(modifier = Modifier.size(17.dp)) },
                        { PaperclipIcon(modifier = Modifier.size(17.dp)) },
                        { CameraIcon(modifier = Modifier.size(17.dp)) },
                        { PhotoIcon(modifier = Modifier.size(17.dp)) },
                    ).forEach { icon ->
                        Box(Modifier.padding(end = 10.dp)) { icon() }
                    }
                }
            }
        }
    }

    /**
     * Mid-run with nothing typed: the action button is Stop, and no bar sits
     * over the transcript. That bar was the whole reason for this change — it
     * covered the reply while the reply was being written.
     */
    @Test
    fun chatBusy() {
        capture("chat-busy", 411, 891) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = transcript,
                    phase = RunPhase.Running("r1"),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                modelLabel = "opus-5",
                effort = ReasoningEffort.High,
                voiceAvailable = true,
            )
        }
    }

    /** Markdown as the agent writes it: bold, bullets, a fence, a link. */
    @Test
    fun chatMarkdown() {
        capture("chat-markdown", 411, 891) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = listOf(
                        TranscriptItem.UserText("u1", "서울 날씨 알려줘"),
                        TranscriptItem.AssistantText(
                            "a1",
                            """
                            ## 서울 현재 날씨

                            - 기온: **23.8°C** (체감 24.8°C)
                            - 습도: 100%
                            - 바람: `1.54 m/s` 남동쪽

                            확인은 이렇게 합니다:

                            ```bash
                            curl -s "https://api.example.test/weather?q=seoul"
                            ```

                            자세한 건 [문서](https://example.test/docs)를 보세요.
                            """.trimIndent(),
                            streaming = false,
                        ),
                    ),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * The native renderer's table and maths.
     *
     * Marked streaming so it stays on this path: a finished reply with maths in
     * it goes to KaTeX in a WebView, which Robolectric cannot draw.
     */
    @Test
    fun chatTableMath() {
        capture("chat-table-math", 411, 891) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = listOf(
                        TranscriptItem.UserText("u1", "커널별 처리량 비교해줘"),
                        TranscriptItem.AssistantText(
                            "a1",
                            """
                            | 커널 | 처리량 | 비고 |
                            |------|-------:|:----:|
                            | FP16 | 1.00x | 기준 |
                            | FP8 | 0.98x | 동등 |
                            | FP4 | 0.71x | 명령 인출 |

                            상대 처리량 ${'$'}T_{rel}${'$'} 은 이렇게 정의했습니다:

                            ${'$'}${'$'}
                            T_{rel} = \frac{t_{FP16}}{t_{k}} \leq \alpha
                            ${'$'}${'$'}

                            오차는 ${'$'}\alpha = 0.05${'$'} 안입니다.
                            """.trimIndent(),
                            streaming = true,
                        ),
                    ),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** The banner, and the shape of the failure that needs an action. */
    @Test
    fun updateBanner() {
        val release = Release(
            version = "2.0",
            tag = "v2.0",
            notes = "Tables and maths.",
            apkUrl = "https://github.com/example/example/releases/download/v2.0/app.apk",
            apkBytes = 3_280_000,
        )
        capture("update-banner", 411, 320) {
            Column {
                UpdateBanner(
                    state = UpdateState.Available(release),
                    onUpdate = {}, onLater = {}, onGrant = {},
                )
                Spacer(Modifier.size(12.dp))
                UpdateBanner(
                    state = UpdateState.Downloading(release, 0.42f),
                    onUpdate = {}, onLater = {}, onGrant = {},
                )
                Spacer(Modifier.size(12.dp))
                UpdateBanner(
                    state = UpdateState.Failed(release, UpdateState.Reason.Permission),
                    onUpdate = {}, onLater = {}, onGrant = {},
                )
            }
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
