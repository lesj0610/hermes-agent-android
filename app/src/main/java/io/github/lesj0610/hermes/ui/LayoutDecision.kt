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
 * Whether the drawer may be pinned open as the shell's left column.
 *
 * Pinning costs a column. A window that can hold only two has already spent
 * them on the transcript and the rail, so pinning there would silently evict
 * the rail — which reads as the app losing a panel rather than as a setting
 * taking effect. The unfolded Fold 5 lands exactly here, at 690dp.
 *
 * The stored preference is left alone when this is false: the same device folds
 * back open into a window that can hold three.
 */
fun canPinDrawer(layout: ShellLayout): Boolean = layout == ShellLayout.Triple

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
