package com.rembrowser.rembrowse.ui

import com.rembrowser.rembrowse.model.PxRect
import com.rembrowser.rembrowse.ui.components.ScreenCaptureState
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the screenshot under way may finish: one given up on can't come back as the next.
 *
 * A plain JVM test on purpose. Under Robolectric, writing Compose state outside a composition
 * after other Compose tests have run in the same sandbox hands Compose's global apply notice to
 * the main looper, which Robolectric clears when the test ends; Compose then never applies a
 * global change again in that JVM, and every later Compose test waits for idle until it times out.
 */
class ScreenCaptureStateTest {

    private val state = ScreenCaptureState()
    private val area = PxRect(0, 0, 100, 80)
    private val copied = ScreenshotResult.Copied(null, 100, 80)

    @Test
    fun aLateResult_ofAScreenshotGivenUpOn_isIgnored() {
        assertTrue(state.start())
        state.capture(area)
        val first = state.generation
        assertEquals(ScreenCaptureState.Phase.CAPTURING, state.phase)

        // It timed out: the screen finished it as failed
        assertTrue(state.finish(ScreenshotResult.Failed, first))
        assertSame(ScreenshotResult.Failed, state.notice?.result)

        // Its copy turns up after all
        assertFalse(state.finish(copied, first))
        assertSame(ScreenshotResult.Failed, state.notice?.result)

        // Nor can it finish the next one
        assertTrue(state.start())
        state.capture(area)
        val second = state.generation
        assertTrue(second != first)
        assertFalse(state.finish(copied, first))
        assertEquals(ScreenCaptureState.Phase.CAPTURING, state.phase)
        assertNull("The last notice went when the overlay came up", state.notice)

        assertTrue(state.finish(copied, second))
        assertEquals(ScreenCaptureState.Phase.IDLE, state.phase)
        assertSame(copied, state.notice?.result)
    }

    @Test
    fun nothingIsTaken_unlessTheOverlayIsUp() {
        state.capture(area)
        assertEquals(ScreenCaptureState.Phase.IDLE, state.phase)
        assertFalse(state.finish(copied, state.generation))

        state.start()
        state.capture(PxRect(10, 10, 10, 50))
        assertEquals("An empty area takes nothing", ScreenCaptureState.Phase.SELECTING, state.phase)
        assertFalse("Already up", state.start())
        state.cancel()
        assertEquals(ScreenCaptureState.Phase.IDLE, state.phase)
    }

    @Test
    fun aWholePage_isTakenFromTheOverlayOrStraightAway_butNotDuringAnotherScreenshot() {
        // From the command palette, without the overlay
        assertTrue(state.captureWholePage("t1"))
        assertEquals(ScreenCaptureState.Phase.CAPTURING, state.phase)
        assertEquals("t1", state.wholePageTabId)
        assertNull(state.area)
        assertFalse("One at a time", state.captureWholePage("t2"))
        assertFalse(state.start())
        assertTrue(state.finish(wholePage, state.generation))
        assertNull(state.wholePageTabId)
        assertSame(wholePage, state.notice?.result)

        // From the overlay: the last notice goes, as for any screenshot
        state.start()
        assertTrue(state.captureWholePage("t2"))
        assertNull(state.notice)
        assertEquals("t2", state.wholePageTabId)
        state.finish(ScreenshotResult.TooLong, state.generation)
        assertSame(ScreenshotResult.TooLong, state.notice?.result)

        // An area afterwards is not a whole page
        state.start()
        state.capture(area)
        assertNull(state.wholePageTabId)
        assertEquals(area, state.area)
    }

    @Test
    fun theOverlaysKeys_areOnlyRequestedWhileItIsUp() {
        state.requestWholePageCapture()
        assertEquals(0, state.wholePageRequests)
        state.start()
        state.requestWholePageCapture()
        assertEquals(1, state.wholePageRequests)
    }

    @Test
    fun cancellingAWholePage_leavesNoNotice_andALateResultIsIgnored() {
        state.captureWholePage("t1")
        val taken = state.generation
        state.cancelWholePage()
        assertEquals(ScreenCaptureState.Phase.IDLE, state.phase)
        assertNull(state.wholePageTabId)
        assertNull(state.notice)
        assertFalse(state.finish(wholePage, taken))
        assertNull(state.notice)

        // Cancel is for a whole page only: an area being copied carries on
        state.start()
        state.capture(area)
        state.cancelWholePage()
        assertEquals(ScreenCaptureState.Phase.CAPTURING, state.phase)
    }

    @Test
    fun onlyAWholePagesNotice_canBeSaved_onceAtATime_andTheResultTakesItsPlace() {
        assertFalse("No notice", state.startSaving())
        state.start()
        state.capture(area)
        state.finish(copied, state.generation)
        assertFalse("Part of the window is not offered for saving", state.startSaving())

        state.captureWholePage("t1")
        state.finish(wholePage, state.generation)
        val shown = state.notice!!
        assertTrue(state.startSaving())
        assertTrue(state.isSaving(shown))
        assertFalse("Already being saved", state.startSaving())

        val saved = ScreenshotResult.Saved("Screenshot 2026-11-25 at 14.03.22.png")
        state.finishSaving(saved)
        assertFalse(state.saving)
        assertSame(saved, state.notice?.result)
        assertFalse("Its notice has nothing more to save", state.startSaving())
    }

    @Test
    fun aSaveThatEndsLate_saysSo_unlessALaterScreenshotHasItsOwnNotice() {
        state.captureWholePage("t1")
        state.finish(wholePage, state.generation)
        state.startSaving()
        // The notice left by itself meanwhile: the answer still comes
        state.dismissNotice()
        state.finishSaving(ScreenshotResult.NotSaved)
        assertSame(ScreenshotResult.NotSaved, state.notice?.result)

        state.captureWholePage("t1")
        state.finish(wholePage, state.generation)
        state.startSaving()
        state.start()
        state.capture(area)
        state.finish(copied, state.generation)
        state.finishSaving(ScreenshotResult.Saved("a.png"))
        assertSame("The later screenshot's notice stays", copied, state.notice?.result)
        assertFalse(state.saving)

        // Nor does it show over the overlay
        state.captureWholePage("t1")
        state.finish(wholePage, state.generation)
        state.startSaving()
        state.start()
        state.finishSaving(ScreenshotResult.Saved("b.png"))
        assertNull(state.notice)
        assertEquals(ScreenCaptureState.Phase.SELECTING, state.phase)
    }

    private val wholePage = ScreenshotResult.Copied(null, 1280, 24_000, wholePage = true)
}
