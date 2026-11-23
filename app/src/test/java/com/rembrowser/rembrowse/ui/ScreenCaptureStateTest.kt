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
}
