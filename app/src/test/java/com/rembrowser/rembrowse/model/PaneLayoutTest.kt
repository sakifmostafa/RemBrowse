package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.model.PaneNode.Leaf
import com.rembrowser.rembrowse.model.PaneNode.Split
import com.rembrowser.rembrowse.model.SplitOrientation.COLUMN
import com.rembrowser.rembrowse.model.SplitOrientation.ROW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneLayoutTest {

    private val sideBySide: PaneNode = Split(ROW, 0.5f, Leaf("reddit"), Leaf("youtube"))

    /** Reddit at full height on the left; YouTube above a third tab on the right. */
    private val oneAndTwo: PaneNode = Split(ROW, 0.5f, Leaf("reddit"), Split(COLUMN, 0.5f, Leaf("youtube"), Leaf("news")))

    @Test
    fun split_onAnEdgeDividesOnlyThatPane() {
        assertEquals(oneAndTwo, sideBySide.split("youtube", "news", DropZone.BOTTOM))
        assertEquals(
            Split(ROW, 0.5f, Split(COLUMN, 0.5f, Leaf("news"), Leaf("reddit")), Leaf("youtube")),
            sideBySide.split("reddit", "news", DropZone.TOP)
        )
        assertEquals(
            Split(ROW, 0.5f, Leaf("reddit"), Split(ROW, 0.5f, Leaf("news"), Leaf("youtube"))),
            sideBySide.split("youtube", "news", DropZone.LEFT)
        )
        assertEquals(listOf("a", "b"), Leaf("a").split("a", "b", DropZone.RIGHT).tabIds)
    }

    @Test
    fun split_leavesTheLayoutAloneWhenItCannotBeDone() {
        assertSame("The centre is not an edge", sideBySide, sideBySide.split("youtube", "news", DropZone.CENTER))
        assertSame("The target is not on screen", sideBySide, sideBySide.split("gone", "news", DropZone.LEFT))
        assertSame("A tab is shown once only", sideBySide, sideBySide.split("youtube", "reddit", DropZone.LEFT))
    }

    @Test
    fun split_stopsAtFourPanes() {
        val four = oneAndTwo.split("reddit", "mail", DropZone.BOTTOM)
        assertEquals(4, four.paneCount)
        assertEquals(PaneNode.MAX_PANES, four.paneCount)

        assertSame(four, four.split("mail", "fifth", DropZone.RIGHT))
    }

    @Test
    fun remove_collapsesTheSplitAndTheNeighbourTakesTheRoom() {
        assertEquals(sideBySide, oneAndTwo.remove("news"))
        assertEquals(Split(COLUMN, 0.5f, Leaf("youtube"), Leaf("news")), oneAndTwo.remove("reddit"))
        assertEquals(Leaf("reddit"), sideBySide.remove("youtube"))
        assertNull("The last pane cannot be closed", Leaf("reddit").remove("reddit"))
        assertEquals("A tab that is not on screen changes nothing", oneAndTwo, oneAndTwo.remove("elsewhere"))
    }

    @Test
    fun replaceAndSwap_moveTabsBetweenPanesWithoutChangingTheShape() {
        assertEquals(listOf("reddit", "mail", "news"), oneAndTwo.replaceTab("youtube", "mail").tabIds)
        val swapped = oneAndTwo.swapTabs("reddit", "news")
        assertEquals(listOf("news", "youtube", "reddit"), swapped.tabIds)
        assertEquals(oneAndTwo.splits(), swapped.splits())
    }

    @Test
    fun paneOrderAndSplitPaths_followTheTree() {
        assertEquals(listOf("reddit", "youtube", "news"), oneAndTwo.tabIds)
        assertEquals(3, oneAndTwo.paneCount)
        assertTrue(oneAndTwo.contains("news"))
        assertEquals(listOf(SplitRef("", ROW, 0.5f), SplitRef("1", COLUMN, 0.5f)), oneAndTwo.splits())
        assertEquals("1", oneAndTwo.firstSplit(COLUMN)?.path)
        assertNull(sideBySide.firstSplit(COLUMN))
    }

    @Test
    fun ratios_areSetPerSplitAndClamped() {
        val resized = oneAndTwo.updateRatio("1") { it + 0.2f }
        assertEquals(listOf(0.5f, 0.7f), resized.splits().map { it.ratio })

        val tooFar = resized.updateRatio("") { it + 5f }.updateRatio("1") { it - 5f }
        assertEquals(listOf(SplitRatios.MAX_RATIO, SplitRatios.MIN_RATIO), tooFar.splits().map { it.ratio })

        assertEquals("A path that leads nowhere changes nothing", oneAndTwo, oneAndTwo.updateRatio("00") { 0.8f })
        assertEquals(listOf(0.5f, 0.5f), tooFar.withAllRatios(0.5f).splits().map { it.ratio })
        assertEquals(0.5f, PaneNode.clampRatio(Float.NaN), 0f)
    }

    @Test
    fun sanitized_dropsWhatCannotBeShown() {
        val valid = setOf("reddit", "youtube", "news")
        assertEquals(oneAndTwo, oneAndTwo.sanitized(valid))
        assertEquals("A closed tab's pane collapses", sideBySide, oneAndTwo.sanitized(setOf("reddit", "youtube")))
        assertNull(oneAndTwo.sanitized(emptySet()))

        val twice = Split(ROW, 0.5f, Leaf("reddit"), Leaf("reddit"))
        assertEquals("A tab is shown once", Leaf("reddit"), twice.sanitized(valid))

        val wild = Split(ROW, 7f, Leaf("reddit"), Leaf("youtube"))
        assertEquals(SplitRatios.MAX_RATIO, (wild.sanitized(valid) as Split).ratio, 0f)

        val six = Split(ROW, 0.5f, Split(COLUMN, 0.5f, Leaf("a"), Split(ROW, 0.5f, Leaf("b"), Leaf("c"))), Split(COLUMN, 0.5f, Leaf("d"), Split(ROW, 0.5f, Leaf("e"), Leaf("f"))))
        assertEquals(listOf("a", "b", "c", "d"), six.sanitized(setOf("a", "b", "c", "d", "e", "f"))!!.tabIds)
    }

    @Test
    fun presets_buildTheFixedLayoutsAndAreRecognisedAgain() {
        val ids = listOf("a", "b", "c", "d")
        val ratios = SplitRatios(horizontal = 0.3f, vertical = 0.7f)

        assertEquals(Leaf("a"), PaneNode.preset(LayoutMode.SINGLE_1X1, ids, ratios))
        assertEquals(Split(ROW, 0.3f, Leaf("a"), Leaf("b")), PaneNode.preset(LayoutMode.SPLIT_1X2, ids, ratios))
        assertEquals(Split(COLUMN, 0.7f, Leaf("a"), Leaf("b")), PaneNode.preset(LayoutMode.SPLIT_2X1, ids, ratios))
        val grid = PaneNode.preset(LayoutMode.GRID_2X2, ids, ratios)
        assertEquals("Reading order: top row, then bottom row", ids, grid.tabIds)

        LayoutMode.entries.forEach { mode ->
            assertEquals(mode, PaneNode.preset(mode, ids, ratios).matchingPreset())
        }
        assertNull("Three panes are no preset", oneAndTwo.matchingPreset())
    }

    @Test
    fun geometry_placesPanesAndDividersWithAGapBetweenThem() {
        val geometry = oneAndTwo.updateRatio("") { 0.25f }.geometry(width = 1008f, height = 608f, gap = 8f)

        assertEquals(
            listOf(
                PanePlacement("reddit", PaneRect(0f, 0f, 250f, 608f)),
                PanePlacement("youtube", PaneRect(258f, 0f, 750f, 300f)),
                PanePlacement("news", PaneRect(258f, 308f, 750f, 300f))
            ),
            geometry.panes
        )
        assertEquals(
            listOf(
                DividerPlacement("", ROW, PaneRect(250f, 0f, 8f, 608f), extent = 1000f),
                // The inner divider only spans the column it divides
                DividerPlacement("1", COLUMN, PaneRect(258f, 300f, 750f, 8f), extent = 600f)
            ),
            geometry.dividers
        )
    }

    @Test
    fun dropTarget_edgesSplitAndTheCentreReplaces() {
        val geometry = sideBySide.geometry(width = 1008f, height = 600f, gap = 8f)
        // The right pane spans x 508..1008

        fun zoneAt(x: Float, y: Float) = geometry.dropTargetAt(x, y, draggedTabId = "news")

        assertEquals(PaneDropTarget(1, DropZone.CENTER, PaneRect(508f, 0f, 500f, 600f)), zoneAt(758f, 300f))
        assertEquals(PaneDropTarget(1, DropZone.BOTTOM, PaneRect(508f, 300f, 500f, 300f)), zoneAt(758f, 560f))
        assertEquals(PaneDropTarget(1, DropZone.TOP, PaneRect(508f, 0f, 500f, 300f)), zoneAt(758f, 30f))
        assertEquals(PaneDropTarget(1, DropZone.RIGHT, PaneRect(758f, 0f, 250f, 600f)), zoneAt(990f, 300f))
        assertEquals(PaneDropTarget(1, DropZone.LEFT, PaneRect(508f, 0f, 250f, 600f)), zoneAt(530f, 300f))
        assertEquals(0, zoneAt(100f, 300f)?.paneIndex)

        assertNull("Outside the panes", zoneAt(2000f, 300f))
        assertNull("In the gap", zoneAt(504f, 300f))
        assertEquals("The gap counts for a neighbour when asked to", DropZone.LEFT, geometry.dropTargetAt(504.5f, 300f, "news", slack = 8f)?.zone)
        assertNull("A tab cannot be dropped on itself", geometry.dropTargetAt(758f, 300f, draggedTabId = "youtube"))
    }

    @Test
    fun dropTarget_offersOnlyTheCentreWhenTheLayoutIsFull() {
        val full = PaneNode.preset(LayoutMode.GRID_2X2, listOf("a", "b", "c", "d")).geometry(1008f, 608f, 8f)

        // Near the left edge of the top-left pane: an edge zone anywhere else
        assertEquals(DropZone.CENTER, full.dropTargetAt(10f, 150f, draggedTabId = "new")?.zone)
        // A tab that is already on screen frees its own pane by moving, so it may still split
        assertEquals(DropZone.LEFT, full.dropTargetAt(10f, 150f, draggedTabId = "d")?.zone)

        val three = oneAndTwo.geometry(1008f, 608f, 8f)
        assertEquals(DropZone.LEFT, three.dropTargetAt(10f, 300f, draggedTabId = "new")?.zone)
    }
}
