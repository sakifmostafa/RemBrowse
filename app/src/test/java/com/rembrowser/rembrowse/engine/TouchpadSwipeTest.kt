package com.rembrowser.rembrowse.engine

import android.app.Application
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.PanZoomController
import org.robolectric.annotation.Config

private const val HANDLED = PanZoomController.INPUT_RESULT_HANDLED
private const val HANDLED_CONTENT = PanZoomController.INPUT_RESULT_HANDLED_CONTENT
private const val UNHANDLED = PanZoomController.INPUT_RESULT_UNHANDLED
private const val IGNORED = PanZoomController.INPUT_RESULT_IGNORED
private const val LEFT = PanZoomController.SCROLLABLE_FLAG_LEFT
private const val RIGHT = PanZoomController.SCROLLABLE_FLAG_RIGHT
private const val OVERSCROLL = PanZoomController.OVERSCROLL_FLAG_HORIZONTAL

/** Events for the rules and the navigator: a touchpad is a mouse source with a finger tool. */
private fun pointerEvent(
    action: Int,
    x: Float,
    y: Float,
    time: Long = 0L,
    source: Int = InputDevice.SOURCE_MOUSE,
    toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    buttons: Int = 0,
    secondFinger: Boolean = false
): MotionEvent {
    val count = if (secondFinger) 2 else 1
    val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; this.toolType = toolType } }
    val coords = Array(count) { i ->
        MotionEvent.PointerCoords().apply { this.x = x + i * 20f; this.y = y; pressure = 1f }
    }
    return MotionEvent.obtain(0L, time, action, count, properties, coords, 0, buttons, 1f, 1f, 7, 0, source, 0)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SwipeRulesTest {

    private val made = mutableListOf<MotionEvent>()

    @After
    fun recycle() = made.forEach { it.recycle() }

    private fun event(
        source: Int = InputDevice.SOURCE_MOUSE,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        buttons: Int = 0,
        secondFinger: Boolean = false
    ) = pointerEvent(MotionEvent.ACTION_DOWN, 10f, 10f, 0L, source, toolType, buttons, secondFinger).also { made += it }

    private fun leaves(handled: Int, scrollable: Int = 0, overscroll: Int = 0, direction: SwipeDirection) =
        SwipeRules.pageLeavesSwipe(PageScroll(handled, scrollable, overscroll), direction)

    @Test
    fun directionOf_rightIsBack_leftIsForward() {
        assertEquals(SwipeDirection.BACK, SwipeRules.directionOf(5f))
        assertEquals(SwipeDirection.FORWARD, SwipeRules.directionOf(-5f))
        assertEquals("No movement is not a rightward swipe", SwipeDirection.FORWARD, SwipeRules.directionOf(0f))
    }

    @Test
    fun anUnhandledTouch_leavesTheSwipe_whenItMayOverscroll_unlessTheSideFlagIsSet() {
        assertTrue(leaves(UNHANDLED, overscroll = OVERSCROLL, direction = SwipeDirection.BACK))
        assertTrue(leaves(UNHANDLED, overscroll = OVERSCROLL, direction = SwipeDirection.FORWARD))
        assertTrue("The flag of the other side doesn't matter", leaves(UNHANDLED, scrollable = RIGHT, overscroll = OVERSCROLL, direction = SwipeDirection.BACK))
        assertTrue(leaves(UNHANDLED, scrollable = LEFT, overscroll = OVERSCROLL, direction = SwipeDirection.FORWARD))
        assertFalse(leaves(UNHANDLED, scrollable = LEFT, overscroll = OVERSCROLL, direction = SwipeDirection.BACK))
        assertFalse(leaves(UNHANDLED, scrollable = RIGHT, overscroll = OVERSCROLL, direction = SwipeDirection.FORWARD))
        // A page that doesn't scroll but forbids overscrolling (overscroll-behavior-x: none), or
        // a result GeckoView makes up before the page is attached, keeps the swipe
        assertFalse(leaves(UNHANDLED, direction = SwipeDirection.BACK))
        assertFalse(leaves(UNHANDLED, direction = SwipeDirection.FORWARD))
    }

    @Test
    fun aHandledTouch_leavesTheSwipeOnlyWithHorizontalOverscroll() {
        for (handled in listOf(HANDLED, HANDLED_CONTENT)) {
            for (direction in SwipeDirection.entries) {
                assertTrue("handled=$handled", leaves(handled, overscroll = OVERSCROLL, direction = direction))
                assertFalse("No overscroll: contain or none", leaves(handled, overscroll = 0, direction = direction))
            }
        }
    }

    @Test
    fun aHandledTouch_thatCanStillScrollThatWay_keepsTheSwipe_evenWithOverscroll() {
        for (handled in listOf(HANDLED, HANDLED_CONTENT)) {
            assertFalse(leaves(handled, scrollable = LEFT, overscroll = OVERSCROLL, direction = SwipeDirection.BACK))
            assertFalse(leaves(handled, scrollable = RIGHT, overscroll = OVERSCROLL, direction = SwipeDirection.FORWARD))
            assertTrue(leaves(handled, scrollable = RIGHT, overscroll = OVERSCROLL, direction = SwipeDirection.BACK))
            assertTrue(leaves(handled, scrollable = LEFT, overscroll = OVERSCROLL, direction = SwipeDirection.FORWARD))
        }
    }

    @Test
    fun anIgnoredOrUnknownResult_keepsTheSwipe() {
        for (direction in SwipeDirection.entries) {
            assertFalse(leaves(IGNORED, overscroll = OVERSCROLL, direction = direction))
            assertFalse(leaves(-1, overscroll = OVERSCROLL, direction = direction))
        }
    }

    @Test
    fun aTouchpadFingerWithNoButton_startsATwoFingerScroll() {
        assertTrue(SwipeRules.isTwoFingerScrollStart(event()))
    }

    @Test
    fun aButtonPress_aTouchscreen_andARealMouse_doNot() {
        assertFalse("A click on the touchpad", SwipeRules.isTwoFingerScrollStart(event(buttons = MotionEvent.BUTTON_PRIMARY)))
        assertFalse("A touchscreen finger", SwipeRules.isTwoFingerScrollStart(event(source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertFalse("A real mouse pointer", SwipeRules.isTwoFingerScrollStart(event(toolType = MotionEvent.TOOL_TYPE_MOUSE)))
        assertFalse("Two pointers", SwipeRules.isTwoFingerScrollStart(event(secondFinger = true)))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TouchpadSwipeNavigatorTest {

    private class FakeNavigation : SwipeNavigation {
        override var enabled = true
        override var reverseDirection = false
        val can = mutableMapOf(SwipeDirection.BACK to true, SwipeDirection.FORWARD to true)
        val went = mutableListOf<SwipeDirection>()
        override fun canGo(direction: SwipeDirection) = can.getValue(direction)
        override fun go(direction: SwipeDirection) {
            went += direction
        }
    }

    private class RecordingFeedback : SwipeFeedback {
        val calls = mutableListOf<String>()
        var lastProgress = -1f
        override fun update(direction: SwipeDirection, progress: Float, armed: Boolean) {
            lastProgress = progress
            calls += "update($direction,$progress,$armed)"
        }
        override fun end(navigated: Boolean) {
            calls += "end($navigated)"
        }
    }

    private val navigation = FakeNavigation()
    private val feedback = RecordingFeedback()
    private var haptics = 0
    private var swipeStart = true
    private var fingersMove = true
    private val downs = mutableListOf<(PageScroll) -> Unit>()

    /** The actions that reached the page, in order. */
    private val forwarded = mutableListOf<Int>()
    private val made = mutableListOf<MotionEvent>()

    private val navigator = TouchpadSwipeNavigator(
        density = 1f,
        touchSlopPx = 8f,
        sendDown = { _, result -> downs += result },
        feedback = feedback,
        haptic = { haptics++ },
        isSwipeStart = { swipeStart },
        fingersMoveWithPage = { fingersMove }
    ).also { it.navigation = navigation }

    @After
    fun recycle() = made.forEach { it.recycle() }

    private fun feed(action: Int, x: Number, y: Number = 100, time: Long = 0L, secondFinger: Boolean = false) {
        val event = pointerEvent(action, x.toFloat(), y.toFloat(), time, secondFinger = secondFinger).also { made += it }
        navigator.handle(event) { forwarded += it.action }
    }

    private fun down(x: Number = 100, time: Long = 0L) = feed(MotionEvent.ACTION_DOWN, x, time = time)
    private fun move(x: Number, y: Number = 100, time: Long = 0L) = feed(MotionEvent.ACTION_MOVE, x, y, time)
    private fun up(x: Number, time: Long = 0L) = feed(MotionEvent.ACTION_UP, x, time = time)

    private val pageLeaves = PageScroll(UNHANDLED, 0, OVERSCROLL)

    /** Delivers the page's answer for the latest press. */
    private fun answer(scroll: PageScroll = pageLeaves) = downs.last().invoke(scroll)

    private val cancel = MotionEvent.ACTION_CANCEL
    private val moveAction = MotionEvent.ACTION_MOVE

    @Test
    fun notASwipeStart_navigationNull_orDisabled_forwardEverythingAndNeverAskTheEngine() {
        swipeStart = false
        down(); move(140); up(140)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, moveAction, MotionEvent.ACTION_UP), forwarded)

        swipeStart = true
        forwarded.clear()
        navigation.enabled = false
        down(); move(140); up(140)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, moveAction, MotionEvent.ACTION_UP), forwarded)

        forwarded.clear()
        navigation.enabled = true
        navigator.navigation = null
        down(); move(140); up(140)
        assertEquals(3, forwarded.size)
        assertTrue("Never asked how the page handles it", downs.isEmpty())
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun theSwipePress_goesToTheEngineAndNotToThePage() {
        down()
        assertEquals(1, downs.size)
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun aRightSwipePastStart_withAQualifyingResult_cancelsThePageOnceAndTakesOver() {
        down(); answer()
        move(130, time = 10)
        assertEquals("The page gets one cancel", listOf(cancel), forwarded)
        assertEquals(listOf("update(BACK,0.0,false)"), feedback.calls)

        move(150, time = 20); move(160, time = 30)
        assertEquals("Nothing more of that gesture", listOf(cancel), forwarded)
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun movingToTheArmPoint_armsOnce_andLiftingNavigates() {
        down(); answer()
        move(130, time = 100)
        assertEquals(0, haptics)
        move(202, time = 200) // 72 px after being taken: the arm point
        assertEquals(1, haptics)
        assertEquals(1f, feedback.lastProgress, 0.001f)
        assertTrue(feedback.calls.last().endsWith(",true)"))
        move(220, time = 300)
        assertEquals("Armed once", 1, haptics)

        up(220, time = 400)
        assertEquals(listOf("end(true)"), feedback.calls.takeLast(1))
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
        assertEquals(listOf(cancel), forwarded)
    }

    @Test
    fun liftingShortOfTheArmPoint_slowly_doesNotNavigate() {
        down(); answer()
        move(130, time = 100)
        move(150, time = 600)
        up(150, time = 1100)
        assertEquals("end(false)", feedback.calls.last())
        assertTrue(navigation.went.isEmpty())
        assertEquals(0, haptics)
    }

    @Test
    fun aFastFlickPastTheFlingProgress_navigatesBeforeTheArmPoint() {
        down(time = 0); answer()
        move(130, time = 10)
        move(160, time = 20) // 30 of 72 px: 0.42, past FLING_MIN_PROGRESS
        up(160, time = 25)
        assertEquals("end(true)", feedback.calls.last())
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
        assertEquals("Never armed", 0, haptics)
    }

    @Test
    fun aFastFlickThatHasNotGoneFarEnough_doesNotNavigate() {
        down(time = 0); answer()
        move(130, time = 10)
        move(140, time = 15) // 10 of 72 px: under FLING_MIN_PROGRESS
        up(140, time = 18)
        assertEquals("end(false)", feedback.calls.last())
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun verticalMovementFirst_leavesTheWholeGestureToThePage() {
        down(); answer()
        move(105, y = 120, time = 10)
        move(200, y = 120, time = 20) // sideways now, but the page already has it
        up(200, time = 30)
        assertEquals(listOf(moveAction, moveAction, MotionEvent.ACTION_UP), forwarded)
        assertTrue(feedback.calls.isEmpty())
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun aResultNotKnownAtTheStartPoint_leavesMovesWithThePage_thenTheNextMoveAfterItTakesOver() {
        down()
        move(130, time = 10)
        assertEquals("Still the page's while unknown", listOf(moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())

        answer()
        move(160, time = 20)
        assertEquals(listOf(moveAction, cancel), forwarded)
        assertEquals("Progress counts from where it was taken", listOf("update(BACK,0.0,false)"), feedback.calls)

        move(232, time = 30) // 72 px after being taken at 60
        assertEquals(1f, feedback.lastProgress, 0.001f)
        assertEquals(1, haptics)
    }

    @Test
    fun aPageThatCanScrollThatWay_keepsTheSwipe() {
        down(); answer(PageScroll(HANDLED, LEFT, OVERSCROLL))
        move(130, time = 10); move(200, time = 20)
        assertEquals(listOf(moveAction, moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun handledContentWithoutHorizontalOverscroll_keepsTheSwipe() {
        down(); answer(PageScroll(HANDLED_CONTENT, 0, 0))
        move(130, time = 10); up(130, time = 20)
        assertEquals(listOf(moveAction, MotionEvent.ACTION_UP), forwarded)
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun noWayToGoThatDirection_keepsTheSwipeWithThePage() {
        navigation.can[SwipeDirection.BACK] = false
        down(); answer()
        move(130, time = 10); move(220, time = 20)
        assertEquals(listOf(moveAction, moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun aLeftSwipe_goesForward() {
        down(x = 200); answer()
        move(170, time = 100)
        assertEquals(listOf(cancel), forwarded)
        assertEquals("update(FORWARD,0.0,false)", feedback.calls.last())
        move(98, time = 200) // 72 px after being taken
        assertEquals(1, haptics)
        up(98, time = 300)
        assertEquals(listOf(SwipeDirection.FORWARD), navigation.went)
    }

    @Test
    fun aLeftSwipe_withNowhereToGoForward_staysWithThePage() {
        navigation.can[SwipeDirection.FORWARD] = false
        down(x = 200); answer()
        move(170, time = 10)
        assertEquals(listOf(moveAction), forwarded)
    }

    @Test
    fun aResultFromAPreviousGesture_isIgnored() {
        down()
        val stale = downs.last()
        up(100, time = 10) // lifted before the page answered
        down(time = 50)
        stale(pageLeaves)
        move(130, time = 60)
        assertEquals("The new gesture's answer is still unknown", listOf(MotionEvent.ACTION_UP, moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())

        answer()
        move(160, time = 70)
        assertEquals(cancel, forwarded.last())
    }

    @Test
    fun aSecondFingerWhileUndecided_handsTheGestureToThePage() {
        down(); answer()
        feed(MotionEvent.ACTION_POINTER_DOWN, 105f, time = 5, secondFinger = true)
        move(200, time = 20)
        assertEquals(listOf(MotionEvent.ACTION_POINTER_DOWN, moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun aNewPressDuringASwipe_callsTheSwipeOff() {
        down(); answer()
        move(130, time = 10)
        assertEquals(listOf("update(BACK,0.0,false)"), feedback.calls)

        down(time = 500)
        assertEquals("end(false)", feedback.calls.last())
        assertEquals(2, downs.size)
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun aCancelDuringASwipe_endsItWithoutNavigating() {
        down(); answer()
        move(130, time = 10)
        feed(MotionEvent.ACTION_CANCEL, 130f, time = 20)
        assertEquals("end(false)", feedback.calls.last())
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun cancellingFromOutside_duringASwipe_endsItOnce_andNothingNavigates() {
        down(); answer()
        move(130, time = 10)
        val before = feedback.calls.size
        navigator.cancel()
        assertEquals("end(false)", feedback.calls.last())
        assertEquals(before + 1, feedback.calls.size)
        // A second cancel, or one with no swipe going, reports nothing more
        navigator.cancel()
        assertEquals(before + 1, feedback.calls.size)
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun withReverseScrolling_aLeftMovingPretendFinger_goesBack_armsAndNavigates() {
        fingersMove = false
        navigation.can[SwipeDirection.FORWARD] = false // only Back is possible, and it is the one asked for
        down(x = 200); answer()
        move(170, time = 100)
        assertEquals(listOf(cancel), forwarded)
        assertEquals("update(BACK,0.0,false)", feedback.calls.last())
        move(98, time = 200) // 72 px after being taken
        assertEquals(1, haptics)
        assertEquals(1f, feedback.lastProgress, 0.001f)
        up(98, time = 300)
        assertEquals("end(true)", feedback.calls.last())
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
    }

    @Test
    fun withReverseScrolling_aRightMovingPretendFinger_goesForward() {
        fingersMove = false
        down(); answer()
        move(130, time = 100)
        assertEquals("update(FORWARD,0.0,false)", feedback.calls.last())
        move(202, time = 200)
        up(202, time = 300)
        assertEquals(listOf(SwipeDirection.FORWARD), navigation.went)
    }

    @Test
    fun withReverseScrolling_thePageIsAskedInThePretendFingersTerms() {
        fingersMove = false
        // Pretend finger moves left: the page would scroll right, so a page that can scroll RIGHT keeps it
        down(x = 200); answer(PageScroll(HANDLED, RIGHT, OVERSCROLL))
        move(170, time = 10)
        assertEquals(listOf(moveAction), forwarded)
        assertTrue(feedback.calls.isEmpty())
    }

    @Test
    fun withReverseScrolling_aPageThatCanScrollTheOtherSide_stillLeavesTheSwipe() {
        fingersMove = false
        down(x = 200); answer(PageScroll(HANDLED, LEFT, OVERSCROLL))
        move(170, time = 10)
        assertEquals(listOf(cancel), forwarded)
        assertEquals("update(BACK,0.0,false)", feedback.calls.last())
    }

    @Test
    fun withReverseScrolling_aFastFlickLeft_navigatesBack() {
        fingersMove = false
        down(x = 200, time = 0); answer()
        move(170, time = 10)
        move(140, time = 20) // 30 of 72 px, fast
        up(140, time = 25)
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
        assertEquals(0, haptics)
    }

    @Test
    fun reverseDirection_flipsRightToForward_andLeftToBack() {
        navigation.reverseDirection = true
        down(); answer()
        move(130, time = 100)
        assertEquals("update(FORWARD,0.0,false)", feedback.calls.last())
        move(202, time = 200)
        assertEquals(1, haptics)
        up(202, time = 300)
        assertEquals(listOf(SwipeDirection.FORWARD), navigation.went)

        navigation.went.clear()
        feedback.calls.clear()
        down(x = 200, time = 1000); answer()
        move(170, time = 1100)
        assertEquals("update(BACK,0.0,false)", feedback.calls.last())
        move(98, time = 1200)
        up(98, time = 1300)
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
    }

    @Test
    fun reverseDirection_asksThePageInThePretendFingersTerms_andCanGoInTheFlippedDirection() {
        navigation.reverseDirection = true
        // Moving right: the page check is for BACK (blocked by a left-scrollable page)...
        down(); answer(PageScroll(HANDLED, LEFT, OVERSCROLL))
        move(130, time = 10)
        assertEquals(listOf(moveAction), forwarded)

        // ...and where it goes is FORWARD, which must be possible
        forwarded.clear()
        navigation.can[SwipeDirection.FORWARD] = false
        down(time = 100); answer()
        move(130, time = 110)
        assertEquals(listOf(moveAction), forwarded)
        assertTrue(navigation.went.isEmpty())
    }

    @Test
    fun reverseScrollingAndReverseDirectionTogether_cancelOut() {
        fingersMove = false
        navigation.reverseDirection = true
        down(); answer()
        move(130, time = 100)
        assertEquals("update(BACK,0.0,false)", feedback.calls.last())
        move(202, time = 200)
        up(202, time = 300)
        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
    }

    @Test
    fun theDirectionIsFixedAtThePress_notWhenTheFingersMove() {
        fingersMove = false
        down(x = 200); answer()
        fingersMove = true
        navigation.reverseDirection = true
        move(170, time = 10)
        assertEquals("update(BACK,0.0,false)", feedback.calls.last())
    }
}
