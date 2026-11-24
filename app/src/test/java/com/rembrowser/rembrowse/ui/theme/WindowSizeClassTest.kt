package com.rembrowser.rembrowse.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Material 3's window size classes, at their edges, as RemBrowse names them. */
class WindowSizeClassTest {

    @Test
    fun widthClasses_changeAtMaterialsBreakpoints() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.of(0.dp))
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.of(360.dp))
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.of(599.9.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.of(600.dp))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.of(839.9.dp))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.of(840.dp))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.of(1199.9.dp))
        assertEquals(WindowWidthClass.Large, WindowWidthClass.of(1200.dp))
        assertEquals(WindowWidthClass.Large, WindowWidthClass.of(1280.dp))
    }

    @Test
    fun compactWidth_isStillBelow600dp_asTheLayoutHadIt() {
        // The phone layout (no rail, the tab counter) has switched here since before the classes had names
        assertEquals(600.dp, RemDimens.MediumWidth)
    }

    @Test
    fun compactHeight_isBelow480dp() {
        assertTrue("A phone held sideways", isCompactHeight(360.dp))
        assertTrue(isCompactHeight(479.9.dp))
        assertFalse(isCompactHeight(480.dp))
        assertFalse(isCompactHeight(800.dp))
    }
}
