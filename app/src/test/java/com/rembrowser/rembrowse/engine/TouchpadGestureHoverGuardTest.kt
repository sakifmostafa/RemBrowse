package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.TouchpadGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * When the layout changes during a touchpad's two-finger scroll, Compose 1.13 sends the scroll's
 * last move again as a hover move (AndroidComposeView.resendMotionEventOnLayout), and its view
 * interop hands an embedded view a copy of that move: at the pretend finger, which has moved with
 * the scroll, not at the cursor. A web view would give the page a mouse move there. The page
 * views below are wired as PaneGeckoView wires its guard; a real GeckoView needs Gecko's native
 * engine to take a touch at all, so PaneGeckoView itself is checked with listeners standing in
 * for the page (paneGeckoView_keepsTheMadeUpMoveFromThePage).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h600dp-mdpi")
class TouchpadGestureHoverGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val gestures = TouchpadGestures()

    @After
    fun recycle() = gestures.recycle()

    /** A page that takes every touch, as a page view with a page does, and notes the hover moves that reach it. */
    @SuppressLint("ViewConstructor")
    private class Page(context: Context, private val guarded: Boolean) : View(context) {
        private val guard = TouchpadGestureHoverGuard()

        /** Hover moves handed to the view, and those that got past it to the page, as "x,y". */
        val handed = mutableListOf<String>()
        val reached = mutableListOf<String>()

        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            super.dispatchTouchEvent(event).also { took -> guard.onTouchEvent(event, took) }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) handed += "${event.x.toInt()},${event.y.toInt()}"
            if (guarded && guard.drops(event)) return true
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) reached += "${event.x.toInt()},${event.y.toInt()}"
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    /**
     * A two-finger scroll over the page, 60 px down from the cursor at (300, 200), with the space
     * above the page growing by 20 px midway: Compose lays out again and resends. Then the lift,
     * and the cursor moving on.
     */
    private fun scrollWithARelayout(guarded: Boolean): Page {
        lateinit var page: Page
        scrollWithARelayout { context -> Page(context, guarded).also { page = it } }
        return page
    }

    /** The same scroll over the page view [pageView] makes. */
    private fun scrollWithARelayout(pageView: (Context) -> View) {
        val above = mutableIntStateOf(100)
        lateinit var root: View
        composeRule.setContent {
            root = LocalView.current
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(above.intValue.dp))
                AndroidView(factory = pageView, modifier = Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()

        fun send(event: MotionEvent) {
            composeRule.runOnIdle {
                if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    root.dispatchGenericMotionEvent(event)
                } else {
                    root.dispatchTouchEvent(event)
                }
            }
        }
        send(gestures.hover(300f, 200f, eventTime = 900L))
        val swipe = gestures.swipe(300f, 200f, steps = List(3) { 0f to 20f })
        swipe.dropLast(1).forEach(::send)
        composeRule.runOnIdle { above.intValue = 120 }
        composeRule.waitForIdle()
        send(swipe.last())
        send(gestures.hover(310f, 200f, eventTime = 1_200L))
        composeRule.waitForIdle()
    }

    @Test
    fun composeResendsAMoveAtThePretendFinger_andThePageNeverGetsIt() {
        val page = scrollWithARelayout(guarded = true)

        // The cursor (300, 200 in the window) is at y 100 in the page before the relayout and 80
        // after it; the pretend finger had gone 60 px further down, to 260: 140 in the page
        assertTrue("Compose 1.13 handed the view its made-up hover move: ${page.handed}", "300,140" in page.handed)
        assertFalse(page.reached.toString(), "300,140" in page.reached)
        assertEquals("Before and after the gesture, the cursor", listOf("300,100", "310,80"), page.reached)
    }

    /**
     * The same scroll over PaneGeckoView itself, through its own dispatchTouchEvent and
     * dispatchGenericMotionEvent, so this fails if either override goes. Gecko's native engine,
     * which takes a page's touches, can't run on the JVM: a touch listener takes them in its place
     * (GeckoView's own dispatch calls it first), and a hover listener, which View's hover dispatch
     * calls before anything else, notes the hover moves that get past PaneGeckoView to the page.
     */
    @Test
    fun paneGeckoView_keepsTheMadeUpMoveFromThePage() {
        val reached = mutableListOf<String>()
        scrollWithARelayout { context ->
            PaneGeckoView(context).apply {
                @SuppressLint("ClickableViewAccessibility")
                setOnTouchListener { _, _ -> true }
                setOnHoverListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) reached += "${event.x.toInt()},${event.y.toInt()}"
                    true
                }
            }
        }

        assertFalse(reached.toString(), "300,140" in reached)
        assertEquals("Before and after the gesture, the cursor", listOf("300,100", "310,80"), reached)
    }

    @Test
    fun withoutTheGuard_thePageGotTheMouseAtThePretendFinger() {
        val page = scrollWithARelayout(guarded = false)

        assertTrue(page.reached.toString(), "300,140" in page.reached)
    }

    @Test
    fun theGuard_dropsHoverOnlyDuringAClassifiedGestureTheViewTook() {
        val guard = TouchpadGestureHoverGuard()
        val hover = gestures.hover(10f, 10f, eventTime = 900L)
        val swipe = gestures.swipe(10f, 10f, steps = listOf(0f to 10f))
        val pinch = gestures.pinch(100f, 10f)
        val wheel = MotionEvent.obtain(900L, 900L, MotionEvent.ACTION_SCROLL, 10f, 10f, 0).apply { source = InputDevice.SOURCE_MOUSE }
        val touchscreenDown = MotionEvent.obtain(900L, 900L, MotionEvent.ACTION_DOWN, 10f, 10f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertFalse(guard.drops(hover))

            guard.onTouchEvent(swipe.first(), took = true)
            assertTrue(guard.drops(hover))
            assertFalse("Not a hover", guard.drops(wheel))
            guard.onTouchEvent(swipe[1], took = true)
            assertTrue(guard.drops(hover))
            guard.onTouchEvent(swipe.last(), took = true)
            assertFalse("Lifted", guard.drops(hover))

            guard.onTouchEvent(pinch.first(), took = true)
            assertTrue(guard.drops(hover))
            guard.onTouchEvent(pinch.last(), took = true)
            assertFalse(guard.drops(hover))

            guard.onTouchEvent(swipe.first(), took = false)
            assertFalse("Declined: the lift will never come", guard.drops(hover))

            guard.onTouchEvent(touchscreenDown, took = true)
            assertFalse("A finger on the screen", guard.drops(hover))
        } finally {
            wheel.recycle()
            touchscreenDown.recycle()
        }
    }
}
