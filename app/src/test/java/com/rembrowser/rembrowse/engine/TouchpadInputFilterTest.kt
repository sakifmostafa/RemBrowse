package com.rembrowser.rembrowse.engine

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.TouchpadGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a page's web view is handed for touchpad input. Android reports a touchpad as a mouse
 * whose pointer is a finger, and GeckoView takes anything that is not a mouse pointer for a
 * finger on a touchscreen: right clicks lost their button, and resting fingers mid-scroll were a
 * long press that opened menus.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TouchpadInputFilterTest {

    private val filter = TouchpadInputFilter(scrollSlopPx = SLOP)

    /** Copies of what the filter handed on, in order. */
    private val forwarded = mutableListOf<MotionEvent>()
    private val made = mutableListOf<MotionEvent>()

    @After
    fun recycle() {
        (forwarded + made).forEach { it.recycle() }
    }

    private fun feed(event: MotionEvent) {
        filter.filter(event) { forwarded += MotionEvent.obtain(it) }
    }

    private fun event(
        action: Int,
        x: Float,
        y: Float,
        source: Int,
        toolType: Int,
        buttons: Int = 0,
        downTime: Long = DOWN_TIME,
        eventTime: Long = DOWN_TIME
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, buttons, 1f, 1f, 7, 0, source, 0)
            .also { made += it }
    }

    private fun touchpad(action: Int, x: Float, y: Float, buttons: Int = 0, eventTime: Long = DOWN_TIME) =
        event(action, x, y, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_FINGER, buttons, eventTime = eventTime)

    @Test
    fun touchscreenInputIsPassedOnUntouched() {
        val down = event(MotionEvent.ACTION_DOWN, 10f, 10f, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
        val up = event(MotionEvent.ACTION_UP, 10f, 10f, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
        feed(down)
        feed(up)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), forwarded.map { it.actionMasked })
        assertTrue(forwarded.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER })
    }

    @Test
    fun aRealMouseClickIsPassedOnUntouched() {
        feed(event(MotionEvent.ACTION_DOWN, 5f, 6f, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.BUTTON_SECONDARY))
        feed(event(MotionEvent.ACTION_UP, 5f, 6f, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE))

        assertEquals(2, forwarded.size)
        assertTrue(forwarded.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE })
        assertEquals(MotionEvent.BUTTON_SECONDARY, forwarded[0].buttonState)
    }

    @Test
    fun aTouchpadRightClickReachesThePageAsAMouseRightClick() {
        feed(touchpad(MotionEvent.ACTION_DOWN, 200f, 150f, buttons = MotionEvent.BUTTON_SECONDARY))
        feed(touchpad(MotionEvent.ACTION_UP, 200f, 150f, eventTime = DOWN_TIME + 40))

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), forwarded.map { it.actionMasked })
        assertTrue("GeckoView only reads buttons on its mouse path", forwarded.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE })
        assertEquals(MotionEvent.BUTTON_SECONDARY, forwarded[0].buttonState)
        assertEquals(0, forwarded[1].buttonState)
        assertEquals(200f, forwarded[0].x, 0f)
        assertEquals(150f, forwarded[0].y, 0f)
        assertEquals(DOWN_TIME, forwarded[1].downTime)
        assertEquals(DOWN_TIME + 40, forwarded[1].eventTime)
        assertTrue(forwarded.all { it.isFromSource(InputDevice.SOURCE_MOUSE) })
    }

    @Test
    fun aTouchpadLeftClickAndDragStaysAMouseDrag() {
        feed(touchpad(MotionEvent.ACTION_DOWN, 10f, 10f, buttons = MotionEvent.BUTTON_PRIMARY))
        feed(touchpad(MotionEvent.ACTION_MOVE, 90f, 10f, buttons = MotionEvent.BUTTON_PRIMARY))
        feed(touchpad(MotionEvent.ACTION_UP, 90f, 10f))

        assertEquals(3, forwarded.size)
        assertTrue("Selecting text, not panning the page", forwarded.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE })
    }

    @Test
    fun fingersRestingMidScrollNeverReachThePage() {
        // Two fingers land, creep a few pixels and rest well past Gecko's long press delay
        feed(touchpad(MotionEvent.ACTION_DOWN, 300f, 200f))
        feed(touchpad(MotionEvent.ACTION_MOVE, 302f, 203f, eventTime = DOWN_TIME + 100))
        feed(touchpad(MotionEvent.ACTION_MOVE, 303f, 205f, eventTime = DOWN_TIME + 900))
        feed(touchpad(MotionEvent.ACTION_UP, 303f, 205f, eventTime = DOWN_TIME + 1500))

        assertEquals("No press to hold still, no tap to click", emptyList<Int>(), forwarded.map { it.actionMasked })
    }

    @Test
    fun aScrollReachesThePageWholeOnceItMoves() {
        feed(touchpad(MotionEvent.ACTION_DOWN, 300f, 200f))
        feed(touchpad(MotionEvent.ACTION_MOVE, 300f, 210f, eventTime = DOWN_TIME + 16))
        feed(touchpad(MotionEvent.ACTION_MOVE, 300f, 240f, eventTime = DOWN_TIME + 32))
        feed(touchpad(MotionEvent.ACTION_MOVE, 300f, 300f, eventTime = DOWN_TIME + 48))
        feed(touchpad(MotionEvent.ACTION_UP, 300f, 300f, eventTime = DOWN_TIME + 64))

        assertEquals(
            "The press where the fingers landed, then the move that went past the slop, then the rest",
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            forwarded.map { it.actionMasked }
        )
        assertEquals(200f, forwarded[0].y, 0f)
        assertEquals(DOWN_TIME, forwarded[0].eventTime)
        assertEquals(240f, forwarded[1].y, 0f)
        assertTrue("Still a finger, so Gecko pans and flings", forwarded.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER })
        assertTrue(forwarded.all { it.downTime == DOWN_TIME })
    }

    @Test
    fun theNextGestureStartsAfresh() {
        feed(touchpad(MotionEvent.ACTION_DOWN, 300f, 200f))
        feed(touchpad(MotionEvent.ACTION_UP, 300f, 200f))
        feed(touchpad(MotionEvent.ACTION_DOWN, 10f, 10f, buttons = MotionEvent.BUTTON_SECONDARY))

        assertEquals(1, forwarded.size)
        assertEquals(MotionEvent.TOOL_TYPE_MOUSE, forwarded[0].getToolType(0))
        assertEquals(10f, forwarded[0].x, 0f)
    }

    @Test
    fun aTouchpadsHoveringPointerIsAMouse_andOtherGenericEventsAreLeftAlone() {
        val hover = touchpad(MotionEvent.ACTION_HOVER_MOVE, 40f, 50f)
        TouchpadInputFilter.filterGeneric(hover) { forwarded += MotionEvent.obtain(it) }
        assertEquals(MotionEvent.TOOL_TYPE_MOUSE, forwarded.single().getToolType(0))
        assertEquals(MotionEvent.ACTION_HOVER_MOVE, forwarded.single().actionMasked)
        assertEquals(40f, forwarded.single().x, 0f)

        // A button notice keeps its action button, so it is not re-created
        val press = touchpad(MotionEvent.ACTION_BUTTON_PRESS, 40f, 50f, buttons = MotionEvent.BUTTON_SECONDARY)
        var passed: MotionEvent? = null
        TouchpadInputFilter.filterGeneric(press) { passed = it }
        assertTrue(passed === press)

        val wheel = event(MotionEvent.ACTION_SCROLL, 40f, 50f, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE)
        TouchpadInputFilter.filterGeneric(wheel) { passed = it }
        assertTrue(passed === wheel)
    }

    // Android 14+ classifies a touchpad's two-finger scroll and pinch. Compose 1.13 reads them by
    // that as a pan or a zoom with nothing pressed, and scrolls by each move's own distance, so a
    // window (whose events reach Compose first) passes them on as they come; a page's view holds
    // them back as before, since GeckoView still takes them for a finger.

    private val windowFilter = TouchpadInputFilter(scrollSlopPx = SLOP, passesClassifiedGestures = true)
    private val gestures = TouchpadGestures(downTime = DOWN_TIME)

    @After
    fun recycleGestures() = gestures.recycle()

    private fun feedWindow(event: MotionEvent) {
        windowFilter.filter(event) { forwarded += MotionEvent.obtain(it) }
    }

    @Test
    fun aWindow_passesAClassifiedScrollOnAsItComes_everyMoveOfIt() {
        // 4 px steps: the first four (16 px, the slop) used to be held back and then dropped
        val swipe = gestures.swipe(300f, 200f, steps = List(8) { 0f to -4f })
        for (event in swipe) {
            val before = forwarded.size
            feedWindow(event)
            assertEquals("Handed on at once: ${MotionEvent.actionToString(event.action)}", before + 1, forwarded.size)
        }

        assertEquals(swipe.map { it.actionMasked }, forwarded.map { it.actionMasked })
        assertEquals(swipe.map { it.y }, forwarded.map { it.y })
        assertEquals(
            "Every move's scroll distance arrives",
            32f,
            forwarded.sumOf { it.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE).toDouble() }.toFloat(),
            0.001f
        )
        assertTrue("Unchanged: still the classified pretend finger", forwarded.all {
            it.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE && it.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        })
    }

    @Test
    fun aWindow_passesAClassifiedPinchOnAsItComes() {
        val pinch = gestures.pinch(300f, 200f)
        for (event in pinch) {
            val before = forwarded.size
            feedWindow(event)
            assertEquals("Handed on at once: ${MotionEvent.actionToString(event.action)}", before + 1, forwarded.size)
        }

        assertEquals(pinch.map { it.action }, forwarded.map { it.action })
    }

    @Test
    fun aPage_stillHoldsBackAClassifiedScroll_untilItHasMoved_andForgetsOneThatDidNot() {
        gestures.swipe(300f, 200f, steps = List(8) { 0f to -4f }).forEach(::feed)
        assertEquals(
            "The press, the move past the slop (the fifth), the three after it and the lift",
            listOf(MotionEvent.ACTION_DOWN) + List(4) { MotionEvent.ACTION_MOVE } + MotionEvent.ACTION_UP,
            forwarded.map { it.actionMasked }
        )
        assertEquals(200f, forwarded[0].y, 0f)
        assertEquals(180f, forwarded[1].y, 0f)

        forwarded.clear()
        gestures.swipe(300f, 200f, steps = List(3) { 0f to -4f }).forEach(::feed)
        assertEquals("Lifted inside the slop: nothing for the page", emptyList<Int>(), forwarded.map { it.actionMasked })
    }

    @Test
    fun aWindow_stillHoldsBackAnUnclassifiedScroll_andMakesClicksAMouse() {
        // Older Android: no classification, so Compose takes the pretend finger for a finger
        feedWindow(touchpad(MotionEvent.ACTION_DOWN, 300f, 200f))
        feedWindow(touchpad(MotionEvent.ACTION_MOVE, 302f, 203f, eventTime = DOWN_TIME + 100))
        feedWindow(touchpad(MotionEvent.ACTION_UP, 302f, 203f, eventTime = DOWN_TIME + 900))
        assertTrue(forwarded.isEmpty())

        feedWindow(touchpad(MotionEvent.ACTION_DOWN, 10f, 10f, buttons = MotionEvent.BUTTON_SECONDARY))
        assertEquals(MotionEvent.TOOL_TYPE_MOUSE, forwarded.single().getToolType(0))
        assertEquals(MotionEvent.BUTTON_SECONDARY, forwarded.single().buttonState)
    }

    @Test
    fun isClassifiedGesture_isATwoFingerSwipeOrAPinch() {
        val swipe = gestures.event(MotionEvent.ACTION_DOWN, 1f, 1f, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE, DOWN_TIME)
        val pinch = gestures.event(MotionEvent.ACTION_DOWN, 1f, 1f, MotionEvent.CLASSIFICATION_PINCH, DOWN_TIME)
        // MotionEvent's CLASSIFICATION_MULTI_FINGER_SWIPE (4), hidden from apps: the system keeps
        // three-finger swipes
        val threeFingers = gestures.event(MotionEvent.ACTION_DOWN, 1f, 1f, 4, DOWN_TIME)
        val deepPress = gestures.event(MotionEvent.ACTION_DOWN, 1f, 1f, MotionEvent.CLASSIFICATION_DEEP_PRESS, DOWN_TIME)
        assertTrue(TouchpadInputFilter.isClassifiedGesture(swipe))
        assertTrue(TouchpadInputFilter.isClassifiedGesture(pinch))
        assertFalse(TouchpadInputFilter.isClassifiedGesture(threeFingers))
        assertFalse(TouchpadInputFilter.isClassifiedGesture(deepPress))
        assertFalse(TouchpadInputFilter.isClassifiedGesture(touchpad(MotionEvent.ACTION_DOWN, 1f, 1f)))
    }

    @Test
    fun scrollSlop_isATenthOfAnInch_butNeverLessThanTheTouchSlop() {
        assertEquals(16f, TouchpadInputFilter.scrollSlopPx(dpi = 160f, touchSlopPx = 8), 0.001f)
        assertEquals(32f, TouchpadInputFilter.scrollSlopPx(dpi = 320f, touchSlopPx = 16), 0.001f)
        assertEquals(24f, TouchpadInputFilter.scrollSlopPx(dpi = 120f, touchSlopPx = 24), 0.001f)
        // Gecko starts scrolling after 0.06 inch; the page must only ever see a press already past it
        assertTrue(TouchpadInputFilter.SCROLL_SLOP_INCHES > 0.06f)
    }

    private companion object {
        const val SLOP = 16f
        const val DOWN_TIME = 1_000L
    }
}
