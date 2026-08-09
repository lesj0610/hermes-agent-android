package io.github.lesj0610.hermes.ui

import io.github.lesj0610.hermes.core.LayoutMode

/**
 * Breakpoints, in dp of *window* width.
 *
 * 840dp is Material's expanded width breakpoint and roughly where the desktop
 * app's own rails stop making sense (it collapses both below 768px). 600dp is
 * the medium breakpoint — a tablet held in portrait lands here, at around
 * 800dp, which is wide enough for a session rail beside the transcript but not
 * for a third rail on top of that.
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

/** How many panes the shell shows at once. */
enum class ShellLayout {
    /** One pane at a time: sessions → chat → settings. */
    Single,

    /** Session rail beside the transcript. No activity rail. */
    Dual,

    /** Desktop-style: session rail, transcript, activity rail, status bar. */
    Triple,
}

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
