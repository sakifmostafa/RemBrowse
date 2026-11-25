package com.rembrowser.rembrowse.model

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A rectangle of whole pixels in the window, as a screenshot takes it. [right] and [bottom] are
 * the first column and row outside it, so the size is `right - left` by `bottom - top`.
 */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /** Whether the point (in window pixels) is inside: on the left and top edges counts, on the right and bottom does not. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** The part of this rectangle that is also in [other]; null when they don't overlap. */
    fun intersect(other: PxRect): PxRect? =
        PxRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            .takeUnless { it.isEmpty }

    fun offset(dx: Int, dy: Int): PxRect = PxRect(left + dx, top + dy, right + dx, bottom + dy)
}

/** A point in whole pixels. */
data class PxPoint(val x: Int, val y: Int)

/**
 * Somewhere a click on the screenshot overlay captures whole: a pane (the page, terminal or
 * Settings it shows), or the tab switcher drawn over the panes. [layer] puts one above another.
 */
data class CaptureRegion(val key: String, val bounds: PxRect, val layer: Int = 0)

/**
 * The geometry of choosing what a screenshot takes: a dragged rectangle, or the region under a
 * click. Everything is in window pixels, the coordinates a copy of the window is made in. Pure, so
 * it is tested on the JVM.
 */
object CaptureGeometry {

    /** A mouse or touchpad starts a selection after moving this far; a finger after the system's touch slop. */
    const val MOUSE_DRAG_THRESHOLD_DP = 4f

    /** A selection smaller than this either way (a slip of the hand) takes nothing; the overlay stays. */
    const val MIN_SELECTION_DP = 8f

    fun dpToPx(dp: Float, density: Float): Float = dp * density

    /** How far the pointer must travel before a press becomes a drag rather than a click. */
    fun dragThresholdPx(isMouse: Boolean, touchSlopPx: Float, density: Float): Float =
        if (isMouse) dpToPx(MOUSE_DRAG_THRESHOLD_DP, density) else touchSlopPx

    fun isDrag(startX: Float, startY: Float, x: Float, y: Float, thresholdPx: Float): Boolean =
        hypot(x - startX, y - startY) > thresholdPx

    /**
     * The rectangle a drag from (startX, startY) to (endX, endY) selects, whichever way it went:
     * each edge rounded to the nearest pixel boundary, then kept inside [bounds] (the window), so
     * dragging past an edge stops at it.
     */
    fun selection(startX: Float, startY: Float, endX: Float, endY: Float, bounds: PxRect): PxRect {
        fun x(value: Float) = value.roundToInt().coerceIn(bounds.left, bounds.right)
        fun y(value: Float) = value.roundToInt().coerceIn(bounds.top, bounds.bottom)
        return PxRect(
            left = x(minOf(startX, endX)),
            top = y(minOf(startY, endY)),
            right = x(maxOf(startX, endX)),
            bottom = y(maxOf(startY, endY))
        )
    }

    /** Whether [rect] is big enough to be taken: at least [MIN_SELECTION_DP] each way. */
    fun isBigEnough(rect: PxRect, density: Float): Boolean {
        val min = dpToPx(MIN_SELECTION_DP, density)
        return rect.width >= min && rect.height >= min
    }

    /** The topmost region at (x, y), or null over the browser's toolbar and tab rail. */
    fun regionAt(x: Float, y: Float, regions: Collection<CaptureRegion>): CaptureRegion? =
        regions.filter { it.bounds.contains(x, y) }.maxByOrNull { it.layer }

    /**
     * What a click at (x, y) takes: the region under it (the visible part, inside [window]), or
     * the whole window over the toolbar and the tab rail.
     */
    fun clickTarget(x: Float, y: Float, regions: Collection<CaptureRegion>, window: PxRect): PxRect =
        regionAt(x, y, regions)?.bounds?.intersect(window) ?: window

    /** What Enter takes: the region named [key] (the tab switcher, or the focused pane), else the whole window. */
    fun focusedTarget(key: String?, regions: Collection<CaptureRegion>, window: PxRect): PxRect =
        regions.firstOrNull { it.key == key }?.bounds?.intersect(window) ?: window

    /**
     * The largest whole page taken, in pixels: 64 megapixels, 256 MB as a picture. A longer page
     * is refused rather than copied, since its picture and the PNG made from it could run the app
     * out of memory, and few places would take one that large anyway.
     */
    const val WHOLE_PAGE_MAX_PIXELS = 64_000_000L

    /** Whether a whole page of [width] by [height] pixels is within [WHOLE_PAGE_MAX_PIXELS]. */
    fun fitsWholePage(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= WHOLE_PAGE_MAX_PIXELS

    /**
     * Whose whole page the overlay's key takes: the region under the mouse ([hoveredKey]) when
     * there is one, else the focused one; null unless that region is a pane showing a web page
     * (one of [pageKeys]). Over the toolbar, which is no region, it is the focused pane's.
     */
    fun wholePageTarget(hoveredKey: String?, focusedKey: String?, pageKeys: Set<String>): String? =
        (hoveredKey ?: focusedKey)?.takeIf { it in pageKeys }

    /**
     * The top of a whole page of [width] by [height] pixels, as wide as the page and [aspect]
     * times wider than tall, or all of it when it is shorter than that: what the notice's small
     * picture shows of it.
     */
    fun topOfPage(width: Int, height: Int, aspect: Float): PxRect =
        PxRect(0, 0, width, (width / aspect).roundToInt().coerceIn(1, maxOf(1, height)))

    /** The selection's size as captured, in pixels: "640 × 480". */
    fun sizeLabel(rect: PxRect): String = "${rect.width} × ${rect.height}"

    /** The same, for a screen reader: "640 by 480 pixels". */
    fun sizeDescription(rect: PxRect): String = "${rect.width} by ${rect.height} pixels"

    /**
     * Where the size label ([labelWidth] by [labelHeight]) goes for [selection]: [gap] below its
     * bottom edge, lined up with its right edge; above its top edge when there is no room below;
     * inside its bottom-right corner when there is room on neither side. Always inside [bounds],
     * at least [gap] from its edges where it fits.
     */
    fun labelPosition(selection: PxRect, labelWidth: Int, labelHeight: Int, bounds: PxRect, gap: Int): PxPoint {
        val y = when {
            selection.bottom + gap + labelHeight <= bounds.bottom - gap -> selection.bottom + gap
            selection.top - gap - labelHeight >= bounds.top + gap -> selection.top - gap - labelHeight
            else -> selection.bottom - gap - labelHeight
        }
        val x = selection.right - labelWidth
        return PxPoint(
            x = x.coerceAtMost(bounds.right - gap - labelWidth).coerceAtLeast(bounds.left + gap),
            y = y.coerceAtMost(bounds.bottom - gap - labelHeight).coerceAtLeast(bounds.top + gap)
        )
    }
}
