package com.rembrowser.rembrowse.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.PageScroll
import com.rembrowser.rembrowse.engine.SwipeDirection
import com.rembrowser.rembrowse.engine.SwipeNavigation
import com.rembrowser.rembrowse.engine.TouchpadInputFilter
import com.rembrowser.rembrowse.engine.TouchpadSwipeNavigator
import com.rembrowser.rembrowse.fakes.TouchpadGestures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.PanZoomController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * A dialog is a window of its own; its touchpad clicks must reach it as a mouse too, and its
 * two-finger scrolls must reach Compose whole. The dialog's window filter is the main window's
 * (MainActivity's), set up the same way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TouchpadWindowsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aTouchpadClickInADialogArrivesAsAMouseClick() {
        val pressedBy = mutableListOf<PointerType>()
        rule.setContent {
            Dialog(onDismissRequest = {}) {
                TouchpadAsMouseInDialog()
                Box(
                    Modifier
                        .size(200.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture { pressedBy += awaitFirstDown().type }
                        }
                )
            }
        }
        rule.waitForIdle()
        val window = ShadowDialog.getLatestDialog().window!!.decorView
        val (x, y) = IntArray(2).also { window.getLocationOnScreen(it) }.let { 0f + window.width / 2 to 0f + window.height / 2 }

        val time = SystemClock.uptimeMillis()
        fun send(action: Int, buttons: Int) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
            val event = MotionEvent.obtain(time, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0)
            rule.runOnUiThread { window.dispatchTouchEvent(event) }
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
        send(MotionEvent.ACTION_UP, 0)
        rule.waitForIdle()

        assertEquals(listOf(PointerType.Mouse), pressedBy)
    }

    private val gestures = TouchpadGestures()

    @After
    fun recycleGestures() = gestures.recycle()

    /** The dialog's window, and where it is on the screen (Compose reads a classified gesture's cursor there). */
    private fun dialogWindow(): Pair<View, Pair<Float, Float>> {
        val window = ShadowDialog.getLatestDialog().window!!.decorView
        val location = IntArray(2).also { window.getLocationOnScreen(it) }
        return window to (location[0].toFloat() to location[1].toFloat())
    }

    /** Hands [events] to the dialog's window as Android does, through its window callback. */
    private fun send(window: View, events: List<MotionEvent>) {
        for (event in events) rule.runOnUiThread { window.dispatchTouchEvent(event) }
        rule.waitForIdle()
    }

    // Android 14+ classifies a touchpad's two-finger scroll, and Compose 1.13 scrolls a list by
    // each move's own distance. The window's filter used to hold the first moves back until the
    // fingers had gone 0.1 inch and then drop them: every scroll lost its start.
    @Test
    fun aClassifiedTwoFingerScrollInADialog_scrollsItsListFromTheFirstMove() {
        val scroll = ScrollState(0)
        rule.setContent {
            Dialog(onDismissRequest = {}) {
                TouchpadAsMouseInDialog()
                Column(Modifier.size(300.dp).verticalScroll(scroll)) {
                    Spacer(Modifier.height(3000.dp))
                }
            }
        }
        rule.waitForIdle()
        val (window, screen) = dialogWindow()

        // Eight moves of 4 px (mdpi: the window's scroll slop is 16 px), the fingers resting
        // before they lift, so no fling adds to the scroll
        val x = window.width / 2f
        val y = window.height / 2f
        val swipe = gestures.swipe(
            x, y,
            steps = List(8) { 0f to -4f },
            rawOffsetX = screen.first, rawOffsetY = screen.second,
            lift = false
        )
        val lift = gestures.event(
            MotionEvent.ACTION_UP, x, y - 32f, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
            swipe.last().eventTime + 500,
            rawOffsetX = screen.first, rawOffsetY = screen.second
        )
        send(window, swipe + lift)

        assertEquals("Every move scrolled, the first four included", 32, scroll.value)
    }

    // The page's own filter and the swipe navigator still see the gesture as before: a sideways
    // two-finger swipe over a page that leaves it goes back once. The view is wired as
    // PaneGeckoView.onTouchEvent wires a page (a GeckoView needs an open session, and with that
    // Gecko's native engine, to take a touch at all).
    @Test
    fun aSidewaysSwipeOverAPage_inAWindowThatPassesItOn_goesBackOnce() {
        val navigation = CountingNavigation()
        lateinit var page: SwipingPageView
        rule.setContent {
            Dialog(onDismissRequest = {}) {
                TouchpadAsMouseInDialog()
                AndroidView(
                    factory = { SwipingPageView(it, navigation).also { view -> page = view } },
                    modifier = Modifier.size(400.dp)
                )
            }
        }
        rule.waitForIdle()
        val (window, screen) = dialogWindow()

        send(
            window,
            gestures.swipe(
                window.width / 2f - 100f, window.height / 2f,
                steps = List(12) { 12f to 0f },
                rawOffsetX = screen.first, rawOffsetY = screen.second
            )
        )

        assertEquals(listOf(SwipeDirection.BACK), navigation.went)
        assertEquals(
            "The page had the press, held until the fingers moved, then a cancel when the swipe was taken",
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
            page.got
        )
    }

    private class CountingNavigation : SwipeNavigation {
        override val enabled = true
        val went = mutableListOf<SwipeDirection>()
        override fun canGo(direction: SwipeDirection) = true
        override fun go(direction: SwipeDirection) {
            went += direction
        }
    }

    /** A page that leaves sideways swipes alone, behind the filters PaneGeckoView puts before its page. */
    private class SwipingPageView(context: Context, navigation: SwipeNavigation) : View(context) {
        /** The actions the page received, its swipe press included. */
        val got = mutableListOf<Int>()

        private val touchpad = TouchpadInputFilter(
            TouchpadInputFilter.scrollSlopPx(
                dpi = resources.displayMetrics.let { maxOf(it.densityDpi.toFloat(), it.xdpi, it.ydpi) },
                touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
            )
        )
        private val swipe = TouchpadSwipeNavigator(
            density = resources.displayMetrics.density,
            touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            sendDown = { down, result ->
                got += down.actionMasked
                result(PageScroll(PanZoomController.INPUT_RESULT_UNHANDLED, 0, PanZoomController.OVERSCROLL_FLAG_HORIZONTAL))
            }
        ).also { it.navigation = navigation }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touchpad.filter(event) { filtered -> swipe.handle(filtered) { got += it.actionMasked } }
            return true
        }
    }
}
