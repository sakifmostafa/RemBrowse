package com.rembrowser.rembrowse.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** How many extension buttons the tab rail shows for its height. */
class RailExtensionSlotsTest {

    @Test
    fun tallRail_showsUpToFour() {
        assertEquals(MAX_RAIL_EXTENSIONS, railExtensionSlots(800.dp))
        assertEquals(MAX_RAIL_EXTENSIONS, railExtensionSlots(450.dp))
        assertEquals(MAX_RAIL_EXTENSIONS, railExtensionSlots(Dp.Infinity))
    }

    @Test
    fun shorterRail_givesUpButtonsBeforeTheTabsLoseTheirRoom() {
        assertEquals(3, railExtensionSlots(400.dp))
        // A phone in landscape
        assertEquals(2, railExtensionSlots(360.dp))
    }

    @Test
    fun veryShortRail_keepsOneButtonSoEveryExtensionStaysReachable() {
        // A landscape window with the keyboard up
        assertEquals(1, railExtensionSlots(250.dp))
        assertEquals(1, railExtensionSlots(0.dp))
    }
}
