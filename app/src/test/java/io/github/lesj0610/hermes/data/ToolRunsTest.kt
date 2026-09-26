package io.github.lesj0610.hermes.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's reading of a turn's tool calls, as ported.
 *
 * Every expectation here is what the desktop shows for the same turn. The two
 * surfaces are meant to read one conversation the same way, so a difference is
 * a bug on one side or the other, not a style choice.
 */
class ToolRunsTest {

    private var next = 0

    private fun call(
        tool: String,
        target: String? = null,
        state: ToolState = ToolState.Completed,
        readsResource: Boolean = false,
    ) = TranscriptItem.ToolCall(
        key = "t-${next++}",
        tool = tool,
        preview = null,
        state = state,
        target = target,
        readsResource = readsResource,
    )

    private fun text(body: String) = TranscriptItem.AssistantText("a-${next++}", body, streaming = false)

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    // ── grouping ─────────────────────────────────────────────────────────

    @Test
    fun `consecutive calls fold into one run and prose ends it`() {
        val a = call("read_file")
        val b = call("terminal")
        val reply = text("확인했습니다.")
        val c = call("web_search")

        val blocks = groupTranscript(listOf(a, b, reply, c))

        assertEquals(3, blocks.size)
        assertEquals(listOf(a, b), (blocks[0] as TranscriptBlock.Run).tools)
        assertEquals(reply, (blocks[1] as TranscriptBlock.Item).item)
        assertEquals(listOf(c), (blocks[2] as TranscriptBlock.Run).tools)
    }

    @Test
    fun `an edit stands alone and splits the reading around it`() {
        val before = call("read_file")
        val edit = call("patch")
        val after = call("read_file")

        val blocks = groupTranscript(listOf(before, edit, after))

        assertEquals(listOf(before), (blocks[0] as TranscriptBlock.Run).tools)
        assertEquals(edit, (blocks[1] as TranscriptBlock.Item).item)
        assertEquals(listOf(after), (blocks[2] as TranscriptBlock.Run).tools)
    }

    @Test
    fun `a silent tool neither shows nor breaks the run, unless it failed`() {
        val a = call("terminal")
        val todo = call("todo")
        val b = call("terminal")
        assertEquals(
            listOf(listOf(a, b)),
            groupTranscript(listOf(a, todo, b)).map { (it as TranscriptBlock.Run).tools },
        )

        val broken = call("todo", state = ToolState.Failed)
        assertEquals(
            listOf(listOf(a, broken, b)),
            groupTranscript(listOf(a, broken, b)).map { (it as TranscriptBlock.Run).tools },
        )
    }

    @Test
    fun `an empty thought is dropped without splitting the run`() {
        val a = call("terminal")
        val b = call("terminal")
        val blocks = groupTranscript(listOf(a, TranscriptItem.Reasoning("r-0", "  "), b))
        assertEquals(listOf(listOf(a, b)), blocks.map { (it as TranscriptBlock.Run).tools })
    }

    @Test
    fun `a run keeps its key as it grows`() {
        // Keyed by position, a run's state would jump to another row the
        // moment a call landed in front of it.
        val first = call("read_file")
        val one = groupTranscript(listOf(first)).single()
        val two = groupTranscript(listOf(first, call("read_file"))).single()
        assertEquals(one.key, two.key)
    }

    // ── the summary line ─────────────────────────────────────────────────

    @Test
    fun `a settled run counts each category in a fixed order`() {
        // Commands first in the turn, still listed after exploring: the same
        // run always reads the same way.
        val tools = listOf(
            call("terminal", "ls"),
            call("terminal", "git status"),
            call("read_file", "a.kt"),
            call("search_files", "TODO"),
            call("browser_navigate", "https://example.com"),
        )
        assertEquals(
            RunSummary(
                listOf(
                    RunClause.Counted(RunCategory.Explore, 3, present = false),
                    RunClause.Counted(RunCategory.Run, 2, present = false),
                ),
                failed = 0,
            ),
            summarizeRun(tools, live = false),
        )
    }

    @Test
    fun `one file is named, one settled command is counted`() {
        val summary = summarizeRun(listOf(call("read_file", "Theme.kt"), call("terminal", "npm test")), live = false)
        assertEquals(
            listOf(
                RunClause.Named(RunCategory.Explore, "Theme.kt", present = false),
                RunClause.Counted(RunCategory.Run, 1, present = false),
            ),
            summary.clauses,
        )
    }

    @Test
    fun `a live run narrates only the category it is waiting on`() {
        val tools = listOf(
            call("read_file", "a.kt"),
            call("read_file", "b.kt"),
            call("terminal", "npm test", state = ToolState.Running),
        )
        assertEquals(
            listOf(
                RunClause.Counted(RunCategory.Explore, 2, present = false),
                // The command being waited on is the one thing worth naming.
                RunClause.Named(RunCategory.Run, "npm test", present = true),
            ),
            summarizeRun(tools, live = true).clauses,
        )
    }

    @Test
    fun `between calls the most recent one is still the live one`() {
        val tools = listOf(call("terminal", "make"), call("read_file", "out.log"))
        assertEquals(
            RunClause.Named(RunCategory.Explore, "out.log", present = true),
            summarizeRun(tools, live = true).clauses.first(),
        )
    }

    @Test
    fun `a call left open by an ended turn reads as finished`() {
        val tools = listOf(call("terminal", "sleep 999", state = ToolState.Running), call("terminal", "ls"))
        assertEquals(
            listOf(RunClause.Counted(RunCategory.Run, 2, present = false)),
            summarizeRun(tools, live = false).clauses,
        )
    }

    @Test
    fun `failures are counted across the run`() {
        val tools = listOf(
            call("terminal", "a", state = ToolState.Failed),
            call("read_file", "b"),
            call("web_search", "c", state = ToolState.Failed),
        )
        assertEquals(2, summarizeRun(tools, live = false).failed)
    }

    @Test
    fun `skills read by what they did, ahead of everything else`() {
        val tools = listOf(
            call("terminal", "ls"),
            call("skill_view", "comfyui"),
            call("skill_view", "comfyui → templates/sdxl.json", readsResource = true),
            call("skills_list", state = ToolState.Failed),
        )
        assertEquals(
            listOf(
                RunClause.Skill(SkillActivity.Loaded, "comfyui"),
                RunClause.Skill(SkillActivity.ReadResource, "comfyui → templates/sdxl.json"),
                RunClause.Skill(SkillActivity.ListFailed, null),
                RunClause.Counted(RunCategory.Run, 1, present = false),
            ),
            summarizeRun(tools, live = false).clauses,
        )
        assertEquals(
            RunClause.Skill(SkillActivity.Loading, "comfyui"),
            summarizeRun(listOf(call("skill_view", "comfyui", state = ToolState.Running)), live = true)
                .clauses.single(),
        )
    }

    // ── what a call acted on ─────────────────────────────────────────────

    @Test
    fun `a command keeps its whole line`() {
        val raw = "cd /repo && npm test 2>&1 | tail -20"
        assertEquals(ToolAim(raw), toolAim("terminal", args("""{"command": "cd /repo && npm test 2>&1 | tail -20"}""")))
        assertEquals(ToolAim("print(1)"), toolAim("execute_code", args("""{"code": "print(1)"}""")))
    }

    @Test
    fun `a file is named by its basename, a search by its query`() {
        assertEquals("Theme.kt", toolAim("read_file", args("""{"path": "/repo/ui/theme/Theme.kt"}""")).target)
        assertEquals("a.txt", toolAim("read_file", args("""{"path": "C:\\work\\a.txt"}""")).target)
        assertEquals("kotlin flow", toolAim("web_search", args("""{"query": "kotlin flow"}""")).target)
        assertEquals("https://example.com", toolAim("web_extract", args("""{"url": "https://example.com"}""")).target)
    }

    @Test
    fun `a skill names itself and the file it read`() {
        assertEquals(ToolAim("comfyui"), toolAim("skill_view", args("""{"name": "comfyui"}""")))
        assertEquals(
            ToolAim("comfyui → templates/sdxl.json", readsResource = true),
            toolAim("skill_view", args("""{"name": "comfyui", "file_path": "templates/sdxl.json"}""")),
        )
    }

    @Test
    fun `the context line stands in for missing arguments, except where nothing is named`() {
        assertEquals("npm test", toolAim("terminal", null, fallback = "npm test\nmore").target)
        assertEquals(null, toolAim("memory", null, fallback = "remembered something").target)
        assertEquals(null, toolAim("read_file", null, fallback = "   ").target)
    }

    // ── a command line, peeled ───────────────────────────────────────────

    @Test
    fun `plumbing is peeled off the command that matters`() {
        assertEquals(
            ShellSummary("npm test", 0),
            summarizeShellCommand("cd /repo && npm test 2>&1 | tail -20; echo \"x_exit=\${PIPESTATUS[0]}\""),
        )
        assertEquals(ShellSummary("ls -la", 0), summarizeShellCommand("ls -la > out.txt"))
        assertEquals(
            ShellSummary("pytest -q", 0),
            summarizeShellCommand("export PYTHONPATH=src; echo '----- tests -----'; pytest -q"),
        )
    }

    @Test
    fun `several real commands become the first and a count`() {
        assertEquals(ShellSummary("git status", 2), summarizeShellCommand("git status && git diff\ngit log -1"))
    }

    @Test
    fun `quoted separators and real output are left alone`() {
        assertEquals(ShellSummary("echo \"a && b\"", 0), summarizeShellCommand("echo \"a && b\""))
        // An echo of a value may be the answer itself, not a banner.
        assertEquals(ShellSummary("echo \$HOME", 0), summarizeShellCommand("cd /tmp && echo \$HOME"))
    }

    @Test
    fun `a line of nothing but setup is shown as written`() {
        assertEquals(ShellSummary("cd /repo && export A=1", 0), summarizeShellCommand("cd /repo && export A=1"))
        assertEquals(ShellSummary("", 0), summarizeShellCommand("  "))
        assertEquals(ShellSummary("", 0), summarizeShellCommand(null))
    }

    // ── small readings ───────────────────────────────────────────────────

    @Test
    fun `elapsed reads as seconds, then minutes and seconds`() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("29s", formatElapsed(29))
        assertEquals("59s", formatElapsed(59))
        assertEquals("1:00", formatElapsed(60))
        assertEquals("1:01", formatElapsed(61))
        assertEquals("10:00", formatElapsed(600))
    }

    @Test
    fun `a result reports failure the way the desktop reads it`() {
        assertTrue(resultLooksFailed("""{"success": false, "output": "x"}"""))
        assertTrue(resultLooksFailed("""{"ok": false}"""))
        assertTrue(resultLooksFailed("""{"error": "No such file"}"""))
        assertTrue(resultLooksFailed("""{"error": true}"""))
        assertTrue(resultLooksFailed("""{"error": {"code": 2}}"""))
        // Gateway notes after the JSON: the first line still decides.
        assertTrue(resultLooksFailed("{\"error\": \"timeout\"}\n[loop warning: same call 3 times]"))

        // An explicit success wins over a stray error field.
        assertFalse(resultLooksFailed("""{"success": true, "error": "warning only"}"""))
        assertFalse(resultLooksFailed("""{"error": ""}"""))
        assertFalse(resultLooksFailed("""{"error": null, "output": "done"}"""))
        assertFalse(resultLooksFailed("""{"output": "done", "exit_code": 0}"""))
        assertFalse(resultLooksFailed("plain text that mentions an error"))
        assertFalse(resultLooksFailed(null))
    }
}
