package com.rembrowser.rembrowse.model

/** How the two halves of a split sit: [ROW] side by side, [COLUMN] one above the other. */
enum class SplitOrientation { ROW, COLUMN }

/** Where on a pane a dragged tab is dropped: an edge splits the pane, the centre replaces its tab. */
enum class DropZone { CENTER, LEFT, RIGHT, TOP, BOTTOM }

/**
 * The arrangement of panes in the content area, as a small tree: a [Leaf] is one pane showing a
 * tab, a [Split] divides its area between two sub-layouts. "One full-height pane beside two
 * stacked ones" is `Split(ROW, Leaf, Split(COLUMN, Leaf, Leaf))`.
 *
 * Panes are numbered in reading order of the tree (first before second, depth first), and a tab
 * is shown in at most one pane. A split is addressed by its [path][SplitRef.path] from the root.
 * The tree is immutable; every change returns a new one.
 */
sealed interface PaneNode {

    data class Leaf(val tabId: String) : PaneNode

    /** [ratio] is the share of the split (less the gap) that [first] gets. */
    data class Split(
        val orientation: SplitOrientation,
        val ratio: Float,
        val first: PaneNode,
        val second: PaneNode
    ) : PaneNode

    /** The tabs on screen, in pane order. */
    val tabIds: List<String>
        get() = when (this) {
            is Leaf -> listOf(tabId)
            is Split -> first.tabIds + second.tabIds
        }

    val paneCount: Int
        get() = when (this) {
            is Leaf -> 1
            is Split -> first.paneCount + second.paneCount
        }

    fun contains(tabId: String): Boolean = when (this) {
        is Leaf -> this.tabId == tabId
        is Split -> first.contains(tabId) || second.contains(tabId)
    }

    /** Shows [newTabId] in the pane that showed [oldTabId]. */
    fun replaceTab(oldTabId: String, newTabId: String): PaneNode = mapTabs { if (it == oldTabId) newTabId else it }

    /** Exchanges the panes of two visible tabs. */
    fun swapTabs(a: String, b: String): PaneNode = mapTabs {
        when (it) {
            a -> b
            b -> a
            else -> it
        }
    }

    /**
     * Splits the pane showing [targetTabId] and puts [newTabId] on its [zone] side. Returns the
     * layout unchanged when that is not possible: the layout is full, the target is not on
     * screen, the new tab already is, or [zone] is the centre.
     */
    fun split(targetTabId: String, newTabId: String, zone: DropZone): PaneNode {
        if (zone == DropZone.CENTER || paneCount >= MAX_PANES) return this
        if (!contains(targetTabId) || contains(newTabId)) return this
        return splitLeaf(targetTabId, newTabId, zone)
    }

    /**
     * Closes the pane showing [tabId]: its split collapses and the neighbour takes the room.
     * Null when it was the only pane.
     */
    fun remove(tabId: String): PaneNode? = prune { it != tabId }

    /** Every split, outermost first. */
    fun splits(): List<SplitRef> = buildList { collectSplits("", this) }

    /** The first split in [orientation], which is the one the Split menu's sizes speak of. */
    fun firstSplit(orientation: SplitOrientation): SplitRef? = splits().firstOrNull { it.orientation == orientation }

    /** Changes the ratio of the split at [path]; the result is kept within the allowed range. */
    fun updateRatio(path: String, change: (Float) -> Float): PaneNode = when (this) {
        is Leaf -> this
        is Split -> when {
            path.isEmpty() -> copy(ratio = clampRatio(change(ratio)))
            path[0] == '0' -> copy(first = first.updateRatio(path.substring(1), change))
            else -> copy(second = second.updateRatio(path.substring(1), change))
        }
    }

    fun withAllRatios(ratio: Float): PaneNode = when (this) {
        is Leaf -> this
        is Split -> Split(orientation, clampRatio(ratio), first.withAllRatios(ratio), second.withAllRatios(ratio))
    }

    /**
     * A layout safe to show: panes whose tab is not in [validTabIds] (or is shown twice) are
     * closed, at most [MAX_PANES] remain and ratios are within range. Null when nothing is left.
     */
    fun sanitized(validTabIds: Set<String>): PaneNode? {
        val seen = mutableSetOf<String>()
        return prune { it in validTabIds && seen.size < MAX_PANES && seen.add(it) }
    }

    /** The preset this layout has the shape of, or null for any other arrangement. */
    fun matchingPreset(): LayoutMode? = when {
        this is Leaf -> LayoutMode.SINGLE_1X1
        this is Split && first is Leaf && second is Leaf ->
            if (orientation == SplitOrientation.ROW) LayoutMode.SPLIT_1X2 else LayoutMode.SPLIT_2X1
        this is Split && orientation == SplitOrientation.COLUMN && first.isLeafPair(SplitOrientation.ROW) &&
            second.isLeafPair(SplitOrientation.ROW) -> LayoutMode.GRID_2X2
        else -> null
    }

    /**
     * Where every pane and divider goes in an area of [width] x [height], with [gap] between
     * panes. Units are whatever the caller passes in (dp or pixels).
     */
    fun geometry(width: Float, height: Float, gap: Float): PaneGeometry {
        val panes = mutableListOf<PanePlacement>()
        val dividers = mutableListOf<DividerPlacement>()
        place(PaneRect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f)), gap, "", panes, dividers)
        return PaneGeometry(panes, dividers)
    }

    companion object {
        /** More panes than this are of no use, even on a large window. */
        const val MAX_PANES = 4

        fun clampRatio(ratio: Float): Float =
            if (ratio.isNaN()) 0.5f else ratio.coerceIn(SplitRatios.MIN_RATIO, SplitRatios.MAX_RATIO)

        /**
         * One of the fixed layouts, showing [tabIds] in reading order. [tabIds] must hold at
         * least as many tabs as [mode] has panes.
         */
        fun preset(mode: LayoutMode, tabIds: List<String>, ratios: SplitRatios = SplitRatios.DEFAULT): PaneNode {
            val h = clampRatio(ratios.horizontal)
            val v = clampRatio(ratios.vertical)
            fun leaf(index: Int) = Leaf(tabIds[index])
            return when (mode) {
                LayoutMode.SINGLE_1X1 -> leaf(0)
                LayoutMode.SPLIT_1X2 -> Split(SplitOrientation.ROW, h, leaf(0), leaf(1))
                LayoutMode.SPLIT_2X1 -> Split(SplitOrientation.COLUMN, v, leaf(0), leaf(1))
                LayoutMode.GRID_2X2 -> Split(
                    SplitOrientation.COLUMN,
                    v,
                    Split(SplitOrientation.ROW, h, leaf(0), leaf(1)),
                    Split(SplitOrientation.ROW, h, leaf(2), leaf(3))
                )
            }
        }
    }
}

/** A split's address ("" is the root, then '0' for first and '1' for second at each level). */
data class SplitRef(val path: String, val orientation: SplitOrientation, val ratio: Float)

data class PaneRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** How far ([x], [y]) is outside this rectangle; zero when inside. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return maxOf(dx, dy)
    }
}

data class PanePlacement(val tabId: String, val rect: PaneRect)

/**
 * The gap of the split at [path]. [extent] is the length its ratio divides, so dragging the
 * divider by `d` changes the ratio by `d / extent`.
 */
data class DividerPlacement(
    val path: String,
    val orientation: SplitOrientation,
    val rect: PaneRect,
    val extent: Float
)

/** Where a dragged tab would land: on pane [paneIndex], filling [highlight] of the area. */
data class PaneDropTarget(val paneIndex: Int, val zone: DropZone, val highlight: PaneRect)

data class PaneGeometry(val panes: List<PanePlacement>, val dividers: List<DividerPlacement>) {

    /**
     * What dropping the tab [draggedTabId] at ([x], [y]) would do, or null when that is not on
     * a pane (or is the tab's own pane). A point in the gap between panes, up to [slack] away,
     * counts for the nearest pane.
     *
     * The outer quarter of a pane on each side is an edge zone, the middle is the centre. A full
     * layout offers the centre only, unless the dragged tab is itself on screen: moving it frees
     * its pane first.
     */
    fun dropTargetAt(x: Float, y: Float, draggedTabId: String, slack: Float = 0f): PaneDropTarget? {
        val index = panes.indices.minByOrNull { panes[it].rect.distanceTo(x, y) } ?: return null
        val pane = panes[index]
        val rect = pane.rect
        if (rect.distanceTo(x, y) > slack || pane.tabId == draggedTabId) return null
        if (rect.width <= 0f || rect.height <= 0f) return null

        val panesAfterMove = panes.size - if (panes.any { it.tabId == draggedTabId }) 1 else 0
        val zone = if (panesAfterMove >= PaneNode.MAX_PANES) {
            DropZone.CENTER
        } else {
            zoneAt(((x - rect.left) / rect.width).coerceIn(0f, 1f), ((y - rect.top) / rect.height).coerceIn(0f, 1f))
        }
        val halfWidth = rect.width / 2f
        val halfHeight = rect.height / 2f
        val highlight = when (zone) {
            DropZone.CENTER -> rect
            DropZone.LEFT -> rect.copy(width = halfWidth)
            DropZone.RIGHT -> rect.copy(left = rect.left + halfWidth, width = halfWidth)
            DropZone.TOP -> rect.copy(height = halfHeight)
            DropZone.BOTTOM -> rect.copy(top = rect.top + halfHeight, height = halfHeight)
        }
        return PaneDropTarget(index, zone, highlight)
    }

    private fun zoneAt(fx: Float, fy: Float): DropZone {
        val centre = EDGE_FRACTION..(1f - EDGE_FRACTION)
        if (fx in centre && fy in centre) return DropZone.CENTER
        return listOf(
            DropZone.LEFT to fx,
            DropZone.RIGHT to 1f - fx,
            DropZone.TOP to fy,
            DropZone.BOTTOM to 1f - fy
        ).minBy { it.second }.first
    }

    private companion object {
        const val EDGE_FRACTION = 0.25f
    }
}

private fun PaneNode.mapTabs(transform: (String) -> String): PaneNode = when (this) {
    is PaneNode.Leaf -> PaneNode.Leaf(transform(tabId))
    is PaneNode.Split -> copy(first = first.mapTabs(transform), second = second.mapTabs(transform))
}

private fun PaneNode.isLeafPair(orientation: SplitOrientation): Boolean =
    this is PaneNode.Split && this.orientation == orientation && first is PaneNode.Leaf && second is PaneNode.Leaf

private fun PaneNode.splitLeaf(targetTabId: String, newTabId: String, zone: DropZone): PaneNode = when (this) {
    is PaneNode.Leaf -> if (tabId != targetTabId) {
        this
    } else {
        val orientation = if (zone == DropZone.LEFT || zone == DropZone.RIGHT) {
            SplitOrientation.ROW
        } else {
            SplitOrientation.COLUMN
        }
        val added = PaneNode.Leaf(newTabId)
        val addedFirst = zone == DropZone.LEFT || zone == DropZone.TOP
        PaneNode.Split(orientation, 0.5f, if (addedFirst) added else this, if (addedFirst) this else added)
    }
    is PaneNode.Split -> copy(
        first = first.splitLeaf(targetTabId, newTabId, zone),
        second = second.splitLeaf(targetTabId, newTabId, zone)
    )
}

/** Keeps the panes whose tab passes [keep], asked in pane order; emptied splits collapse. */
private fun PaneNode.prune(keep: (String) -> Boolean): PaneNode? = when (this) {
    is PaneNode.Leaf -> if (keep(tabId)) this else null
    is PaneNode.Split -> {
        val keptFirst = first.prune(keep)
        val keptSecond = second.prune(keep)
        when {
            keptFirst == null -> keptSecond
            keptSecond == null -> keptFirst
            else -> PaneNode.Split(orientation, PaneNode.clampRatio(ratio), keptFirst, keptSecond)
        }
    }
}

private fun PaneNode.collectSplits(path: String, into: MutableList<SplitRef>) {
    if (this !is PaneNode.Split) return
    into += SplitRef(path, orientation, ratio)
    first.collectSplits(path + "0", into)
    second.collectSplits(path + "1", into)
}

private fun PaneNode.place(
    area: PaneRect,
    gap: Float,
    path: String,
    panes: MutableList<PanePlacement>,
    dividers: MutableList<DividerPlacement>
) {
    when (this) {
        is PaneNode.Leaf -> panes += PanePlacement(tabId, area)
        is PaneNode.Split -> if (orientation == SplitOrientation.ROW) {
            val extent = (area.width - gap).coerceAtLeast(0f)
            val firstWidth = extent * ratio
            dividers += DividerPlacement(path, orientation, area.copy(left = area.left + firstWidth, width = gap), extent)
            first.place(area.copy(width = firstWidth), gap, path + "0", panes, dividers)
            second.place(
                area.copy(left = area.left + firstWidth + gap, width = extent - firstWidth),
                gap,
                path + "1",
                panes,
                dividers
            )
        } else {
            val extent = (area.height - gap).coerceAtLeast(0f)
            val firstHeight = extent * ratio
            dividers += DividerPlacement(path, orientation, area.copy(top = area.top + firstHeight, height = gap), extent)
            first.place(area.copy(height = firstHeight), gap, path + "0", panes, dividers)
            second.place(
                area.copy(top = area.top + firstHeight + gap, height = extent - firstHeight),
                gap,
                path + "1",
                panes,
                dividers
            )
        }
    }
}
