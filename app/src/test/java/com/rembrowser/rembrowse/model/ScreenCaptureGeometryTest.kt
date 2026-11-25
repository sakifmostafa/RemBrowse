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

    @Test
    fun aWholePageWithinTheBudget_isTakenAtTheScreensDensity() {
        val budget = CaptureGeometry.WHOLE_PAGE_MAX_PIXELS
        assertEquals(64_000_000L, budget)
        assertEquals(
            "A long article on a laptop",
            WholePagePlan.Take(2560, 20_000, reduced = false),
            CaptureGeometry.wholePagePlan(1280, 10_000, 2f, budget)
        )
        assertEquals(
            "Exactly the budget",
            WholePagePlan.Take(2000, 32_000, reduced = false),
            CaptureGeometry.wholePagePlan(2000, 32_000, 1f, budget)
        )
        assertEquals(
            "The longer side as the engine rounds it, the shorter one rounded up to hold the page",
            WholePagePlan.Take(1082, 2100, reduced = false),
            CaptureGeometry.wholePagePlan(412, 800, 2.625f, budget)
        )
        assertEquals(
            "A page zoomed out is smaller than its CSS size",
            WholePagePlan.Take(490, 5000, reduced = false),
            CaptureGeometry.wholePagePlan(980, 10_000, 0.5f, budget)
        )
    }

    @Test
    fun aWholePageOverTheBudget_isTakenSmaller_withItsShapeKept() {
        // 30,000 CSS px tall at three pixels each: 1218 by 90,000, 110 megapixels
        val plan = CaptureGeometry.wholePagePlan(406, 30_000, 3f, 64_000_000L) as WholePagePlan.Take
        assertTrue(plan.reduced)
        assertTrue("Within the budget", plan.width.toLong() * plan.height <= 64_000_000L)
        assertTrue("Nearly all of the budget", plan.width.toLong() * plan.height > 63_000_000L)
        assertEquals("The page's shape", 90_000.0 / 1218, plan.height.toDouble() / plan.width, 0.1)
        assertFillsThePicture(406, 30_000, plan)
        assertTrue("At least a pixel for each CSS pixel", plan.width >= 406 && plan.height >= 30_000)

        // The same page on a device with little memory to spare
        val small = CaptureGeometry.wholePagePlan(406, 30_000, 3f, 20_000_000L) as WholePagePlan.Take
        assertTrue(small.reduced)
        assertTrue(small.width.toLong() * small.height <= 20_000_000L)
        assertTrue(small.width >= 406 && small.height >= 30_000)
        assertFillsThePicture(406, 30_000, small)

        // A wide page: its width is the longer side
        val wide = CaptureGeometry.wholePagePlan(40_000, 700, 3f, 64_000_000L) as WholePagePlan.Take
        assertTrue(wide.reduced)
        assertTrue(wide.width.toLong() * wide.height <= 64_000_000L)
        assertFillsThePicture(40_000, 700, wide)

        // One row over the budget
        val barely = CaptureGeometry.wholePagePlan(1000, 16_001, 2f, 64_000_000L) as WholePagePlan.Take
        assertTrue(barely.reduced)
        assertTrue(barely.width.toLong() * barely.height <= 64_000_000L)
        assertEquals(2000f, barely.width.toFloat(), 2f)
    }

    /**
     * The engine draws the page at the smaller of the two ratios of picture to page: drawn so,
     * a [cssWidth] by [cssHeight] page covers [plan]'s picture to within a pixel each way.
     */
    private fun assertFillsThePicture(cssWidth: Int, cssHeight: Int, plan: WholePagePlan.Take) {
        val scale = minOf(plan.width.toDouble() / cssWidth, plan.height.toDouble() / cssHeight)
        assertEquals("Blank columns", plan.width.toDouble(), cssWidth * scale, 1.0)
        assertEquals("Blank rows", plan.height.toDouble(), cssHeight * scale, 1.0)
    }

    @Test
    fun aWholePageAtFullSize_fillsItsPictureAtAnyDensity() {
        for (ratio in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
            for ((width, height) in listOf(412 to 800, 412 to 12_000, 427 to 5000, 1280 to 700, 3000 to 900)) {
                val plan = CaptureGeometry.wholePagePlan(width, height, ratio, 64_000_000L) as WholePagePlan.Take
                assertFalse("$width by $height at $ratio", plan.reduced)
                assertFillsThePicture(width, height, plan)
                assertEquals("$width by $height at $ratio", width * ratio, plan.width.toFloat(), 1f)
                assertEquals("$width by $height at $ratio", height * ratio, plan.height.toFloat(), 1f)
            }
        }
    }

    @Test
    fun aWholePageTooLongEvenAtOnePixelForEachCssPixel_isRefused() {
        assertEquals(
            "Exactly the budget at its CSS size: taken at that size",
            WholePagePlan.Take(1000, 64_000, reduced = true),
            CaptureGeometry.wholePagePlan(1000, 64_000, 2f, 64_000_000L)
        )
        assertEquals(WholePagePlan.TooLong, CaptureGeometry.wholePagePlan(1000, 64_001, 2f, 64_000_000L))
        assertEquals(
            "An ordinary monitor has nothing to reduce",
            WholePagePlan.TooLong,
            CaptureGeometry.wholePagePlan(2000, 32_001, 1f, 64_000_000L)
        )
        assertEquals(
            "As large as the engine lays a page out, without overflowing",
            WholePagePlan.TooLong,
            CaptureGeometry.wholePagePlan(17_895_697, 17_895_697, 4f, 64_000_000L)
        )
    }

    @Test
    fun aWholePageOnlyTheFreeMemoryRulesOut_isNotCalledTooLong() {
        assertEquals(
            "The 30,000 px page with little memory to spare",
            WholePagePlan.LowMemory,
            CaptureGeometry.wholePagePlan(406, 30_000, 3f, 12_000_000L)
        )
        // An ordinary article on a laptop, with memory for no more than the least ever allowed
        val least = CaptureGeometry.WHOLE_PAGE_MIN_BUDGET_PIXELS
        assertEquals(WholePagePlan.LowMemory, CaptureGeometry.wholePagePlan(1280, 5000, 2f, least))
        assertEquals(WholePagePlan.LowMemory, CaptureGeometry.wholePagePlan(1280, 5000, 1f, least))
        assertEquals(
            "Still taken smaller where that is enough",
            WholePagePlan.Take(1306, 3060, reduced = true),
            CaptureGeometry.wholePagePlan(1280, 3000, 2f, least)
        )
        assertEquals(
            "The longest page any device takes",
            WholePagePlan.LowMemory,
            CaptureGeometry.wholePagePlan(1000, 64_000, 2f, least)
        )
        assertEquals(
            "Too long whatever memory is free",
            WholePagePlan.TooLong,
            CaptureGeometry.wholePagePlan(1000, 64_001, 2f, least)
        )
        assertEquals(
            "Zoomed out, its size on screen is what counts",
            WholePagePlan.LowMemory,
            CaptureGeometry.wholePagePlan(20_000, 10_000, 0.5f, least)
        )
    }

    @Test
    fun theRegionOfAWholePage_isTheFewestPixelsTheEngineCountsAsAllOfIt() {
        assertEquals(2560, CaptureGeometry.wholePageSource(1280, 2f))
        assertEquals(30_000, CaptureGeometry.wholePageSource(30_000, 1f))
        // 412 CSS px at 2.625 is 1081.5 px: 1081 would come back as 411, a column short
        assertEquals(1082, CaptureGeometry.wholePageSource(412, 2.625f))
        assertEquals(411, (1081 / 2.625f).toInt())
        assertEquals(412, (1082 / 2.625f).toInt())
        assertEquals(490, CaptureGeometry.wholePageSource(980, 0.5f))
        // Zoomed out, an odd side has no exact region: the next one up, never less than the page
        assertEquals(491, CaptureGeometry.wholePageSource(981, 0.5f))
        assertEquals(1, CaptureGeometry.wholePageSource(1, 0.25f))
        // A side too large to work out ends at once instead of counting past Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, CaptureGeometry.wholePageSource(Int.MAX_VALUE / 2, 3f))
        assertEquals(Int.MAX_VALUE, CaptureGeometry.wholePageSource(1000, Float.NaN))
        for (ratio in listOf(1.25f, 1.5f, 2.625f, 2.75f, 3f, 3.5f)) {
            for (css in listOf(1, 319, 412, 427, 980, 1281, 30_001, 250_000)) {
                val source = CaptureGeometry.wholePageSource(css, ratio)
                assertEquals("$css at $ratio", css, (source / ratio).toInt())
                assertTrue("$css at $ratio: one fewer is short", ((source - 1) / ratio).toInt() < css)
            }
        }
    }

    @Test
    fun noPage_isNoWholePage() {
        assertNull(CaptureGeometry.wholePagePlan(0, 500, 2f, 64_000_000L))
        assertNull(CaptureGeometry.wholePagePlan(500, 0, 2f, 64_000_000L))
        assertNull(CaptureGeometry.wholePagePlan(500, 500, 0f, 64_000_000L))
        assertNull(CaptureGeometry.wholePagePlan(500, 500, Float.NaN, 64_000_000L))
        assertNull(CaptureGeometry.wholePagePlan(500, 500, Float.POSITIVE_INFINITY, 64_000_000L))
        assertNull("Too small to be a pixel", CaptureGeometry.wholePagePlan(1, 500, 0.5f, 64_000_000L))
    }

    @Test
    fun theWholePageBudget_followsTheMemoryTheDeviceCanSpare() {
        val perPixel = CaptureGeometry.WHOLE_PAGE_BYTES_PER_PIXEL
        val megabyte = 1024L * 1024
        assertEquals(
            "What is free above the level where Android closes other apps",
            (800 - 200) * megabyte / perPixel,
            CaptureGeometry.wholePageMaxPixels(800 * megabyte, 200 * megabyte)
        )
        assertEquals(
            "Plenty of memory: the fixed limit",
            CaptureGeometry.WHOLE_PAGE_MAX_PIXELS,
            CaptureGeometry.wholePageMaxPixels(8000 * megabyte, 200 * megabyte)
        )
        assertEquals(
            "Little to spare: still about a screenful",
            CaptureGeometry.WHOLE_PAGE_MIN_BUDGET_PIXELS,
            CaptureGeometry.wholePageMaxPixels(210 * megabyte, 200 * megabyte)
        )
        assertEquals(
            "Already low",
            CaptureGeometry.WHOLE_PAGE_MIN_BUDGET_PIXELS,
            CaptureGeometry.wholePageMaxPixels(150 * megabyte, 200 * megabyte)
        )
        assertTrue(CaptureGeometry.WHOLE_PAGE_MIN_BUDGET_PIXELS < CaptureGeometry.WHOLE_PAGE_MAX_PIXELS)
    }

    @Test
    fun theWholePageKey_takesThePaneUnderTheMouse_elseTheFocusedOne_butOnlyAWebPage() {
        val pages = setOf("pane:a", "pane:b")
        assertEquals("pane:b", CaptureGeometry.wholePageTarget(hoveredKey = "pane:b", focusedKey = "pane:a", pageKeys = pages))
        assertEquals("No mouse over a pane", "pane:a", CaptureGeometry.wholePageTarget(null, "pane:a", pages))
        assertNull("Over a terminal: not the focused page either", CaptureGeometry.wholePageTarget("pane:t", "pane:a", pages))
        assertNull("Over the tab switcher", CaptureGeometry.wholePageTarget("tab_switcher", "pane:a", pages))
        assertNull("The focused pane shows the start page", CaptureGeometry.wholePageTarget(null, "pane:s", pages))
        assertNull(CaptureGeometry.wholePageTarget(null, null, pages))
    }

    @Test
    fun theNoticesPicture_showsTheTopOfAWholePage() {
        // 4:3, as the notice shows it
        val aspect = 4f / 3f
        assertEquals(PxRect(0, 0, 1600, 1200), CaptureGeometry.topOfPage(1600, 24_000, aspect))
        assertEquals("Rounded to a whole row", PxRect(0, 0, 1081, 811), CaptureGeometry.topOfPage(1081, 30_000, aspect))
        assertEquals("A page shorter than that is shown whole", PxRect(0, 0, 1600, 400), CaptureGeometry.topOfPage(1600, 400, aspect))
    }
}
