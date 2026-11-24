package com.rembrowser.rembrowse.engine

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Android asks the innermost view under the mouse for the cursor. A web view always has the one
 * its page asked for, so it has to step aside while the browser's chrome has the pointer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PaneGeckoViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val pageCursor = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TEXT)
    private val chromeCursor = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
    private var chromeHasPointer = false

    // The screen, with a pane's web view filling it: the same nesting as in the app
    private val view = PaneGeckoView(context, chromeHasPointer = { chromeHasPointer }).apply { pointerIcon = pageCursor }
    private val screen = FrameLayout(context).apply {
        pointerIcon = chromeCursor
        addView(view, FrameLayout.LayoutParams(400, 300))
        measure(400, 300)
        layout(0, 0, 400, 300)
    }

    private fun cursorAt(x: Float, y: Float): PointerIcon? {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        event.source = InputDevice.SOURCE_MOUSE
        return try {
            screen.onResolvePointerIcon(event, 0)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun thePageChoosesTheCursorOverItsView() {
        assertEquals(pageCursor, cursorAt(200f, 150f))
    }

    @Test
    fun theChromeChoosesTheCursorWhileItHasThePointer() {
        chromeHasPointer = true
        assertNull(view.onResolvePointerIcon(MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, 2f, 150f, 0), 0))
        assertEquals("The screen's cursor shows through", chromeCursor, cursorAt(2f, 150f))

        chromeHasPointer = false
        assertEquals(pageCursor, cursorAt(2f, 150f))
    }

    @Test
    fun enterPastTheKeyboardApp_isLeftToTheKeyboardAppWithoutAPage() {
        // No GeckoSession is attached, so there is no key path to send it through
        assertEquals(false, view.sendKeyPastKeyboardApp(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(false, view.sendKeyPastKeyboardApp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun takesKeysPastTheKeyboardApp_isFalseWithoutAPage() {
        assertEquals(false, view.takesKeysPastKeyboardApp(enter = false))
        assertEquals(false, view.takesKeysPastKeyboardApp(enter = true))
    }
}
