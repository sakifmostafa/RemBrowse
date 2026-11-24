package com.rembrowser.rembrowse

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.ui.BrowserChromeState
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * MainActivity's own key and touch hooks (dispatchKeyEvent, dispatchTouchEvent,
 * dispatchGenericMotionEvent), on the activity itself: TerminalKeysUiTest and
 * WindowTouchpadUiTest send events through BrowserKeys and WindowTouchpadInput as the activity
 * does, and these fail if the activity stops handing its events to them.
 *
 * The activity is made and attached to its window but never created: onCreate starts the engine,
 * which needs Gecko's native code. Its keys are given a browser with a fake engine.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityInputTest {

    private val activity = Robolectric.buildActivity(MainActivity::class.java).get()

    @Test
    fun theActivitysKeys_goToTheBrowsersShortcutsFirst() {
        val browser = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
        activity.useKeys(BrowserKeys(browser, BrowserChromeState()))
        val tabs = browser.uiState.value.tabs.size

        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ctrlT = KeyEvent(
                time, time, action, KeyEvent.KEYCODE_T, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            assertTrue("Taken by the browser", activity.dispatchKeyEvent(ctrlT))
        }

        assertEquals("Ctrl+T opened a tab", tabs + 1, browser.uiState.value.tabs.size)
    }

    @Test
    fun theActivitysTouches_goThroughTheTouchpadFilter() {
        // What reaches the window's views after the activity's hooks: each event's action and
        // its pointer's tool type
        val touches = mutableListOf<Pair<Int, Int>>()
        val hovers = mutableListOf<Pair<Int, Int>>()
        val decor = activity.window.decorView
        decor.setOnTouchListener { _, event ->
            touches += event.actionMasked to event.getToolType(0)
            true
        }
        decor.setOnHoverListener { _, event ->
            hovers += event.actionMasked to event.getToolType(0)
            true
        }

        // A touchpad click arrives as a finger on a mouse source, with its button: the window
        // gets a mouse click
        activity.dispatchTouchEvent(touchpad(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY))
        activity.dispatchTouchEvent(touchpad(MotionEvent.ACTION_UP, 0))
        // The cursor moving over the window: a mouse's hover
        activity.dispatchGenericMotionEvent(touchpad(MotionEvent.ACTION_HOVER_MOVE, 0))
        // Fingers resting on the pad (no button, not moving, unclassified): never a press
        activity.dispatchTouchEvent(touchpad(MotionEvent.ACTION_DOWN, 0))
        activity.dispatchTouchEvent(touchpad(MotionEvent.ACTION_UP, 0))

        val mouse = MotionEvent.TOOL_TYPE_MOUSE
        assertEquals(listOf(MotionEvent.ACTION_DOWN to mouse, MotionEvent.ACTION_UP to mouse), touches)
        // (the window's view makes a hover enter of the first move itself)
        assertEquals(MotionEvent.ACTION_HOVER_MOVE to mouse, hovers.last())
        assertTrue("A mouse throughout: $hovers", hovers.all { it.second == mouse })
    }

    /** A touchpad's event as Android 14 and later report it: a finger on a mouse source. */
    private fun touchpad(action: Int, buttons: Int): MotionEvent {
        val time = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 1f })
        return MotionEvent.obtain(
            time, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0
        )
    }
}
