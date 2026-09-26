package io.github.lesj0610.hermes.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.RunCategory
import io.github.lesj0610.hermes.data.RunClause
import io.github.lesj0610.hermes.data.RunSummary
import io.github.lesj0610.hermes.data.SkillActivity
import io.github.lesj0610.hermes.data.TranscriptItem
import io.github.lesj0610.hermes.data.formatElapsed
import io.github.lesj0610.hermes.data.summarizeRun
import io.github.lesj0610.hermes.data.summarizeShellCommand
import io.github.lesj0610.hermes.ui.components.ChevronIcon
import io.github.lesj0610.hermes.ui.components.ToolCard
import io.github.lesj0610.hermes.ui.theme.LocalRunColors
import kotlinx.coroutines.delay

/*
 * The desktop's two quiet rows, ported: a reasoning block rests as "Thought for
 * 12s", and a run of tool calls rests as one summary line. Both open on a tap.
 * Everything they say comes from data/ToolRuns.kt; this only draws it.
 */

/** Tall enough for a few lines of a live thought, short enough not to take the screen. */
private val LIVE_PREVIEW_MAX = 160.dp

/** How close to the bottom counts as "still reading the tail". */
private val PREVIEW_RELOCK = 24.dp

/**
 * One reasoning block, headed by how long it took.
 *
 * Open or shut follows the desktop. With [collapsedByDefault] off, a block that
 * is streaming shows a short live preview that follows the newest tokens, and a
 * block watched while it streamed stays open once it settles — a finished
 * thought must not snap shut under the reader. A block that mounts already
 * finished (an earlier thought, or a reopened session) rests shut. With it on,
 * every block rests as its one-line header. A tap overrides either.
 */
@Composable
internal fun ThinkingDisclosure(item: TranscriptItem.Reasoning, collapsedByDefault: Boolean) {
    val colors = LocalRunColors.current
    val pending = item.pending

    // null until the user taps: until then the preview rule decides.
    var userOpen by rememberSaveable(item.key) { mutableStateOf<Boolean?>(null) }
    var sawLive by rememberSaveable(item.key) { mutableStateOf(false) }
    LaunchedEffect(pending) { if (pending) sawLive = true }

    // The collapsed-by-default preference outranks the latch: it opts out of
    // live previews entirely, so there is nothing to hold open. The clip stays
    // after the block settles — unmounting it then is the jump the latch is for.
    val showPreview = !collapsedByDefault && (pending || sawLive)
    val open = userOpen ?: showPreview
    val isPreview = userOpen == null && showPreview

    val elapsed = rememberElapsedSeconds(item.startedAtMillis, active = pending)
    val duration = item.durationSeconds
    // Three ways a finished block reports itself. A measured duration says so,
    // unless whole seconds round it to "0s" — accurate and useless — so it says
    // it was brief. With no duration at all it still has to read as finished.
    val label = when {
        pending -> stringResource(R.string.thinking_live)
        duration == null -> stringResource(R.string.thinking_done)
        duration < 1 -> stringResource(R.string.thinking_brief)
        else -> stringResource(R.string.thinking_for, formatElapsed(duration))
    }

    Column(Modifier.fillMaxWidth()) {
        ScaffoldRow(
            label = label,
            open = open,
            live = pending,
            trailing = if (pending) elapsed?.let(::formatElapsed) else null,
            onToggle = { userOpen = !open },
        )
        if (open) {
            val style = MaterialTheme.typography.bodyMedium
            if (isPreview) {
                // A real scroller pinned to the tail, not a clip: the newest
                // tokens are the ones worth seeing while it thinks. Pinned only
                // while the reader is at the bottom — someone who scrolled up
                // to reread a line is left there until they come back down.
                val scroll = rememberScrollState()
                val relock = with(LocalDensity.current) { PREVIEW_RELOCK.roundToPx() }
                LaunchedEffect(scroll) {
                    var lastMax = 0
                    snapshotFlow { scroll.maxValue }.collect { max ->
                        if (lastMax - scroll.value < relock) scroll.scrollTo(max)
                        lastMax = max
                    }
                }
                Text(
                    text = item.text,
                    style = style,
                    color = colors.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LIVE_PREVIEW_MAX)
                        .verticalScroll(scroll),
                )
            } else {
                Text(text = item.text, style = style, color = colors.muted)
            }
        }
    }
}

/**
 * A run of consecutive activity: its summary line, and the calls behind it.
 *
 * A run of one is simply that call — the desktop draws no header over a single
 * row. Two or more rest as the summary until opened. While the run is [live]
 * the summary narrates in the present tense and a one-line ticker under it
 * shows the call in progress, each new one sliding the last up and out, so a
 * turn that touches thirty files reads as one line ticking over rather than a
 * list growing down the screen. When it settles the ticker goes and the summary
 * is all that is left.
 */
@Composable
internal fun ToolRunRow(tools: List<TranscriptItem.ToolCall>, runKey: String, live: Boolean) {
    var open by rememberSaveable(runKey) { mutableStateOf(false) }
    if (tools.size < 2) {
        tools.firstOrNull()?.let { ToolCard(it) }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScaffoldRow(
            label = runSummaryText(summarizeRun(tools, live)),
            open = open,
            live = live,
            onToggle = { open = !open },
        )
        when {
            open -> tools.forEach { ToolCard(it) }
            live -> ToolTicker(tools.last(), live)
        }
    }
}

@Composable
private fun ToolTicker(latest: TranscriptItem.ToolCall, live: Boolean) {
    val colors = LocalRunColors.current
    AnimatedContent(
        targetState = latest,
        contentKey = { it.key },
        transitionSpec = {
            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        },
        label = "tool-ticker",
    ) { tool ->
        // The row's own line: the same wording the run would use for this one
        // call, so the ticker and the summary never describe it differently.
        Text(
            text = runSummaryText(summarizeRun(listOf(tool), live = live && tool.pending)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * The quiet header both rows share: a label, then the chevron, then anything
 * trailing. Muted when settled; full strength while the thing it heads is live.
 */
@Composable
private fun ScaffoldRow(
    label: String,
    open: Boolean,
    live: Boolean,
    onToggle: () -> Unit,
    trailing: String? = null,
) {
    val colors = LocalRunColors.current
    val tone = if (live) MaterialTheme.colorScheme.onSurface else colors.muted
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Label and chevron travel together, and the label gives way first.
        // Only this group is weighted: a second weighted spacer beside it split
        // the width in half and cut a summary off with room to spare.
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ChevronIcon(modifier = Modifier.size(12.dp).rotate(if (open) 90f else 0f), tint = tone)
        }
        trailing?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}

/** Seconds since [since], ticking once a second while [active]. */
@Composable
private fun rememberElapsedSeconds(since: Long?, active: Boolean): Long? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return since?.let { ((now - it) / 1000).coerceAtLeast(0) }
}

// ── wording ───────────────────────────────────────────────────────────────

/**
 * A run summary in the current language — "Explored 3 files, ran 5 commands"
 * in English, where every clause after the first starts lower-case. Korean
 * clauses open with a noun or a file name, which must keep its case, so the
 * resource flag leaves them alone there.
 */
@Composable
internal fun runSummaryText(summary: RunSummary): String {
    val lowerJoins = booleanResource(R.bool.run_summary_lowercase_joins)
    val parts = summary.clauses.map { clauseText(it) } +
        listOfNotNull(
            summary.failed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.run_failed, it, it) },
        )
    return parts.mapIndexed { index, text ->
        if (index > 0 && lowerJoins) text.replaceFirstChar { it.lowercase() } else text
    }.joinToString(", ")
}

@Composable
private fun clauseText(clause: RunClause): String = when (clause) {
    is RunClause.Counted -> pluralStringResource(
        countedRes(clause.category, clause.present), clause.count, clause.count,
    )
    is RunClause.Named -> stringResource(
        namedRes(clause.category, clause.present), displayTarget(clause.category, clause.target),
    )
    is RunClause.Skill -> {
        val label = stringResource(skillRes(clause.activity))
        clause.target?.let { stringResource(R.string.skill_with_target, label, it) } ?: label
    }
}

/** A command reads as the command that matters; anything else reads as it was captured. */
@Composable
private fun displayTarget(category: RunCategory, target: String): String {
    if (category != RunCategory.Run) return target
    val shell = summarizeShellCommand(target)
    val command = shell.command.ifEmpty { target }
    return if (shell.more == 0) command else pluralStringResource(R.plurals.shell_more, shell.more, command, shell.more)
}

private fun countedRes(category: RunCategory, present: Boolean): Int = when (category) {
    RunCategory.Edit -> if (present) R.plurals.run_edit_counted_present else R.plurals.run_edit_counted_past
    RunCategory.Explore -> if (present) R.plurals.run_explore_counted_present else R.plurals.run_explore_counted_past
    RunCategory.Run -> if (present) R.plurals.run_run_counted_present else R.plurals.run_run_counted_past
    RunCategory.Delegate -> if (present) R.plurals.run_delegate_counted_present else R.plurals.run_delegate_counted_past
    RunCategory.Other -> if (present) R.plurals.run_other_counted_present else R.plurals.run_other_counted_past
}

private fun namedRes(category: RunCategory, present: Boolean): Int = when (category) {
    RunCategory.Edit -> if (present) R.string.run_edit_named_present else R.string.run_edit_named_past
    RunCategory.Explore -> if (present) R.string.run_explore_named_present else R.string.run_explore_named_past
    RunCategory.Run -> if (present) R.string.run_run_named_present else R.string.run_run_named_past
    RunCategory.Delegate -> if (present) R.string.run_delegate_named_present else R.string.run_delegate_named_past
    RunCategory.Other -> if (present) R.string.run_other_named_present else R.string.run_other_named_past
}

private fun skillRes(activity: SkillActivity): Int = when (activity) {
    SkillActivity.Loading -> R.string.skill_loading
    SkillActivity.Loaded -> R.string.skill_loaded
    SkillActivity.LoadFailed -> R.string.skill_load_failed
    SkillActivity.ReadingResource -> R.string.skill_reading_resource
    SkillActivity.ReadResource -> R.string.skill_read_resource
    SkillActivity.ResourceFailed -> R.string.skill_resource_failed
    SkillActivity.Listing -> R.string.skill_listing
    SkillActivity.Listed -> R.string.skill_listed
    SkillActivity.ListFailed -> R.string.skill_list_failed
}
