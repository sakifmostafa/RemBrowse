package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing what a screenshot takes: dragged rectangles, clicks on regions, and the size label. */
class ScreenCaptureGeometryTest {

    private val window = PxRect(0, 0, 1280, 800)

    @Test
    fun aDrag_selectsTheSameRectangle_whicheverWayItGoes() {
        val expected = PxRect(100, 50, 400, 300)
        assertEquals(expected, CaptureGeometry.selection(100f, 50f, 400f, 300f, window))
        assertEquals("Up and to the left", expected, CaptureGeometry.selection(400f, 300f, 100f, 50f, window))
        assertEquals("Down and to the left", expected, CaptureGeometry.selection(400f, 50f, 100f, 300f, window))
        assertEquals("Up and to the right", expected, CaptureGeometry.selection(100f, 300f, 400f, 50f, window))
    }

    @Test
    fun edgesRoundToTheNearestPixel_soTheLabelNamesExactlyWhatIsTaken() {
        val rect = CaptureGeometry.selection(10.4f, 20.6f, 110.5f, 70.49f, window)
        assertEquals(PxRect(10, 21, 111, 70), rect)
        assertEquals(101, rect.width)
        assertEquals(49, rect.height)
        assertEquals("101 × 49", CaptureGeometry.sizeLabel(rect))
        assertEquals("101 by 49 pixels", CaptureGeometry.sizeDescription(rect))
    }

    @Test
    fun aDragPastTheWindowsEdge_stopsAtIt() {
        assertEquals(PxRect(0, 0, 300, 200), CaptureGeometry.selection(300f, 200f, -50f, -80f, window))
        assertEquals(PxRect(1000, 600, 1280, 800), CaptureGeometry.selection(1000f, 600f, 1500f, 1200f, window))
        // A window that doesn't start at the screen's corner (a desktop window's caption above it)
        val framed = PxRect(0, 40, 1280, 840)
        assertEquals(PxRect(0, 40, 200, 100), CaptureGeometry.selection(-10f, 10f, 200f, 100f, framed))
    }

    @Test
    fun aPress_becomesADrag_afterTheThreshold_whichIsSmallerForAMouse() {
        val density = 2f
        val mouse = CaptureGeometry.dragThresholdPx(isMouse = true, touchSlopPx = 16f, density = density)
        val finger = CaptureGeometry.dragThresholdPx(isMouse = false, touchSlopPx = 16f, density = density)
        assertEquals("4dp at 2x", 8f, mouse)
        assertEquals("The system's touch slop", 16f, finger)
        assertEquals(12f, CaptureGeometry.dpToPx(6f, density))

        assertFalse("A wobble is still a click", CaptureGeometry.isDrag(100f, 100f, 105f, 105f, mouse))
        assertTrue(CaptureGeometry.isDrag(100f, 100f, 108f, 106f, mouse))
        assertFalse(CaptureGeometry.isDrag(100f, 100f, 108f, 106f, finger))
    }

    @Test
    fun tinySelections_takeNothing() {
        assertTrue(CaptureGeometry.isBigEnough(PxRect(0, 0, 8, 8), density = 1f))
        assertFalse("A line", CaptureGeometry.isBigEnough(PxRect(0, 0, 300, 2), density = 1f))
        assertFalse("8dp is 16px at 2x", CaptureGeometry.isBigEnough(PxRect(0, 0, 15, 40), density = 2f))
    }

    @Test
    fun aClick_takesTheTopmostRegionUnderIt_orTheWholeWindowOverTheChrome() {
        val left = CaptureRegion("pane:a", PxRect(56, 52, 640, 794))
        val right = CaptureRegion("pane:b", PxRect(648, 52, 1274, 794))
        val switcher = CaptureRegion("tab_switcher", PxRect(56, 52, 1274, 794), layer = 1)

        assertEquals(left.bounds, CaptureGeometry.clickTarget(300f, 400f, listOf(left, right), window))
        assertEquals(right.bounds, CaptureGeometry.clickTarget(900f, 400f, listOf(left, right), window))
        assertEquals("The gap between panes, the toolbar or the rail", window, CaptureGeometry.clickTarget(644f, 400f, listOf(left, right), window))
        assertEquals(window, CaptureGeometry.clickTarget(300f, 20f, listOf(left, right), window))
        assertEquals("The tab switcher is on top", switcher.bounds, CaptureGeometry.clickTarget(300f, 400f, listOf(left, switcher, right), window))
        assertEquals(switcher, CaptureGeometry.regionAt(900f, 400f, listOf(switcher, right)))
        assertNull(CaptureGeometry.regionAt(10f, 10f, listOf(left, right)))
    }

    @Test
    fun aRegion_isOnlyTakenWhereItIsInTheWindow() {
        val partlyOff = CaptureRegion("pane:a", PxRect(-20, 52, 640, 900))
        assertEquals(PxRect(0, 52, 640, 800), CaptureGeometry.clickTarget(10f, 400f, listOf(partlyOff), window))
    }

    @Test
    fun enter_takesTheFocusedRegion_orTheWholeWindow() {
        val pane = CaptureRegion("pane:a", PxRect(56, 52, 1274, 794))
        assertEquals(pane.bounds, CaptureGeometry.focusedTarget("pane:a", listOf(pane), window))
        assertEquals(window, CaptureGeometry.focusedTarget("pane:gone", listOf(pane), window))
        assertEquals(window, CaptureGeometry.focusedTarget(null, listOf(pane), window))
    }

    @Test
    fun theSizeLabel_sitsUnderTheSelection_elseAboveIt_elseInside_alwaysInTheWindow() {
        val gap = 8
        // Room below: under the bottom edge, lined up with the right edge
        assertEquals(PxPoint(300, 308), CaptureGeometry.labelPosition(PxRect(100, 100, 400, 300), 100, 24, window, gap))
        // Touching the bottom: above the top edge
        assertEquals(PxPoint(300, 468), CaptureGeometry.labelPosition(PxRect(100, 500, 400, 790), 100, 24, window, gap))
        // The whole window: inside its bottom-right corner
        assertEquals(PxPoint(1172, 768), CaptureGeometry.labelPosition(window, 100, 24, window, gap))
        // Narrower than the label, at the left edge: kept in the window
        assertEquals(PxPoint(8, 58), CaptureGeometry.labelPosition(PxRect(0, 0, 40, 50), 100, 24, window, gap))
    }

    @Test
    fun rectangles_intersectContainAndMove() {
        val a = PxRect(0, 0, 100, 100)
        assertEquals(PxRect(50, 50, 100, 100), a.intersect(PxRect(50, 50, 200, 200)))
        assertNull("Edges touching is no overlap", a.intersect(PxRect(100, 0, 200, 100)))
        assertTrue(a.contains(0f, 0f))
        assertFalse("Right and bottom edges are outside", a.contains(100f, 50f))
        assertEquals(PxRect(10, 20, 110, 120), a.offset(10, 20))
        assertTrue(PxRect(5, 5, 5, 50).isEmpty)
    }
}
