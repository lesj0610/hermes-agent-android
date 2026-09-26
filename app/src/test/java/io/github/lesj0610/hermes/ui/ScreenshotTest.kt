package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import io.github.lesj0610.hermes.ui.components.ImageLightbox
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

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        /** A resource qualifier such as "ko", for checking a translation's fit. */
        locale: String? = null,
        /** Runs after the first frame, before the capture — a tap, say. */
        before: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        // The window is resized to match, not just the Surface inside it. The
        // class-level qualifier is 411dp, so without this a 690dp capture was
        // measured against a 411dp window and came out squeezed — the render
        // would have shown a layout bug the app does not have, and hidden the
        // proportions being checked.
        RuntimeEnvironment.setQualifiers(listOfNotNull(locale, "w${width}dp-h${height}dp-xhdpi").joinToString("-"))
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
        before()
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
                onToggleOpenAtLatest = {},
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

    /** A sent message with its picture, which used to leave no trace. */
    @Test
    fun chatSentImage() {
        capture("chat-sent-image", 411, 520) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = listOf(
                        TranscriptItem.UserText(
                            "u1",
                            "이 그래프 좀 봐줘",
                            images = listOf("data:image/jpeg;base64,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UUAFFFFABRRRQAUUUUAeH/tQXk6WHh+yV8W00s0zpgcugQKc9eBI/wCfsK+f69h/aavJ38WaVZM+baGx85EwOHeRgxz15Eafl7mvHq7KStFHNU+JljTv+Qha/wDXVf5irPiD/kL3H/Af/QRVbTv+Qha/9dV/mKs+IP8AkL3H/Af/AEEVt9ky+0Z1FFFSUFFFFABRRRQAUUUUAaPiD/kL3H/Af/QRWdWj4g/5C9x/wH/0EVnU5bsUdkFFFFIYUUUUAFbNr/yLF5/11H81rGrZtf8AkWLz/rqP5rVR6kyMaiiipKPp2iiivy0+zCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK+dvFP8AyM+r/wDX5N/6Ga+ia+dvFP8AyM+r/wDX5N/6Ga+k4c/iz9Dyc2+CPqZdbNr/AMixef8AXUfzWsatm1/5Fi8/66j+a19hHqeBIxqKKKko+h/2YtQ83Qta03ysfZ7lLjzN33vMXbjGOMeV1zzu9ufaa+VvgDq8Wl/ESCKfYEv4HtA7yBAjHDr16klAoHHLD6H6prjqq0jppu8QooorMsKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiszxNq8Wg+H9R1WfYUtIGlCPIEDsB8qbj0LHCjryR1o3A+T/izqH9p/EfX5/K8rZcm327t2fKAjz0HXZnHbOOetclRRXelZWONu7uWNO/5CFr/11X+Yqz4g/wCQvcf8B/8AQRVbTv8AkIWv/XVf5irPiD/kL3H/AAH/ANBFX9kn7RnUUUVJQUUUUAFFFFABRRRQBo+IP+Qvcf8AAf8A0EVnVo+IP+Qvcf8AAf8A0EVnU5bsUdkFFFFIYUUUUAFbNr/yLF5/11H81rGrZtf+RYvP+uo/mtVHqTIxqKKKko+naKKK/LT7MKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAr528U/8AIz6v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"),
                        ),
                        TranscriptItem.AssistantText("a1", "가로축이 잘렸습니다.", streaming = false),
                    ),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * The jump-to-latest button, which only appears once the tail is out of
     * view. Captured with openAtLatest off so the list stays at the top.
     */
    @Test
    fun chatJumpToLatest() {
        capture("chat-jump-latest", 411, 560) {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = (1..40).map { n ->
                        TranscriptItem.AssistantText("a$n", "이것은 $n 번째 단락입니다.", streaming = false)
                    },
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                openAtLatest = false,
                // Parked near the top, which is the only state the button
                // appears in: scrolled away from the tail.
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 0),
            )
        }
    }

    /** The full-screen viewer a tapped picture opens into. */
    @Test
    fun imageLightbox() {
        capture("image-lightbox", 411, 560) {
            ImageLightbox(
                dataUrl = "data:image/jpeg;base64,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UUAFFFFABRRRQAUUUUAeH/tQXk6WHh+yV8W00s0zpgcugQKc9eBI/wCfsK+f69h/aavJ38WaVZM+baGx85EwOHeRgxz15Eafl7mvHq7KStFHNU+JljTv+Qha/wDXVf5irPiD/kL3H/Af/QRVbTv+Qha/9dV/mKs+IP8AkL3H/Af/AEEVt9ky+0Z1FFFSUFFFFABRRRQAUUUUAaPiD/kL3H/Af/QRWdWj4g/5C9x/wH/0EVnU5bsUdkFFFFIYUUUUAFbNr/yLF5/11H81rGrZtf8AkWLz/rqP5rVR6kyMaiiipKPp2iiivy0+zCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAK+dvFP8AyM+r/wDX5N/6Ga+ia+dvFP8AyM+r/wDX5N/6Ga+k4c/iz9Dyc2+CPqZdbNr/AMixef8AXUfzWsatm1/5Fi8/66j+a19hHqeBIxqKKKko+h/2YtQ83Qta03ysfZ7lLjzN33vMXbjGOMeV1zzu9ufaa+VvgDq8Wl/ESCKfYEv4HtA7yBAjHDr16klAoHHLD6H6prjqq0jppu8QooorMsKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiszxNq8Wg+H9R1WfYUtIGlCPIEDsB8qbj0LHCjryR1o3A+T/izqH9p/EfX5/K8rZcm327t2fKAjz0HXZnHbOOetclRRXelZWONu7uWNO/5CFr/11X+Yqz4g/wCQvcf8B/8AQRVbTv8AkIWv/XVf5irPiD/kL3H/AAH/ANBFX9kn7RnUUUVJQUUUUAFFFFABRRRQBo+IP+Qvcf8AAf8A0EVnVo+IP+Qvcf8AAf8A0EVnU5bsUdkFFFFIYUUUUAFbNr/yLF5/11H81rGrZtf+RYvP+uo/mtVHqTIxqKKKko+naKKK/LT7MKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAr528U/8AIz6v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",
                onDismiss = {},
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

    /**
     * A settled turn as the desktop draws it: each thought reduced to how long
     * it took, each run of calls to one line, and the edit left standing on its
     * own because the edit is the deliverable.
     */
    private val runTurn: List<TranscriptItem> = run {
        val t0 = 1_700_000_000_000L
        listOf(
            TranscriptItem.UserText("u1", "벤치 스크립트 고치고 테스트 돌려줘"),
            TranscriptItem.Reasoning(
                "r1", "먼저 벤치 스크립트와 설정을 읽고 어디가 느린지 확인한다.", t0, t0 + 12_000,
            ),
            TranscriptItem.ToolCall("t1", "read_file", "214줄", ToolState.Completed, 0.1, target = "latency.py"),
            TranscriptItem.ToolCall("t2", "search_files", "3건", ToolState.Completed, 0.3, target = "warmup"),
            TranscriptItem.ToolCall(
                "t3", "terminal", "[50/50] 1.83 ms/iter", ToolState.Completed, 41.0,
                target = "cd /repo && python bench/latency.py --seq 4096 2>&1 | tail -20",
            ),
            TranscriptItem.ToolCall(
                "t4", "terminal", "fatal: not a git repository", ToolState.Failed, 0.1,
                target = "git diff --stat",
            ),
            TranscriptItem.Reasoning("r2", "워밍업 반복이 빠져 있다.", t0 + 60_000, t0 + 60_400),
            TranscriptItem.ToolCall(
                "t5", "patch", "bench/latency.py\n+    for _ in range(10):\n+        step()",
                ToolState.Completed, 0.2, target = "latency.py",
            ),
            TranscriptItem.ToolCall(
                "t6", "terminal", "[50/50] 1.61 ms/iter", ToolState.Completed, 38.0,
                target = "python bench/latency.py --seq 4096",
            ),
            TranscriptItem.AssistantText(
                "a1", "워밍업을 추가했고 1.83 → 1.61 ms/iter로 줄었습니다.", streaming = false,
            ),
        )
    }

    @Test
    fun chatToolRuns() {
        capture("chat-tool-runs", 411, 891) {
            ChatPane(
                state = ChatState(sessionId = "s1", items = runTurn),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                openAtLatest = false,
            )
        }
    }

    /** The same turn in Korean, where the clauses keep their own case. */
    @Test
    fun chatToolRunsKo() {
        capture("chat-tool-runs-ko", 411, 891, locale = "ko") {
            ChatPane(
                state = ChatState(sessionId = "s1", items = runTurn),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                openAtLatest = false,
            )
        }
    }

    /** A run opened by a tap: the calls behind the summary, as cards. */
    @Test
    fun chatToolRunOpen() {
        capture(
            "chat-tool-run-open", 411, 891,
            before = { compose.onNodeWithText("Explored 2 files", substring = true).performClick() },
        ) {
            ChatPane(
                state = ChatState(sessionId = "s1", items = runTurn),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
                openAtLatest = false,
            )
        }
    }

    /**
     * A run still going: the summary narrates the call it is waiting on, and
     * the ticker under it shows that call rather than a growing list.
     */
    @Test
    fun chatToolRunLive() {
        val t0 = System.currentTimeMillis() - 30_000
        capture("chat-tool-run-live", 411, 891, locale = "ko") {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = listOf(
                        TranscriptItem.UserText("u1", "테스트 돌려줘"),
                        TranscriptItem.Reasoning("r1", "테스트 설정부터 본다.", t0, t0 + 8_000),
                        TranscriptItem.ToolCall("t1", "read_file", null, ToolState.Completed, target = "build.gradle.kts"),
                        TranscriptItem.ToolCall("t2", "read_file", null, ToolState.Completed, target = "settings.gradle.kts"),
                        TranscriptItem.ToolCall(
                            "t3", "terminal", "> Task :app:testDebugUnitTest", ToolState.Running,
                            target = "cd /repo && ./gradlew test 2>&1 | tail -20",
                        ),
                    ),
                    phase = RunPhase.Running("r1"),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * A thought still streaming: "Thinking" with its timer, and a short preview
     * pinned to the newest line instead of the whole block pushing the reply
     * off the screen.
     */
    @Test
    fun chatThinkingLive() {
        val thought = (1..14).joinToString("\n") { "단계 $it: 캐시 적중률과 워밍업 반복 수의 관계를 확인한다." }
        capture("chat-thinking-live", 411, 891, locale = "ko") {
            ChatPane(
                state = ChatState(
                    sessionId = "s1",
                    items = listOf(
                        TranscriptItem.UserText("u1", "왜 첫 반복만 느린지 분석해줘"),
                        TranscriptItem.Reasoning(
                            "r1", thought, startedAtMillis = System.currentTimeMillis() - 7_000,
                        ),
                    ),
                    phase = RunPhase.Running("r1"),
                ),
                onSend = { _, _ -> }, onStop = {}, onDismissError = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
