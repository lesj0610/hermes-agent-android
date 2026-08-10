package io.github.lesj0610.hermes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import io.github.lesj0610.hermes.core.HermesSettings
import io.github.lesj0610.hermes.data.ChatState
import io.github.lesj0610.hermes.data.PendingApproval
import io.github.lesj0610.hermes.data.RunPhase
import io.github.lesj0610.hermes.data.ToolState
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.net.ActiveProfile
import io.github.lesj0610.hermes.net.DashboardSkill
import io.github.lesj0610.hermes.net.Job
import io.github.lesj0610.hermes.net.Profile
import io.github.lesj0610.hermes.net.SessionSummary
import io.github.lesj0610.hermes.ui.chat.ApprovalSheet
import io.github.lesj0610.hermes.ui.chat.ChatPane
import io.github.lesj0610.hermes.ui.components.ChatIcon
import io.github.lesj0610.hermes.ui.components.ClockIcon
import io.github.lesj0610.hermes.ui.components.DrawerContent
import io.github.lesj0610.hermes.ui.components.DrawerEntry
import io.github.lesj0610.hermes.ui.components.GridIcon
import io.github.lesj0610.hermes.ui.components.ServerIcon
import io.github.lesj0610.hermes.ui.cron.CronPane
import io.github.lesj0610.hermes.ui.dashboard.DashboardPane
import io.github.lesj0610.hermes.ui.settings.PermissionState
import io.github.lesj0610.hermes.ui.settings.SettingsPane
import io.github.lesj0610.hermes.ui.theme.HermesTheme

/**
 * Compose previews, for looking at and clicking through screens in the IDE
 * without a build or a device.
 *
 * These live in the `debug` source set so they never reach a release build —
 * previews pull in the tooling library, which has no business shipping.
 *
 * The sample data mirrors what the screens really receive; a preview fed
 * unrealistically short strings hides exactly the overflow and wrapping
 * problems previews are good at catching.
 */

private val sampleTranscript = listOf(
    TranscriptItem.UserText("u1", "디코드 커널 벤치 한 번 돌려주고 이전 결과랑 비교해줘"),
    TranscriptItem.AssistantText("a1", "벤치 스크립트부터 확인하겠습니다.", streaming = false),
    TranscriptItem.ToolCall("t1", "read_file", "bench/decode_fp4.py · 214줄", ToolState.Completed, 0.4),
    TranscriptItem.ToolCall(
        "t2", "bash",
        "python bench/decode_fp4.py --seq 4096 --iters 50\n[24/50] 1.83 ms/iter",
        ToolState.Running,
    ),
    TranscriptItem.AssistantText("a2", "중간 결과는 1.83 ms/iter", streaming = true),
)

private val sampleSessions = listOf(
    SessionSummary(id = "1", title = "FP4 dequant 벤치", model = "opus-5", preview = "중간 결과는 1.83 ms/iter…"),
    SessionSummary(id = "2", title = "게이트웨이 로그 점검", model = "opus-5", preview = "승인 대기 · bash"),
    SessionSummary(id = "3", title = "Termux 설치 경로 확인", toolCallCount = 5, endedAt = "2026-08-08"),
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    HermesTheme {
        // The Scaffold paints the background in the app; previews need their
        // own or the dark theme renders on white.
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Preview(name = "Chat · running", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewChatRunning() = Frame {
    ChatPane(
        state = ChatState(sessionId = "s1", items = sampleTranscript, phase = RunPhase.Running("r1")),
        onSend = {}, onStop = {}, onDismissError = {},
    )
}

@Preview(name = "Chat · empty", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewChatEmpty() = Frame {
    ChatPane(state = ChatState(), onSend = {}, onStop = {}, onDismissError = {})
}

@Preview(name = "Chat · tablet", device = Devices.TABLET, showBackground = true)
@Composable
private fun PreviewChatTablet() = Frame {
    ChatPane(
        state = ChatState(sessionId = "s1", items = sampleTranscript, phase = RunPhase.Running("r1")),
        onSend = {}, onStop = {}, onDismissError = {},
    )
}

@Preview(name = "Approval sheet", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewApproval() = Frame {
    ApprovalSheet(
        approval = PendingApproval(
            runId = "r1",
            command = "rm -rf bench/results.old",
            choices = listOf("once", "session", "always", "deny"),
            smartDenied = false,
        ),
        onChoice = {},
    )
}

/**
 * Rendered at drawer width rather than phone width: the three bands only read
 * correctly in the space the sheet actually gets.
 */
@Preview(name = "Drawer", widthDp = 300, heightDp = 780, showBackground = true)
@Composable
private fun PreviewDrawer() = Frame {
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

@Preview(name = "Schedule", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewCron() = Frame {
    CronPane(
        jobs = listOf(
            Job(id = "j1", name = "야간 리포트", scheduleDisplay = "매일 03:00",
                prompt = "어제 실행 로그를 요약해서 텔레그램으로 보내줘", state = "active"),
            Job(id = "j2", name = "주간 백업 점검", scheduleDisplay = "매주 월 09:00",
                prompt = "백업 무결성 확인", state = "paused", enabled = false),
        ),
        onPause = {}, onResume = {}, onRun = {}, onDelete = {},
    )
}

@Preview(name = "Workspace", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewDashboard() = Frame {
    DashboardPane(
        state = DashboardState.Ready,
        profiles = listOf(
            Profile(name = "default", description = "기본 프로필"),
            Profile(name = "research", description = "리서치 전용"),
        ),
        active = ActiveProfile(active = "default", current = "default"),
        skills = listOf(
            DashboardSkill(name = "github-issue-to-pr", enabled = true, provenance = "bundled"),
            DashboardSkill(name = "p5js", enabled = false, provenance = "hub"),
        ),
        onSelectProfile = {}, onToggleSkill = {}, onRetry = {},
    )
}

@Preview(name = "Settings", device = Devices.PHONE, showBackground = true)
@Composable
private fun PreviewSettings() = Frame {
    SettingsPane(
        settings = HermesSettings(
            baseUrl = "http://192.0.2.10:8642",
            token = "not-a-real-token",
            model = "opus-5",
        ),
        connection = Connection.Connected(version = "0.20.0", latencyMs = 12),
        dashboardState = DashboardState.Off,
        models = emptyList(),
        permissions = PermissionState(canNotify = true, batteryExempt = false),
        onSaveServer = { _, _, _ -> },
        onSaveDashboard = { _, _, _, _ -> },
        onSelectModel = {}, onSelectLanguage = {},
        onToggleApprovals = {}, onToggleCompletion = {},
        onSelectLayoutMode = {}, onSetUiScale = {},
        onRequestNotifications = {}, onRequestBackground = {},
    )
}
