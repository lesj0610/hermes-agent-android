package io.github.lesj0610.hermes.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/*
 * How a turn's tool calls are presented — the desktop's rules, ported as they
 * stand in apps/desktop: run-summary.ts, lib/tool-render-class.ts,
 * lib/summarize-command.ts, and the run splitting in tool/fallback.tsx.
 *
 * The point is that the two surfaces read the same turn the same way. Where
 * the desktop made a judgement call, this makes the same one.
 */

/** The clause a tool call is counted under in a run's summary line. */
enum class RunCategory { Edit, Explore, Run, Delegate, Other }

private val FILE_EDIT_TOOLS = setOf("edit_file", "patch", "write_file")

// Tools whose own surface is the point of the turn, so they are never folded
// into a summary: an edit is the deliverable, a question has to be answered, a
// generated image is what was asked for, a fan-out shows its agents, and a
// consent card's controls must stay visible.
private val CARD_TOOLS = setOf(
    "clarify", "delegate_task", "image_generate", "manage_catalog", "manage_connections",
)

// Render nothing unless they fail: a todo list has its own panel on the
// desktop and a reaction's whole UI is the emoji landing on the bubble.
private val SILENT_TOOLS = setOf("react_to_message", "todo", "todo_list")

private val EXPLORE_TOOLS = setOf(
    "list_files", "read_file", "search_files", "session_search_recall",
    "vision_analyze", "web_extract", "web_search",
)

fun isFileEditTool(tool: String): Boolean = tool in FILE_EDIT_TOOLS

fun isCardTool(tool: String): Boolean = tool in CARD_TOOLS || isFileEditTool(tool)

fun isSilentTool(tool: String): Boolean = tool in SILENT_TOOLS

fun isSkillTool(tool: String): Boolean = tool == "skill_view" || tool == "skills_list"

fun toolCategory(tool: String): RunCategory = when {
    isFileEditTool(tool) -> RunCategory.Edit
    tool == "terminal" || tool == "execute_code" -> RunCategory.Run
    tool == "delegate_task" -> RunCategory.Delegate
    tool in EXPLORE_TOOLS || tool.startsWith("browser_") -> RunCategory.Explore
    else -> RunCategory.Other
}

// ── what a call acted on ──────────────────────────────────────────────────

/**
 * What a call acted on, captured when it starts.
 *
 * [target] is the raw command line for a command, a file's basename, or a
 * query or URL — whatever the desktop's header would name. [readsResource]
 * separates a skill load that read one of the skill's files from one that
 * loaded its instructions, which the desktop words differently.
 */
data class ToolAim(val target: String?, val readsResource: Boolean = false)

/**
 * Reads [ToolAim] out of a call's arguments.
 *
 * [fallback] is the one-line context a route sends when it sends no arguments
 * (the HTTP route's preview). It stands in wherever the desktop would name a
 * thing — but never for [RunCategory.Other], where the desktop names nothing
 * and a context string would only be a guess at what it meant.
 */
fun toolAim(tool: String, args: JsonObject?, fallback: String? = null): ToolAim {
    if (isSkillTool(tool)) {
        val name = args?.field("name")
        val file = args?.field("file_path")
        val target = listOfNotNull(name, file).joinToString(" → ").takeIf { it.isNotEmpty() }
        return ToolAim(target, readsResource = file != null)
    }
    val category = toolCategory(tool)
    val fromArgs = args?.let {
        if (category == RunCategory.Run) {
            it.field("command", "code")
        } else {
            it.field("path", "file", "filepath")?.let(::basename) ?: it.field("query", "url")
        }
    }
    val fromContext = fallback
        ?.takeIf { category != RunCategory.Other }
        ?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return ToolAim(fromArgs ?: fromContext)
}

private fun JsonObject.field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

/** The last path segment, whichever separator the path was written with. */
fun basename(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    return normalized.split('/').lastOrNull { it.isNotEmpty() } ?: normalized
}

// ── a command line, peeled ────────────────────────────────────────────────

/**
 * A command line with its plumbing removed: the command that matters, and how
 * many more real commands followed it.
 */
data class ShellSummary(val command: String, val more: Int)

private val SILENT_HEADS = setOf(
    "cd", "pushd", "popd", "export", "set", "unset", "source", ".", "true", "false", ":",
)
private val PIPE_TAIL_HEADS = setOf("head", "tail", "wc", "sort", "uniq")
private val ENV_ASSIGNMENT = Regex("^[A-Za-z_]\\w*=")
private val REDIRECT = Regex("^\\d*(?:>>?|<)$")
private val FD_DUPLICATE = Regex("^\\d*(?:>&|<&)\\d+$")
private val BOUNDARY_ECHO = Regex("-{2,}|_exit=|(?:^|\\s|=)\\$[?{]|PIPESTATUS")

/**
 * Agents wrap real work in plumbing — `cd <dir> && <cmd> 2>&1 | tail -20; echo
 * "x_exit=${PIPESTATUS[0]}"` — which buries the command anyone cares about.
 * Segments are split on top-level `&&` `||` `;` and newlines, pipe tails and
 * redirects are dropped, and setup and banner segments are discarded. One
 * survivor is shown as is; several become the first plus a count.
 *
 * Display only: the full command is still on the card.
 */
fun summarizeShellCommand(raw: String?): ShellSummary {
    val original = raw?.trim().orEmpty()
    if (original.isEmpty()) return ShellSummary("", 0)

    val segments = splitCompound(original)
    if (segments.size <= 1) return ShellSummary(cleanSegment(original).ifEmpty { original }, 0)

    val core = segments.map(::cleanSegment).filter { segment ->
        segment.isNotEmpty() && headWord(segment) !in SILENT_HEADS && !isBoundaryEcho(segment)
    }
    return when (core.size) {
        0 -> ShellSummary(original, 0)
        1 -> ShellSummary(core[0], 0)
        else -> ShellSummary(core[0], core.size - 1)
    }
}

/** Splits on chain separators outside quotes. A pipe is not one: it is plumbing. */
private fun splitCompound(input: String): List<String> {
    val segments = mutableListOf<String>()
    val buffer = StringBuilder()
    var quote: Char? = null
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (quote != null) {
            buffer.append(ch)
            if (ch == quote && input.getOrNull(i - 1) != '\\') quote = null
            i++
            continue
        }
        if (ch == '"' || ch == '\'') {
            quote = ch
            buffer.append(ch)
            i++
            continue
        }
        val operator = when {
            input.startsWith("&&", i) || input.startsWith("||", i) -> 2
            ch == ';' || ch == '\n' -> 1
            else -> 0
        }
        if (operator > 0) {
            segments += buffer.toString()
            buffer.clear()
            i += operator
            continue
        }
        buffer.append(ch)
        i++
    }
    segments += buffer.toString()
    return segments.map { stripPipeTail(it.trim()) }.filter { it.isNotEmpty() }
}

private fun splitWords(segment: String): List<String> {
    val words = mutableListOf<String>()
    val buffer = StringBuilder()
    var quote: Char? = null
    segment.forEachIndexed { i, ch ->
        if (quote != null) {
            buffer.append(ch)
            if (ch == quote && segment.getOrNull(i - 1) != '\\') quote = null
            return@forEachIndexed
        }
        if (ch == '"' || ch == '\'') {
            quote = ch
            buffer.append(ch)
            return@forEachIndexed
        }
        if (ch.isWhitespace()) {
            if (buffer.isNotEmpty()) {
                words += buffer.toString()
                buffer.clear()
            }
            return@forEachIndexed
        }
        buffer.append(ch)
    }
    if (buffer.isNotEmpty()) words += buffer.toString()
    return words
}

private fun commandName(word: String): String = word.substringAfterLast('/').ifEmpty { word }

/** The command word, past any `FOO=bar` assignments in front of it. */
private fun headWord(segment: String): String {
    val tokens = splitWords(segment)
    var index = 0
    while (index < tokens.size && ENV_ASSIGNMENT.containsMatchIn(tokens[index])) index++
    return commandName(tokens.getOrElse(index) { "" })
}

private fun stripPipeTail(segment: String): String {
    val words = splitWords(segment)
    val out = mutableListOf<String>()
    for (i in words.indices) {
        if (words[i] == "|" && commandName(words.getOrElse(i + 1) { "" }) in PIPE_TAIL_HEADS) break
        out += words[i]
    }
    return out.joinToString(" ").trim()
}

private fun cleanSegment(segment: String): String {
    val words = splitWords(segment)
    val out = mutableListOf<String>()
    var i = 0
    while (i < words.size) {
        val word = words[i]
        when {
            // The operator and the file it points at.
            REDIRECT.matches(word) -> i += 2
            FD_DUPLICATE.matches(word) -> i += 1
            else -> {
                out += word
                i += 1
            }
        }
    }
    return out.joinToString(" ").trim()
}

// A banner or exit-status echo is UI plumbing. An arbitrary `echo $VALUE` is
// not — it may be the command's actual output.
private fun isBoundaryEcho(segment: String): Boolean {
    val words = splitWords(segment)
    if (commandName(words.firstOrNull().orEmpty()) != "echo") return false
    return BOUNDARY_ECHO.containsMatchIn(words.drop(1).joinToString(" "))
}

// ── grouping ──────────────────────────────────────────────────────────────

/** One rendered row of a transcript: an item, or a run of consecutive activity. */
sealed interface TranscriptBlock {
    val key: String

    data class Item(val item: TranscriptItem) : TranscriptBlock {
        override val key: String get() = item.key
    }

    /**
     * Keyed by its first call, never by position. The desktop found that a run
     * keyed by index reshuffles the moment a turn settles; the first call is
     * the one thing a growing run never changes.
     *
     * A run of one is still a run, so that its key does not change when the
     * second call arrives — it just renders as the call itself.
     */
    data class Run(val tools: List<TranscriptItem.ToolCall>) : TranscriptBlock {
        override val key: String get() = "run:" + tools.first().key
    }
}

/**
 * Folds consecutive activity calls into runs.
 *
 * A card tool ends the run before it and stands alone, so a turn that reads,
 * edits, then reads again shows a summary, the edit, then a second summary —
 * in the order it happened. Anything that is not a tool call ends a run too.
 * A silent tool neither shows nor breaks the run it sits in, unless it failed.
 * Neither does a reasoning block with no text: the desktop drops a thought
 * with nothing in it rather than leave an empty header eating a row.
 */
fun groupTranscript(items: List<TranscriptItem>): List<TranscriptBlock> {
    val blocks = ArrayList<TranscriptBlock>(items.size)
    var run: MutableList<TranscriptItem.ToolCall>? = null

    fun closeRun() {
        run?.takeIf { it.isNotEmpty() }?.let { blocks += TranscriptBlock.Run(it.toList()) }
        run = null
    }

    for (item in items) {
        if (item is TranscriptItem.Reasoning && item.text.isBlank()) continue
        if (item !is TranscriptItem.ToolCall) {
            closeRun()
            blocks += TranscriptBlock.Item(item)
            continue
        }
        if (isSilentTool(item.tool) && item.state != ToolState.Failed) continue
        if (isCardTool(item.tool)) {
            closeRun()
            blocks += TranscriptBlock.Item(item)
            continue
        }
        val current = run ?: mutableListOf<TranscriptItem.ToolCall>().also { run = it }
        current += item
    }
    closeRun()
    return blocks
}

// ── the summary line ──────────────────────────────────────────────────────

/** How a skill call reads — the desktop's `skillActivity` wording, by state. */
enum class SkillActivity {
    Loading, Loaded, LoadFailed,
    ReadingResource, ReadResource, ResourceFailed,
    Listing, Listed, ListFailed,
}

/** One clause of a run's summary. Localised by the UI, never here. */
sealed interface RunClause {
    /** "Ran 5 commands", "Exploring 3 files". */
    data class Counted(val category: RunCategory, val count: Int, val present: Boolean) : RunClause

    /** "Edited wiring.tsx", "Running npm test". [target] is raw: a command is still whole. */
    data class Named(val category: RunCategory, val target: String, val present: Boolean) : RunClause

    /** "Loaded skill: comfyui". */
    data class Skill(val activity: SkillActivity, val target: String?) : RunClause
}

data class RunSummary(val clauses: List<RunClause>, val failed: Int)

/**
 * The single grey line that stands in for a run — "Explored 3 files, ran 5
 * commands".
 *
 * While the run is [live], the category holding its current call speaks in the
 * present tense, so the line reads as work in progress. Which call is current
 * is the outstanding one, or else the most recent: between sequential calls
 * nothing is outstanding and the run is still going.
 *
 * Whether the run is live is the caller's to say. A call can be left without a
 * result by a turn that ended, and a run like that must read as finished
 * rather than narrate work that stopped.
 */
fun summarizeRun(tools: List<TranscriptItem.ToolCall>, live: Boolean): RunSummary {
    val narrating = if (live) tools.firstOrNull { it.pending } ?: tools.lastOrNull() else null
    val liveCategory = narrating?.let { toolCategory(it.tool) }

    val skills = mutableListOf<RunClause>()
    val byCategory = mutableMapOf<RunCategory, MutableList<TranscriptItem.ToolCall>>()
    for (tool in tools) {
        if (isSkillTool(tool.tool)) {
            skills += skillClause(tool, live)
            continue
        }
        byCategory.getOrPut(toolCategory(tool.tool)) { mutableListOf() } += tool
    }

    // A fixed order, so the same run always reads the same way whichever
    // category happens to be live.
    val clauses = RunCategory.entries.mapNotNull { category ->
        byCategory[category]?.let { clause(category, it, present = category == liveCategory) }
    }
    return RunSummary(skills + clauses, failed = tools.count { it.state == ToolState.Failed })
}

/**
 * A category holding one thing names it; anything more is counted. A settled
 * command is the exception: "ran 1 command" is the useful reading, and a
 * command line only earns its space while it is the thing being waited on.
 */
private fun clause(
    category: RunCategory,
    tools: List<TranscriptItem.ToolCall>,
    present: Boolean,
): RunClause {
    val target = tools.singleOrNull()?.target
    return if (target != null && (present || category != RunCategory.Run)) {
        RunClause.Named(category, target, present)
    } else {
        RunClause.Counted(category, tools.size, present)
    }
}

private fun skillClause(tool: TranscriptItem.ToolCall, live: Boolean): RunClause.Skill {
    val pending = live && tool.pending
    val failed = tool.state == ToolState.Failed
    val activity = when {
        tool.tool == "skills_list" -> when {
            failed -> SkillActivity.ListFailed
            pending -> SkillActivity.Listing
            else -> SkillActivity.Listed
        }
        tool.readsResource -> when {
            failed -> SkillActivity.ResourceFailed
            pending -> SkillActivity.ReadingResource
            else -> SkillActivity.ReadResource
        }
        else -> when {
            failed -> SkillActivity.LoadFailed
            pending -> SkillActivity.Loading
            else -> SkillActivity.Loaded
        }
    }
    return RunClause.Skill(activity, tool.target)
}

// ── small readings ────────────────────────────────────────────────────────

/** "29s" under a minute, "1:01" after — the desktop's elapsed format. */
fun formatElapsed(seconds: Long): String = if (seconds < 60) {
    "${seconds}s"
} else {
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Whether a stored tool result reports failure, read the desktop's way: an
 * explicit `success`/`ok` of true wins over any error field, and a false one
 * is a failure whatever else is there.
 *
 * The JSON is often followed by gateway notes (a loop warning, a hint), so the
 * first line is tried when the whole text is not an object.
 */
fun resultLooksFailed(result: String?): Boolean {
    val text = result?.trim().orEmpty()
    if (!text.startsWith("{")) return false
    val obj = parseObject(text) ?: parseObject(text.lineSequence().first()) ?: return false

    fun flag(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull
    if (flag("success") == true || flag("ok") == true) return false
    if (flag("success") == false || flag("ok") == false) return true
    return when (val error = obj["error"]) {
        null -> false
        is JsonPrimitive -> if (error.isString) error.content.isNotBlank() else error.booleanOrNull == true
        else -> true
    }
}

private fun parseObject(text: String): JsonObject? =
    runCatching { lenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
