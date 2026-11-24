package com.rembrowser.rembrowse.engine

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Hands a page's web view a touchpad's events as what they are: mouse clicks, and scrolls that
 * cannot be taken for a long press or a tap.
 *
 * Android's own touchpad support (Android 14 and later, as on a Googlebook) reports the pad as a
 * mouse whose pointer is a finger: `SOURCE_MOUSE` with `TOOL_TYPE_FINGER`. GeckoView decides
 * between mouse and touch by the tool type alone, so it takes every touchpad event for a finger
 * on a touchscreen:
 *
 * - A click becomes a tap. A right click (two-finger tap, or the pad's corner) loses its button
 *   on the way, so the page sees a left tap, or a long press if the click is held, and never a
 *   right click. Such a click stream is passed on as the mouse it is: the same event with
 *   `TOOL_TYPE_MOUSE`, which GeckoView sends to Gecko as a mouse press with its buttons.
 * - Two-finger scrolling arrives as one pretend finger, pressed where the pointer is and dragged
 *   as the fingers move. Left like that, fingers resting before they move far enough are a finger
 *   held still: after half a second Gecko fires a long press, which opens the page's own context
 *   menu, or selects the word under the pointer and shows the selection toolbar; a short nudge
 *   lifted again is a tap, a click where the pointer is. So the stream is held back until the
 *   fingers have travelled [scrollSlopPx], further than Gecko waits before it scrolls, and then
 *   passed on whole: Gecko sees the press and at once a move past its tolerance, and pans (with
 *   its fling) without ever having seen a finger at rest. Fingers lifted before that have done
 *   nothing, and nothing is passed on.
 *
 * Touchscreen and stylus input, real mouse clicks, and touchpad streams of more than one pointer
 * (a pinch) are passed on as they come.
 *
 * Used twice: by the activity for its whole window, so the browser's own controls get mouse
 * clicks from the touchpad too (Compose decides by tool type as well: a touchpad click was a tap,
 * so double-click, drag and triple-click selected nothing in its text fields, and resting fingers
 * long-pressed the tab rail), and by every page's web view, which also covers the ones shown in
 * windows of their own (extension popups). Filtering twice is harmless: what the first filter
 * hands on passes the second unchanged or, for a scroll, released at once; a scroll the window's
 * filter passes on as it comes ([passesClassifiedGestures]) is held back by the page's alone.
 *
 * Main thread only, as views are.
 *
 * @param scrollSlopPx how far a touchpad scroll travels before the page sees it; it must be more
 *   than Gecko's own touch start tolerance (`apz.touch_start_tolerance`, 0.06 inch).
 * @param passesClassifiedGestures true for a whole window, whose events reach Compose before any
 *   page: a two-finger scroll or pinch that Android 14 and later mark as one
 *   ([isClassifiedGesture]) is then passed on as it comes. Compose 1.13 reads a two-finger
 *   scroll by its classification as a pan whose pointer is never pressed, so resting fingers
 *   can't long-press anything in Compose, and scrolls its lists by each event's own distance
 *   (`AXIS_GESTURE_SCROLL_X_DISTANCE` and `_Y_DISTANCE`), so the moves held back here and dropped
 *   were scrolling lost: up to [scrollSlopPx] at the start of every two-finger scroll in the tab
 *   rail, Settings and dialogs. A pinch was never held for long: its second finger released it at
 *   once. A page's web view keeps holding them back (false, the default), since GeckoView still
 *   takes them for a finger. Unclassified gestures (older Android) are held back either way.
 */
class TouchpadInputFilter(
    private val scrollSlopPx: Float,
    private val passesClassifiedGestures: Boolean = false
) {

    private enum class Stream {
        /** Passed on unchanged: a touchscreen, a stylus, a real mouse, or no gesture at all. */
        AS_IS,

        /** A touchpad click: passed on as a mouse. */
        CLICK,

        /** A touchpad scroll that has not moved far enough yet: held back. */
        SCROLL_HELD,

        /** A touchpad scroll that has: passed on unchanged until the fingers lift. */
        SCROLL_MOVING
    }

    private var stream = Stream.AS_IS
    private var heldDown: MotionEvent? = null

    /**
     * Takes [event], as the web view received it in `onTouchEvent`, and gives [forward] what the
     * view should handle instead: the event itself, a mouse copy of it, an event held back from
     * earlier followed by it, or nothing. An event given to [forward] is only valid during the
     * call.
     */
    fun filter(event: MotionEvent, forward: (MotionEvent) -> Unit) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dropHeldDown()
                stream = if (passesClassifiedGestures && isClassifiedGesture(event)) Stream.AS_IS else streamFor(event)
                when (stream) {
                    Stream.AS_IS -> forward(event)
                    Stream.CLICK -> forwardAsMouse(event, forward)
                    Stream.SCROLL_HELD -> heldDown = MotionEvent.obtain(event)
                    Stream.SCROLL_MOVING -> forward(event)
                }
                return
            }
        }

        when (stream) {
            Stream.AS_IS, Stream.SCROLL_MOVING -> forward(event)
            Stream.CLICK -> forwardAsMouse(event, forward)
            Stream.SCROLL_HELD -> filterHeldScroll(event, forward)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dropHeldDown()
                stream = Stream.AS_IS
            }
        }
    }

    private fun filterHeldScroll(event: MotionEvent, forward: (MotionEvent) -> Unit) {
        val down = heldDown ?: run {
            stream = Stream.AS_IS
            forward(event)
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(down.getPointerId(0)).takeIf { it >= 0 } ?: 0
                val travel = hypot(event.getX(index) - down.x, event.getY(index) - down.y)
                if (travel > scrollSlopPx) releaseHeldScroll(event, forward)
            }
            // A second pointer (a pinch): Gecko gets the whole gesture, as it always did
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> releaseHeldScroll(event, forward)
            // Lifted, or taken away, before it went anywhere: the page never hears of it
            else -> Unit
        }
    }

    private fun releaseHeldScroll(event: MotionEvent, forward: (MotionEvent) -> Unit) {
        heldDown?.let(forward)
        dropHeldDown()
        stream = Stream.SCROLL_MOVING
        forward(event)
    }

    private fun dropHeldDown() {
        heldDown?.recycle()
        heldDown = null
    }

    companion object {
        /** How far a touchpad scroll goes before a page sees it, in inches; Gecko's own is 0.06. */
        const val SCROLL_SLOP_INCHES = 0.1f

        /** [SCROLL_SLOP_INCHES] on a screen of [dpi], but never less than [touchSlopPx]. */
        fun scrollSlopPx(dpi: Float, touchSlopPx: Int): Float =
            maxOf(SCROLL_SLOP_INCHES * dpi, touchSlopPx.toFloat())

        /**
         * Hover events come through `dispatchGenericMotionEvent`, apart from presses. A touchpad's
         * hovering pointer is handed on as a mouse too, so the window sees one mouse from hover
         * to click to hover (Compose then shows hover states and cursors for it). Everything else,
         * including the button press and release notices, is handed on unchanged.
         */
        fun filterGeneric(event: MotionEvent, forward: (MotionEvent) -> Unit) {
            val hover = when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
                else -> false
            }
            if (hover && isTouchpadFinger(event)) forwardAsMouse(event, forward) else forward(event)
        }

        /**
         * A touchpad's two-finger scroll or pinch as Android 14 and later mark it
         * (`CLASSIFICATION_TWO_FINGER_SWIPE`, `CLASSIFICATION_PINCH`): what Compose 1.13 reads as
         * a pan or a zoom instead of a finger.
         */
        fun isClassifiedGesture(event: MotionEvent): Boolean =
            Build.VERSION.SDK_INT >= 34 &&
                (event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE ||
                    event.classification == MotionEvent.CLASSIFICATION_PINCH)

        /** A touchpad reported the Android way: a mouse source whose pointer is not a mouse. */
        fun isTouchpadFinger(event: MotionEvent): Boolean =
            event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.pointerCount > 0 &&
                event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE

        private fun streamFor(down: MotionEvent): Stream = when {
            !down.isFromSource(InputDevice.SOURCE_MOUSE) -> Stream.AS_IS
            // A press with a button is a click. A real mouse already says it is one.
            down.buttonState != 0 -> if (isTouchpadFinger(down)) Stream.CLICK else Stream.AS_IS
            // A mouse-source press without a button is a touchpad gesture: Android's two-finger
            // scroll (tool type finger), or a ChromeOS-style one (tool type mouse, which GeckoView
            // also turns into touch). Either is held back until it moves.
            down.pointerCount == 1 -> Stream.SCROLL_HELD
            else -> Stream.AS_IS
        }

        /** [event] with every pointer a mouse, so GeckoView takes the mouse path with its buttons. */
        private fun forwardAsMouse(event: MotionEvent, forward: (MotionEvent) -> Unit) {
            val count = event.pointerCount
            val properties = Array(count) { i ->
                MotionEvent.PointerProperties().also {
                    event.getPointerProperties(i, it)
                    it.toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().also { event.getPointerCoords(i, it) } }
            val mouse = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                count,
                properties,
                coords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags
            )
            try {
                forward(mouse)
            } finally {
                mouse.recycle()
            }
        }
    }
}

/**
 * Keeps hover events from a page's view while a touchpad's two-finger scroll or pinch is on it,
 * from its press to its lift.
 *
 * When the layout changes during a two-finger scroll, Compose 1.13 sends the last event again
 * as a hover move, so that hover states follow content moving under the cursor
 * (`AndroidComposeView.resendMotionEventOnLayout`, under
 * `ComposeUiFlags.isTrackpadPanHoverFixEnabled`, on by default). Compose places its own pointer
 * at the cursor, but what its view interop hands
 * an embedded view (`AndroidViewHolder.dispatchGenericMotionEvent`) is a copy of the last move,
 * at the pretend finger, which Android moves with the scroll, away from the cursor. A web view
 * takes a hover move for the mouse (GeckoView sends it to the page as a mouse move), so mid-scroll
 * the page saw the mouse jump to where the pretend finger was, with hover effects there, and
 * [PaneGeckoView.lastPointerPosition] moved with it. The cursor stays put during such a gesture,
 * so no real hover is lost.
 *
 * Main thread only, as views are.
 */
class TouchpadGestureHoverGuard {

    private var gestureOn = false

    /**
     * Every touch event the view was handed, once it has, and whether it [took] it. A view that
     * declines a press (a web view without a page) is handed nothing more of that gesture, its
     * lift included, so the gesture counts only when taken.
     */
    fun onTouchEvent(event: MotionEvent, took: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureOn = took && TouchpadInputFilter.isClassifiedGesture(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestureOn = false
        }
    }

    /** Whether the view drops [event], a generic motion event it was handed. */
    fun drops(event: MotionEvent): Boolean = gestureOn && when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
        else -> false
    }
}
