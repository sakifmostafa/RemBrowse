package com.rembrowser.rembrowse.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How tall an anchored [RemDropdownMenu] (the More menu) may be below its button, with a laptop's
 * numbers, and its following the window's visible frame as the on-screen keyboard comes and goes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class MenuRoomBelowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The More menu at 100% with a keyboard attached (so with Keyboard shortcuts): 14 rows of
     * Material 3 Expressive's 44dp, its two dividers and its padding, 690dp as LargeFontMenuUiTest
     * lays it out with the device's text measurement.
     */
    private val moreMenuAt100 = 690.dp

    /** The More button's foot with the toolbar at the top of the window, 50dp down; 4dp to the menu. */
    private val moreButtonFoot = 50.dp
    private val offset = 4.dp

    @Test
    fun aLaptopsFullHeightWindow_leavesTheMoreMenuWhole_withoutScrolling() {
        // A 1280x800dp screen, the window as tall as the screen
        val room = menuRoomBelow(frameHeight = 800.dp, anchorBottom = moreButtonFoot, offsetY = offset)
        assertNotNull(room)
        assertTrue("$room of room for $moreMenuAt100", room!! >= moreMenuAt100)
        // Whole down to a visible frame of 792dp: the button's foot, the offset, Material's 48dp
        // margin and the menu
        assertTrue(menuRoomBelow(792.dp, moreButtonFoot, offset)!! >= moreMenuAt100)
        assertTrue(menuRoomBelow(791.dp, moreButtonFoot, offset)!! < moreMenuAt100)
    }

    @Test
    fun aShorterWindow_capsTheMenuBelowTheButton_whereItScrolls() {
        // A desktop window on such a screen with a 40dp header above the toolbar and a 48dp
        // taskbar under it: the menu keeps below the button, 610dp tall, and scrolls the rest
        val room = menuRoomBelow(frameHeight = 752.dp, anchorBottom = 40.dp + moreButtonFoot, offsetY = offset)
        assertEquals(610.dp, room)
        // Less than three rows of room: left to Material's placement
        assertNull(menuRoomBelow(frameHeight = 245.dp, anchorBottom = moreButtonFoot, offsetY = offset))
        assertEquals(144.dp, menuRoomBelow(frameHeight = 246.dp, anchorBottom = moreButtonFoot, offsetY = offset))
    }

    @Test
    fun theOnScreenKeyboard_takesItsRoom_andGivesItBack() {
        // A landscape tablet, 800dp tall: the keyboard up takes 340dp of the frame
        val keyboardUp = menuRoomBelow(frameHeight = 460.dp, anchorBottom = moreButtonFoot, offsetY = offset)
        val keyboardDown = menuRoomBelow(frameHeight = 800.dp, anchorBottom = moreButtonFoot, offsetY = offset)
        assertEquals(358.dp, keyboardUp)
        assertEquals(698.dp, keyboardDown)
    }

    /** What the window's visible frame reads, in px (mdpi: dp), as the fake reader returns it. */
    private var frame = 800

    private var composeView: View? = null

    private fun setKeyboard(up: Boolean) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (up) 340 else 0))
            .setVisible(WindowInsetsCompat.Type.ime(), up)
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeView!!, insets) }
        composeRule.waitForIdle()
    }

    @Test
    fun theVisibleFrame_isReadAgain_whenTheKeyboardComesAndGoes() {
        var shown = -1
        var reads = 0
        composeRule.setContent {
            composeView = LocalView.current
            shown = rememberVisibleFrameHeight(read = {
                reads++
                frame
            })
            Box {}
        }
        composeRule.waitForIdle()
        assertEquals(800, shown)

        // The keyboard comes up: the frame is read again
        frame = 460
        setKeyboard(up = true)
        assertEquals(460, shown)

        // And goes: the menu gets its room back, rather than keeping the keyboard's
        frame = 800
        setKeyboard(up = false)
        assertEquals(800, shown)
        assertTrue("Read again on each change, $reads reads", reads >= 3)
    }
}
