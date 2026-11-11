package com.livetranslate

import com.livetranslate.overlay.PanelAction
import com.livetranslate.overlay.PanelWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel must never be removed from the WindowManager just because its content went blank: that
 * add/remove cycle is what made it disappear for good on the test device.
 */
class PanelWindowTest {
    private var now = 0L
    private val window = PanelWindow(now = { now }, retryStepMs = 1500L)

    /** Drives the window to "created, added and visible". */
    private fun showWindow() {
        window.want(true)
        assertEquals(PanelAction.CREATE, window.next())
        window.created()
        window.windowAdded()
        assertEquals(PanelAction.SHOW, window.next())
        window.shown()
    }

    /** Past the min-visible dwell, so a hide request is actually allowed through. */
    private fun waitDwell() {
        now += 500
    }

    @Test fun caretStepDoesNotBlinkThePanel() {
        showWindow()
        // Typing makes the "should be visible" answer flap twice within a few hundred ms; the
        // window must not be touched, or the user sees the panel blinking on every word.
        repeat(3) {
            window.want(false)
            assertEquals(PanelAction.NONE, window.next())
            window.want(true)
            assertEquals(PanelAction.NONE, window.next())
        }
        assertTrue(window.visible)
        assertTrue(window.attached)
    }

    @Test fun briefBlankDoesNotHideThePanel() {
        showWindow()
        now += 2000 // well past the min-visible dwell
        // An IME commit blanks the source for a moment; the panel must simply stay.
        window.want(false)
        assertEquals(PanelAction.NONE, window.next())
        now += 300
        window.want(true)
        assertEquals(PanelAction.NONE, window.next())
        assertTrue("a momentary blank must not hide the panel", window.visible)

        // A blank that really lasts does hide it.
        window.want(false)
        now += 500
        assertEquals(PanelAction.HIDE, window.next())
    }

    @Test fun dwellDoesNotKeepThePanelUpForever() {
        showWindow()
        window.want(false)
        assertEquals(PanelAction.NONE, window.next())
        waitDwell()
        assertEquals(PanelAction.HIDE, window.next())
        window.hidden()
        assertFalse(window.visible)
    }


    @Test fun panelReturnsWithoutRecreatingWindow() {
        showWindow()
        assertEquals(PanelAction.NONE, window.next())

        // 替换 / provider switch blanks the content: hide, keep the window.
        window.want(false)
        waitDwell()
        assertEquals(PanelAction.HIDE, window.next())
        window.hidden()
        assertEquals(PanelAction.NONE, window.next())
        assertTrue("window stays attached while hidden", window.attached)
        assertTrue("composition stays alive while hidden", window.alive)

        // Next keystroke: show the same window again, no CREATE.
        window.want(true)
        assertEquals(PanelAction.SHOW, window.next())
        window.shown()
        assertTrue(window.visible)
    }

    @Test fun hiddenPanelNeedsNoAction() {
        showWindow()
        window.want(false)
        waitDwell()
        assertEquals(PanelAction.HIDE, window.next())
        window.hidden()
        repeat(5) {
            window.want(false)
            assertEquals(PanelAction.NONE, window.next())
        }
        assertTrue(window.attached)
        assertFalse(window.visible)
    }

    @Test fun systemRemovedWindowIsRecreated() {
        showWindow()
        window.windowLost(alive = false)

        window.want(true)
        assertEquals(PanelAction.CREATE, window.next())
        window.created()
        window.windowAdded()
        assertEquals(PanelAction.SHOW, window.next())
    }

    @Test fun failureBacksOffInsteadOfHammering() {
        window.want(true)
        assertEquals(PanelAction.CREATE, window.next())
        window.attachFailed()

        assertEquals("no retry inside the backoff window", PanelAction.NONE, window.next())
        now += 1499
        assertEquals(PanelAction.NONE, window.next())

        now += 1
        assertEquals(PanelAction.CREATE, window.next())
        window.attachFailed()
        assertFalse(window.alive)
        assertFalse(window.attached)

        // Second failure waits twice as long.
        now += 1500
        assertEquals(PanelAction.NONE, window.next())
        now += 1500
        assertEquals(PanelAction.CREATE, window.next())
    }

    @Test fun successfulAddClearsFailures() {
        window.want(true)
        window.attachFailed()
        now += 1500
        assertEquals(PanelAction.CREATE, window.next())
        window.created()
        window.windowAdded()
        assertEquals(0, window.failures)
    }
}
