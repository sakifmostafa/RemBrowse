package com.rembrowser.rembrowse.model

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

/** What to do about a whole page, decided from its size before it is drawn ([CaptureGeometry.wholePagePlan]). */
sealed interface WholePagePlan {
    /**
     * Take it as a picture of [width] by [height] pixels: at the screen's density, or smaller
     * than that when [reduced], for a page too long to take at full size.
     */
    data class Take(val width: Int, val height: Int, val reduced: Boolean) : WholePagePlan

    /** Too long to take even reduced, on any device: nothing is drawn. */
    data object TooLong : WholePagePlan

    /**
     * Too long to take even reduced with the memory this device has free right now, though not
     * otherwise: nothing is drawn. It may well be taken once other apps or tabs are closed.
     */
    data object LowMemory : WholePagePlan
}

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
     * The largest whole page taken on any device, in pixels: 64 megapixels, 256 MB as a picture.
     * Few places would take a larger one. A device with less memory to spare takes less
     * ([wholePageMaxPixels]).
     */
    const val WHOLE_PAGE_MAX_PIXELS = 64_000_000L

    /**
     * The memory set aside for each pixel of a whole page while it is taken, in bytes. The
     * picture itself is 4, all of it in the app's main process, which is where the memory was
     * seen to go on a device (a 110-megapixel picture added 460 MB there, and nothing to the
     * engine's other processes). Twice that again is kept spare for the page's own process
     * and whatever else is running. The PNG made from it is written straight to a file and
     * needs none.
     */
    const val WHOLE_PAGE_BYTES_PER_PIXEL = 12L

    /**
     * A whole page this small is taken however little memory is free: 4 megapixels, about what a
     * screenshot of the window takes anyway.
     */
    const val WHOLE_PAGE_MIN_BUDGET_PIXELS = 4_000_000L

    /**
     * The largest whole page this device can take right now, in pixels. [availableBytes] is the
     * memory Android counts as free and [lowMemoryBytes] the level at which it starts closing
     * other apps (`ActivityManager.MemoryInfo.availMem` and `threshold`): the page may use what
     * is between the two, at [WHOLE_PAGE_BYTES_PER_PIXEL], so taking it never brings the device
     * that low. Never more than [WHOLE_PAGE_MAX_PIXELS], nor less than
     * [WHOLE_PAGE_MIN_BUDGET_PIXELS].
     */
    fun wholePageMaxPixels(availableBytes: Long, lowMemoryBytes: Long): Long =
        ((availableBytes - lowMemoryBytes) / WHOLE_PAGE_BYTES_PER_PIXEL)
            .coerceIn(WHOLE_PAGE_MIN_BUDGET_PIXELS, WHOLE_PAGE_MAX_PIXELS)

    /**
     * What to do about a whole page of [cssWidth] by [cssHeight] CSS pixels, each
     * [devicePixelRatio] pixels on this screen, when no more than [maxPixels] may be taken.
     * Decided from the page's size alone, before the engine draws anything.
     *
     * Within the budget it is taken at the screen's density, the size the engine itself gives a
     * whole page. Over it, it is taken smaller, as large as the budget allows with its shape
     * kept, but never with less than one pixel for each CSS pixel (how it would look on an
     * ordinary monitor, below which text stops being readable). A page too long for that is
     * [WholePagePlan.TooLong] when it would be so with all of [WHOLE_PAGE_MAX_PIXELS] too, and
     * [WholePagePlan.LowMemory] when it is only the device's free memory that has brought
     * [maxPixels] under what it needs: an ordinary article on a device short of memory is not
     * "too long", and can be taken later. Null when there is no page to take.
     *
     * The engine fits the page into the picture it is asked for by whichever side is tighter. So
     * the page's longer side is settled first, rounded down, and the shorter one follows from it,
     * rounded up: the page then fills the picture to within a pixel, with no blank strip at its
     * end.
     */
    fun wholePagePlan(cssWidth: Int, cssHeight: Int, devicePixelRatio: Float, maxPixels: Long): WholePagePlan? {
        if (cssWidth <= 0 || cssHeight <= 0 || !(devicePixelRatio > 0f) || devicePixelRatio.isInfinite()) return null
        // As the engine rounds them
        val fullWidth = (cssWidth * devicePixelRatio).toInt()
        val fullHeight = (cssHeight * devicePixelRatio).toInt()
        if (fullWidth <= 0 || fullHeight <= 0) return null
        val fullPixels = fullWidth.toLong() * fullHeight
        val cssPixels = cssWidth.toLong() * cssHeight
        if (fullPixels > maxPixels && cssPixels > maxPixels) {
            val fitsWithMemory = minOf(fullPixels, cssPixels) <= WHOLE_PAGE_MAX_PIXELS
            return if (fitsWithMemory) WholePagePlan.LowMemory else WholePagePlan.TooLong
        }
        val scale = if (fullPixels > maxPixels) sqrt(maxPixels.toDouble() / fullPixels) else 1.0
        val tall = cssHeight >= cssWidth
        val cssLong = if (tall) cssHeight else cssWidth
        val cssShort = if (tall) cssWidth else cssHeight
        val fullLong = if (tall) fullHeight else fullWidth
        fun shortFor(long: Int): Int = ceil(cssShort.toDouble() * long / cssLong).toInt().coerceAtLeast(1)
        var long = (fullLong * scale).toInt().coerceAtLeast(1)
        var short = shortFor(long)
        if (long.toLong() * short > maxPixels) {
            // Rounding up took it just over: the shorter side can only get shorter from here
            long = (maxPixels / short).toInt().coerceAtLeast(1)
            short = shortFor(long)
        }
        return WholePagePlan.Take(
            width = if (tall) short else long,
            height = if (tall) long else short,
            reduced = long < fullLong
        )
    }

    /**
     * The side of the region the engine is asked to draw for a page side of [css] CSS pixels,
     * in the screen's pixels, each CSS pixel being [devicePixelRatio] of them. The engine turns
     * it back into CSS pixels by dividing and rounding down, so this is the smallest number that
     * comes back as [css]: all of that side and no more. (Where none does, on a page zoomed out,
     * the next one up: never less than the page.)
     */
    fun wholePageSource(css: Int, devicePixelRatio: Float): Int {
        var source = ceil(css * devicePixelRatio).toInt().coerceAtLeast(1)
        while ((source / devicePixelRatio).toInt() < css) source++
        while (source > 1 && ((source - 1) / devicePixelRatio).toInt() >= css) source--
        return source
    }

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
