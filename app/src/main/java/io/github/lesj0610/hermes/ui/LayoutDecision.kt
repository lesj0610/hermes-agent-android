package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.LayoutMode

/**
 * Breakpoints, in dp of *window* width.
 *
 * 840dp is Material's expanded width breakpoint and roughly where the desktop
 * app's own rails stop making sense (it collapses both below 768px). 600dp is
 * the medium breakpoint — a tablet held in portrait lands here, at around
 * 800dp, which is wide enough for one rail beside the transcript but not for a
 * pinned drawer on top of that.
 *
 * Height matters as much as width, and leaving it out is a real bug rather than
 * a refinement: a large phone in landscape is wider than 840dp — a Pixel 8 Pro
 * is around 891dp — while being only ~411dp tall. Rails in a 411dp-tall window
 * are unusable. Material calls anything under 480dp "compact height", which in
 * practice means a handset on its side.
 */
const val EXPANDED_WIDTH_DP = 840
const val MEDIUM_WIDTH_DP = 600
const val MIN_MULTI_PANE_HEIGHT_DP = 480

/** How many columns the shell can hold at once. */
enum class ShellLayout {
    /** The transcript alone. The drawer opens over it. */
    Single,

    /** Transcript and rail. Both slots are spoken for, so the drawer floats. */
    Dual,

    /** Desktop-style: pinned drawer, transcript, rail, status bar. */
    Triple,
}

/**
 * The narrowest the transcript may become before a column beside it has to go.
 *
 * Below this the conversation stops being a conversation: at around 80dp the
 * Korean text in the settings pane wrapped to one character per line. This is
 * the floor that decides what fits, not the pane count — a window can be
 * nominally three columns wide and still not have room for three.
 */
const val MIN_CENTER_WIDTH_DP = 320f

/**
 * Whether the drawer can dock as the shell's left column at this width.
 *
 * Measured rather than inferred from [ShellLayout]. The tier answers how many
 * columns the window is *for*; it does not answer whether the columns as
 * currently sized leave a usable transcript. Forcing tablet mode on a 690dp
 * foldable produces exactly that gap.
 */
fun canDockDrawer(widthDp: Float, drawerWidthDp: Float): Boolean =
    widthDp - drawerWidthDp >= MIN_CENTER_WIDTH_DP

/**
 * Whether the rail still fits once the columns to its left have taken theirs.
 *
 * The rail yields first. It is the supporting view — tool calls, schedules,
 * gateway health — and squeezing the transcript to keep it is the wrong trade.
 * Docking the drawer on a two-column window drops the rail rather than
 * producing three unusable slivers.
 */
fun railFits(widthDp: Float, occupiedDp: Float, railWidthDp: Float): Boolean =
    widthDp - occupiedDp - railWidthDp >= MIN_CENTER_WIDTH_DP

/**
 * Chooses a shell from the *window* size, never from the device type.
 *
 * Device type is the wrong question on every modern form factor: a foldable is
 * both types within one session, split-screen hands a tablet a phone-sized
 * window, and desktop windowing resizes at will. A decision made once at launch
 * answers all three incorrectly.
 *
 * [LayoutMode.Phone] and [LayoutMode.Tablet] bypass the measurement — they
 * exist because the automatic answer is about available space rather than
 * preference, and someone may simply want the other one.
 */
fun resolveShellLayout(widthDp: Int, heightDp: Int, mode: LayoutMode): ShellLayout {
    if (mode == LayoutMode.Phone) return ShellLayout.Single
    if (mode == LayoutMode.Tablet) return ShellLayout.Triple

    if (heightDp < MIN_MULTI_PANE_HEIGHT_DP) return ShellLayout.Single
    return when {
        widthDp >= EXPANDED_WIDTH_DP -> ShellLayout.Triple
        widthDp >= MEDIUM_WIDTH_DP -> ShellLayout.Dual
        else -> ShellLayout.Single
    }
}
