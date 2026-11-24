package com.rembrowser.rembrowse.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** A dialog is a window of its own; its touchpad clicks must reach it as a mouse too. */
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
}
