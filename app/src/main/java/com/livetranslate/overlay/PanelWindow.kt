package com.livetranslate.overlay

/** What the window manager has to do to bring the overlay in line with the state we want. */
enum class PanelAction { NONE, CREATE, SHOW, HIDE }

/**
 * Desired versus actual state of the translation panel window, with no Android types so it can be
 * unit-tested.
 *
 * The panel used to be removed from the WindowManager whenever the translation went blank (after
 * "替换", after a provider/language change) and added again on the next keystroke. Every such cycle
 * disposed the Compose composition and re-added an accessibility overlay, and on HarmonyOS/EMUI that
 * can end with a window that can never be added again — the panel then stayed away until the process
 * was killed, and every panel button that changed state made it worse.
 *
 * The fix is one window per service runtime whose visibility is toggled, plus this bookkeeping so a
 * window that the system did drop is recreated instead of silently missing.
 *
 * A short [minVisibleMs] dwell is also enforced: typing makes the "should be visible" answer flap
 * (a caret step, a provider switch, a blank intermediate state), and hiding/showing the window on
 * every flap is what the user sees as the panel blinking.
 *
 * The dwell is symmetric: [hideDelayMs] keeps the panel up while the content is blank for only a
 * moment. Typing through an IME, committing a composition or an editor that reports an empty text
 * mid-update all blank the source for a frame or two; hiding on that alone made the panel blink on
 * every word, and left it away when the sentence paused on such a frame.
 */
class PanelWindow(
    private val now: () -> Long,
    private val retryStepMs: Long = 1500L,
    /** Once on screen, the window stays at least this long so sub-second flapping never blinks. */
    private val minVisibleMs: Long = 450L,
    /** A blank panel has to stay blank this long before it is really hidden. */
    private val hideDelayMs: Long = 400L,
) {
    /** True when the panel should be on screen right now. */
    var wanted = false
        private set

    /** True while the window is added to the WindowManager. */
    var attached = false
        private set

    /** True while a ComposeView with a live composition exists for this window. */
    var alive = false
        private set

    /** True when the view is visible (the window may be attached but hidden). */
    var visible = false
        private set

    /** Consecutive failed attempts to add the window; drives the retry backoff. */
    var failures = 0
        private set

    private var retryAt = 0L
    private var shownAt = 0L
    private var unwantedAt = 0L

    fun want(show: Boolean) {
        if (show) {
            wanted = true
            unwantedAt = 0L
        } else if (wanted) {
            wanted = false
            unwantedAt = now()
        }
    }

    /** The single next step; the caller applies it and reports back, then asks again. */
    fun next(): PanelAction = when {
        !wanted -> when {
            !attached || !visible -> PanelAction.NONE
            now() - shownAt < minVisibleMs -> PanelAction.NONE
            now() - unwantedAt < hideDelayMs -> PanelAction.NONE
            else -> PanelAction.HIDE
        }
        !alive || !attached -> if (now() >= retryAt) PanelAction.CREATE else PanelAction.NONE
        !visible -> PanelAction.SHOW
        else -> PanelAction.NONE
    }

    fun created() {
        alive = true
    }

    fun windowAdded() {
        attached = true
        failures = 0
        retryAt = 0L
    }

    fun shown() {
        visible = true
        shownAt = now()
        unwantedAt = 0L
    }

    fun hidden() {
        visible = false
        shownAt = 0L
        unwantedAt = 0L
    }

    /** The system took the window away. [alive] = false forces a new composition. */
    fun windowLost(alive: Boolean) {
        attached = false
        visible = false
        this.alive = alive
        shownAt = 0L
        unwantedAt = 0L
        if (!alive) failures = 0
    }

    /** Adding the window failed; retry later with a growing delay instead of hammering the system. */
    fun attachFailed() {
        alive = false
        attached = false
        visible = false
        failures++
        retryAt = now() + retryStepMs * failures.coerceAtMost(8)
    }
}
