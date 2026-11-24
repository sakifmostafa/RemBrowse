package com.rembrowser.rembrowse.fakes

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Touchpad gestures as Android 14 and later deliver them (GestureConverter): a mouse source whose
 * pointers are fingers with no button down, classified as a two-finger swipe or a pinch. A swipe
 * is one pretend finger pressed where the cursor is and moved by the scroll, with the scroll's
 * own step on `AXIS_GESTURE_SCROLL_X/Y_DISTANCE`; a pinch is two pretend fingers either side of
 * the cursor, with `AXIS_GESTURE_PINCH_SCALE_FACTOR`.
 *
 * Built with the `MotionEvent.obtain` overload that takes a classification (API 34), which
 * Robolectric's SDK 34 keeps: `getClassification()` reads it back, also on copies and after
 * `offsetLocation`, so Compose 1.13 takes these events for real touchpad gestures.
 */
class TouchpadGestures(private val downTime: Long = 1_000L) {

    private val made = mutableListOf<MotionEvent>()

    /** Recycles every event made. */
    fun recycle() {
        made.forEach { it.recycle() }
        made.clear()
    }

    /**
     * One event at [x], [y] (and, for a pinch's second finger, [x2]), in the coordinates of the
     * view it is dispatched to. [rawOffsetX] and [rawOffsetY] are that view's place on the screen:
     * the raw coordinates Compose reads for a classified gesture's cursor are the screen's.
     */
    fun event(
        action: Int,
        x: Float,
        y: Float,
        classification: Int,
        eventTime: Long,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
        x2: Float? = null,
        pinchScale: Float = 1f,
        rawOffsetX: Float = 0f,
        rawOffsetY: Float = 0f
    ): MotionEvent {
        val xs = listOfNotNull(x, x2)
        val properties = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = xs[i] + rawOffsetX
                this.y = y + rawOffsetY
                pressure = 1f
                setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, scrollX)
                setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, scrollY)
                if (classification == MotionEvent.CLASSIFICATION_PINCH) {
                    setAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, pinchScale)
                }
            }
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, xs.size, properties, coords,
            0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0, 0, classification
        )!!
        event.offsetLocation(-rawOffsetX, -rawOffsetY)
        made += event
        return event
    }

    /** The touchpad's pointer hovering at [x], [y], as before a gesture: the cursor arriving. */
    fun hover(x: Float, y: Float, eventTime: Long, rawOffsetX: Float = 0f, rawOffsetY: Float = 0f): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x + rawOffsetX; this.y = y + rawOffsetY })
        val event = MotionEvent.obtain(
            eventTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, 1, properties, coords,
            0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0
        )
        event.offsetLocation(-rawOffsetX, -rawOffsetY)
        made += event
        return event
    }

    /**
     * A two-finger swipe from [x], [y]: the press, then a move for each of [steps] (the pretend
     * finger's step, which is also the scroll's own distance), and the lift where it ended, 16 ms
     * apart.
     */
    fun swipe(
        x: Float,
        y: Float,
        steps: List<Pair<Float, Float>>,
        rawOffsetX: Float = 0f,
        rawOffsetY: Float = 0f,
        lift: Boolean = true
    ): List<MotionEvent> {
        val swipe = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
        var time = downTime
        var fx = x
        var fy = y
        val events = mutableListOf(event(MotionEvent.ACTION_DOWN, fx, fy, swipe, time, rawOffsetX = rawOffsetX, rawOffsetY = rawOffsetY))
        for ((dx, dy) in steps) {
            time += 16
            fx += dx
            fy += dy
            events += event(MotionEvent.ACTION_MOVE, fx, fy, swipe, time, scrollX = -dx, scrollY = -dy, rawOffsetX = rawOffsetX, rawOffsetY = rawOffsetY)
        }
        if (lift) events += event(MotionEvent.ACTION_UP, fx, fy, swipe, time + 16, rawOffsetX = rawOffsetX, rawOffsetY = rawOffsetY)
        return events
    }

    /** A pinch around [x], [y]: two pretend fingers [spread] apart pressed, spread a little, lifted. */
    fun pinch(x: Float, y: Float, spread: Float = 200f, rawOffsetX: Float = 0f, rawOffsetY: Float = 0f): List<MotionEvent> {
        val pinch = MotionEvent.CLASSIFICATION_PINCH
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val left = x - spread / 2
        val right = x + spread / 2
        fun at(action: Int, time: Long, l: Float, r: Float?, scale: Float = 1f) =
            event(action, l, y, pinch, downTime + time, x2 = r, pinchScale = scale, rawOffsetX = rawOffsetX, rawOffsetY = rawOffsetY)
        return listOf(
            at(MotionEvent.ACTION_DOWN, 0, left, null),
            at(MotionEvent.ACTION_POINTER_DOWN or second, 0, left, right),
            at(MotionEvent.ACTION_MOVE, 16, left - 10f, right + 10f, scale = 1.1f),
            at(MotionEvent.ACTION_POINTER_UP or second, 32, left - 10f, right + 10f),
            at(MotionEvent.ACTION_UP, 32, left - 10f, null)
        )
    }

    private companion object {
        const val DEVICE_ID = 7
    }
}
