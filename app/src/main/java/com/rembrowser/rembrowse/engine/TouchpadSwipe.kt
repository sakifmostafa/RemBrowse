package com.rembrowser.rembrowse.engine

import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs

/** Which way a two-finger swipe navigates. */
enum class SwipeDirection { BACK, FORWARD }

/**
 * What GeckoView says about the touch a swipe started with (`PanZoomController.InputResultDetail`):
 * who handled it ([handled], an `INPUT_RESULT_*`), which ways the scroll container under the
 * fingers can still scroll ([scrollable], `SCROLLABLE_FLAG_*`: a side set means it can still
 * scroll towards that side), and which ways it may hand a scroll on past its edge ([overscroll],
 * `OVERSCROLL_FLAG_*`, from CSS `overscroll-behavior`).
 */
data class PageScroll(val handled: Int, val scrollable: Int, val overscroll: Int)

/** Where a pane's two-finger swipes go: back and forward in the tab it shows. */
interface SwipeNavigation {
    /** Swipes are turned on in Settings. */
    val enabled: Boolean

    /** There is somewhere to go that way. */
    fun canGo(direction: SwipeDirection): Boolean

    fun go(direction: SwipeDirection)
}

/** Shows how far a swipe has gone: the arrow over the page. */
interface SwipeFeedback {
    /**
     * The swipe moved. [progress] is 0 where the swipe was taken from the page and 1 where
     * letting go navigates ([armed]); it can run past 1.
     */
    fun update(direction: SwipeDirection, progress: Float, armed: Boolean)

    /** The fingers lifted, or the swipe was called off; [navigated] when it went back or forward. */
    fun end(navigated: Boolean)
}

/** When a touchpad's two-finger swipe navigates instead of scrolling. Pure, so tested on the JVM. */
object SwipeRules {
    /** How far the fingers go sideways before the swipe can be taken from the page. */
    const val START_DP = 24f

    /** How far they go, in all, before letting go navigates (Chrome on Android: 96 dp). */
    const val ARM_DP = 96f

    /** A flick this fast towards the swipe navigates even before [ARM_DP]... */
    const val FLING_DP_PER_SECOND = 1000f

    /** ...once it has gone this share of the way. */
    const val FLING_MIN_PROGRESS = 0.3f

    /** Sideways must beat up-and-down by this much (desktop Chrome: 2.5). */
    const val HORIZONTAL_RATIO = 2.5f

    /**
     * Fingers moving right go back, left go forward. The pretend finger Android reports for a
     * touchpad scroll moves with the page (natural scrolling, Android's default), so right is
     * the way the page would slide to show what is to its left.
     */
    fun directionOf(dx: Float): SwipeDirection = if (dx > 0f) SwipeDirection.BACK else SwipeDirection.FORWARD

    /**
     * Whether the page leaves a sideways swipe towards [direction] alone, so it can navigate. As
     * in Chrome: only a scroll the page can't use. The scroll container under the fingers (the
     * page itself, or an inner one such as a chat's message list) must be at its edge that way,
     * and must let a scroll go on past it (`overscroll-behavior-x` not `contain` or `none`).
     * Gecko fills both in for the results it works out for a touch, handled or not
     * (APZInputBridge.cpp); a page with `touch-action: none` there reports no overscroll, so
     * maps keep the gesture. Unknown or ignored results keep it too.
     *
     * Known limits, as Gecko reports only the container under the fingers: a page that turns
     * off swipe navigation only on its root (`overscroll-behavior-x: none`) still navigates
     * over an inner list, a zoomed-in or extra-wide page goes back instead of panning sideways
     * when the fingers are over an inner list, and a page whose script takes touches itself (`preventDefault`, a
     * carousel with `touch-action: pan-y`) can lose a swipe to navigation. Chrome navigates in
     * the second case too, as it never hands touchpad swipes to a page's touch handlers.
     * Settings turns swipes off.
     */
    fun pageLeavesSwipe(scroll: PageScroll, direction: SwipeDirection): Boolean {
        val towards = when (direction) {
            // Back shows what is to the left: the container must not be able to scroll left
            SwipeDirection.BACK -> PanZoomController.SCROLLABLE_FLAG_LEFT
            SwipeDirection.FORWARD -> PanZoomController.SCROLLABLE_FLAG_RIGHT
        }
        if (scroll.scrollable and towards != 0) return false
        return when (scroll.handled) {
            // Nothing there scrolls (UNHANDLED), or the page or an inner container does
            PanZoomController.INPUT_RESULT_UNHANDLED,
            PanZoomController.INPUT_RESULT_HANDLED,
            PanZoomController.INPUT_RESULT_HANDLED_CONTENT ->
                scroll.overscroll and PanZoomController.OVERSCROLL_FLAG_HORIZONTAL != 0
            else -> false
        }
    }

    /**
     * A touchpad's two-finger scroll starting: Android reports it as one pretend finger on a
     * mouse source, pressed without a button. A pinch starts the same way; Android 14 and later
     * classify it, and an older one shows it with a second finger, which hands the gesture back.
     */
    fun isTwoFingerScrollStart(down: MotionEvent): Boolean {
        if (!TouchpadInputFilter.isTouchpadFinger(down)) return false
        if (down.buttonState != 0 || down.pointerCount != 1) return false
        return !(Build.VERSION.SDK_INT >= 34 && down.classification == MotionEvent.CLASSIFICATION_PINCH)
    }
}

/**
 * Turns a touchpad's two-finger sideways swipe on a page into Back or Forward, as Chrome does:
 * only when the page has nothing to scroll that way (see [SwipeRules.pageLeavesSwipe]) and the
 * tab has somewhere to go. The swipe's press goes to GeckoView with
 * `onTouchEventForDetailResult`, which says how the page handles it, possibly a few moves later;
 * until it is known, and once the page keeps the gesture, every event goes on to the page as
 * before. When the swipe is taken, the page gets a cancel and nothing more of that gesture, and
 * [feedback] shows the arrow. Up-and-down scrolling, pinches, clicks and the touchscreen never
 * reach the swipe logic.
 *
 * One per page view, after [TouchpadInputFilter] (so a resting pair of fingers has already been
 * held back). Main thread only.
 */
class TouchpadSwipeNavigator(
    private val density: Float,
    private val touchSlopPx: Float,
    /** Hands the press to the page and calls back with how it was handled (later, on this thread). */
    private val sendDown: (down: MotionEvent, result: (PageScroll) -> Unit) -> Unit,
    private val feedback: SwipeFeedback? = null,
    /** A tick when letting go would navigate. */
    private val haptic: () -> Unit = {},
    private val isSwipeStart: (MotionEvent) -> Boolean = SwipeRules::isTwoFingerScrollStart
) {
    /** Where swipes go; null leaves every gesture to the page. */
    var navigation: SwipeNavigation? = null

    private enum class Mode {
        /** Not a swipe: everything goes to the page. */
        OFF,

        /** Could become a swipe: still going to the page while the fingers and the page decide. */
        UNDECIDED,

        /** Decided for the page: everything goes to it until the fingers lift. */
        PAGE,

        /** Taken: nothing goes to the page; the arrow follows the fingers. */
        SWIPING
    }

    private var mode = Mode.OFF
    private var gesture = 0
    private var downX = 0f
    private var downY = 0f
    private var pageScroll: PageScroll? = null
    private var direction = SwipeDirection.BACK
    private var takenAtPx = 0f
    private var armed = false
    private var velocity: VelocityTracker? = null

    /** Takes [event] as the page view would and gives [forward] what the page should still see. */
    fun handle(event: MotionEvent, forward: (MotionEvent) -> Unit) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A swipe whose lift never came is called off
            if (mode == Mode.SWIPING) feedback?.end(navigated = false)
            release()
            val nav = navigation
            if (nav != null && nav.enabled && isSwipeStart(event)) {
                mode = Mode.UNDECIDED
                downX = event.x
                downY = event.y
                val id = ++gesture
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                sendDown(event) { scroll -> if (id == gesture) pageScroll = scroll }
            } else {
                forward(event)
            }
            return
        }

        when (mode) {
            Mode.OFF, Mode.PAGE -> forward(event)
            Mode.UNDECIDED -> undecided(event, forward)
            Mode.SWIPING -> swiping(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun undecided(event: MotionEvent, forward: (MotionEvent) -> Unit) {
        velocity?.addMovement(event)
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            // A second finger (a pinch) makes it the page's; a lift before anything was decided
            // was the page's all along
            mode = Mode.PAGE
            forward(event)
            return
        }
        val dx = event.x - downX
        val dy = event.y - downY
        val across = abs(dx)
        val upDown = abs(dy)
        when {
            upDown > touchSlopPx && across < SwipeRules.HORIZONTAL_RATIO * upDown -> {
                mode = Mode.PAGE
                forward(event)
            }
            across >= SwipeRules.START_DP * density && across >= SwipeRules.HORIZONTAL_RATIO * upDown -> {
                val towards = SwipeRules.directionOf(dx)
                val scroll = pageScroll
                val nav = navigation
                when {
                    // Not known yet: the page has the moves until it is
                    scroll == null -> forward(event)
                    nav == null || !SwipeRules.pageLeavesSwipe(scroll, towards) || !nav.canGo(towards) -> {
                        mode = Mode.PAGE
                        forward(event)
                    }
                    else -> takeOver(event, towards, across, forward)
                }
            }
            else -> forward(event)
        }
    }

    private fun takeOver(event: MotionEvent, towards: SwipeDirection, across: Float, forward: (MotionEvent) -> Unit) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            forward(cancel)
        } finally {
            cancel.recycle()
        }
        mode = Mode.SWIPING
        direction = towards
        // Counted from here, so a swipe the page decided on late still starts at no progress
        takenAtPx = across
        armed = false
        feedback?.update(towards, 0f, armed = false)
    }

    private fun swiping(event: MotionEvent) {
        velocity?.addMovement(event)
        val sign = if (direction == SwipeDirection.BACK) 1f else -1f
        val travel = (event.x - downX) * sign
        val progress = ((travel - takenAtPx) / ((SwipeRules.ARM_DP - SwipeRules.START_DP) * density))
            .coerceAtLeast(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val nowArmed = progress >= 1f
                if (nowArmed && !armed) haptic()
                armed = nowArmed
                feedback?.update(direction, progress, armed)
            }
            MotionEvent.ACTION_UP -> {
                val speed = velocity?.run {
                    computeCurrentVelocity(1000)
                    xVelocity * sign
                } ?: 0f
                val flung = speed >= SwipeRules.FLING_DP_PER_SECOND * density &&
                    progress >= SwipeRules.FLING_MIN_PROGRESS
                val go = progress >= 1f || flung
                // Ended first, so the arrow is on its way out when the page changes
                feedback?.end(navigated = go)
                if (go) navigation?.go(direction)
            }
            MotionEvent.ACTION_CANCEL -> feedback?.end(navigated = false)
            // A finger added or lifted: still this swipe
            else -> Unit
        }
    }

    /** Calls off a swipe in progress (the page went away): the arrow leaves, nothing navigates. */
    fun cancel() {
        if (mode == Mode.SWIPING) feedback?.end(navigated = false)
        release()
    }

    private fun release() {
        mode = Mode.OFF
        pageScroll = null
        armed = false
        velocity?.recycle()
        velocity = null
    }
}
