package com.rembrowser.rembrowse.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import com.rembrowser.rembrowse.model.PxRect
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoView

private const val TAG = "WindowScreenshot"

/**
 * A web page drawn behind the window rather than in it ([com.rembrowser.rembrowse.model.PageRendering.DIRECT],
 * a SurfaceView): a copy of the window has a transparent hole where it is. [bounds] is where its
 * view is in the window; [capture] asks the engine for the page's own pixels, the size of the view,
 * or null if it can't give them.
 */
class PageLayer(val bounds: PxRect, val capture: suspend () -> Bitmap?)

/** [PageLayer] for a pane's web view, if it shows a page. Call it on the main thread. */
fun GeckoView.asPageLayer(): PageLayer? {
    if (!isAttachedToWindow || !isShown || session == null || width <= 0 || height <= 0) return null
    val location = IntArray(2)
    getLocationInWindow(location)
    val bounds = PxRect(location[0], location[1], location[0] + width, location[1] + height)
    return PageLayer(bounds) { awaitPagePixels() }
}

/** The page's pixels from Gecko's compositor (`GeckoView.capturePixels`), or null if it has none yet. */
private suspend fun GeckoView.awaitPagePixels(): Bitmap? = suspendCancellableCoroutine { continuation ->
    capturePixels().accept(
        // A screenshot given up on meanwhile lets go of the picture at once
        { bitmap -> continuation.resume(bitmap) { _, picture, _ -> picture?.recycle() } },
        { error ->
            Log.w(TAG, "The page's pixels couldn't be captured", error)
            if (continuation.isActive) continuation.resume(null)
        }
    )
}

/**
 * Takes a screenshot of part of RemBrowse's own window, as it looks: pages, toolbar and rail. Not
 * other apps, so it needs no screen-recording permission.
 *
 * The window is copied with PixelCopy, which reads the window's last drawn frame. That frame holds
 * everything drawn in the window, pages shown in a TextureView included (Standard and Legacy page
 * rendering). A page shown in a SurfaceView (Direct) is a separate layer behind the window, seen
 * through a transparent hole: those pages are asked for their own pixels and put under the
 * window's, so whatever the browser draws over a page (the find bar, a permission card, the pane's
 * rounded corners) stays on top, as on screen.
 *
 * Used on the main thread.
 */
class WindowScreenshot(private val window: Window) {

    /**
     * The pixels of [area] (window pixels), with [pages] filled in under the window. Whatever is
     * left transparent (a page that couldn't be captured) becomes [holeColor], the colour a pane
     * shows before its page paints. Waits for the window to draw a frame first, so anything taken
     * off the screen just before (the screenshot overlay) is not in it.
     *
     * Cancelling it, at any point, frees every picture it made.
     *
     * @throws IllegalStateException if the window can't be copied.
     */
    suspend fun capture(area: PxRect, pages: List<PageLayer>, holeColor: Int): Bitmap {
        val decor = window.decorView
        val clipped = area.intersect(PxRect(0, 0, decor.width, decor.height))
            ?: throw IllegalStateException("Nothing of the window to capture in $area")
        awaitNextFrame(decor)
        val shot = copyWindow(clipped)
        // Every picture is let go of whatever happens: cancelled, or out of memory
        val captured = mutableListOf<Pair<PxRect, Bitmap?>>()
        try {
            for (page in pages) {
                if (page.bounds.intersect(clipped) == null) continue
                captured += page.bounds to page.capture()
            }
            withContext(Dispatchers.Default) {
                ScreenshotPixels.fillIn(shot, clipped, captured, holeColor)
            }
        } catch (e: Throwable) {
            shot.recycle()
            throw e
        } finally {
            captured.forEach { (_, pixels) -> pixels?.recycle() }
        }
        return shot
    }

    /**
     * Waits until the window has drawn and handed over a new frame. The frame commit callback
     * says exactly that (its documented use is with PixelCopy).
     */
    private suspend fun awaitNextFrame(decor: View) {
        val committed = withTimeoutOrNull(FRAME_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val observer = decor.viewTreeObserver
                val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                observer.registerFrameCommitCallback(callback)
                continuation.invokeOnCancellation {
                    decor.post { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
                }
                // Nothing may be waiting to be drawn; this makes sure a frame comes
                decor.invalidate()
            }
        }
        if (committed == null) Log.w(TAG, "No frame was drawn in time; copying the window as it is")
    }

    private suspend fun copyWindow(area: PxRect): Bitmap = suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                window,
                Rect(area.left, area.top, area.right, area.bottom),
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap) { _, picture, _ -> picture.recycle() }
                    } else {
                        bitmap.recycle()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("PixelCopy failed with $result"))
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: IllegalArgumentException) {
            // The window has no surface (not drawn yet, or going away)
            bitmap.recycle()
            continuation.resumeWithException(IllegalStateException("The window can't be copied", e))
        }
    }

    private companion object {
        const val FRAME_TIMEOUT_MILLIS = 500L
    }
}

/** Putting pages under a copy of the window, a row at a time. The pixel rules are pure, for JVM tests. */
internal object ScreenshotPixels {

    private const val OPAQUE_BLACK = 0xFF000000.toInt()

    /**
     * Whether a window pixel over a Direct page is the hole the page is seen through. A window
     * with a SurfaceView has an alpha channel, and the hole is transparent; were the copy made
     * without alpha, the hole would come out as opaque black instead, which nothing RemBrowse
     * draws over a page uses (its darkest colour is #0F1115).
     */
    fun isHole(pixel: Int): Boolean = pixel ushr 24 == 0 || pixel == OPAQUE_BLACK

    /** [top] (not premultiplied, as `Bitmap.getPixels` gives it) drawn over the opaque [under]. */
    fun over(top: Int, under: Int): Int {
        val alpha = top ushr 24
        if (alpha == 0xFF) return top
        if (alpha == 0) return under or OPAQUE_BLACK
        fun channel(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val u = (under shr shift) and 0xFF
            return (t * alpha + u * (0xFF - alpha) + 127) / 0xFF
        }
        return OPAQUE_BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * One row of window pixels over a page: [window] holds the window's pixels and gets the
     * result; [page] holds the page's pixels at the same places, or [holeColor] where there are
     * none (null: the page couldn't be captured). Holes show the page; partly transparent window
     * pixels (a rounded corner's edge) are blended over it.
     */
    fun mergeRow(window: IntArray, page: IntArray?, holeColor: Int, from: Int = 0, until: Int = window.size) {
        for (i in from until until) {
            val under = page?.get(i)?.let { it or OPAQUE_BLACK } ?: (holeColor or OPAQUE_BLACK)
            val top = window[i]
            window[i] = if (isHole(top)) under else over(top, under)
        }
    }

    /** Makes every pixel of [row] opaque, over [background]. */
    fun flattenRow(row: IntArray, background: Int) {
        for (i in row.indices) row[i] = over(row[i], background or OPAQUE_BLACK)
    }

    /**
     * Fills [shot], the copy of [area], with [pages] (each one's bounds in the window, and its
     * pixels or null) where the window has holes, then makes it opaque over [holeColor].
     */
    fun fillIn(shot: Bitmap, area: PxRect, pages: List<Pair<PxRect, Bitmap?>>, holeColor: Int) {
        val width = area.width
        val row = IntArray(width)
        val pageRow = IntArray(width)
        for (y in area.top until area.bottom) {
            shot.getPixels(row, 0, width, 0, y - area.top, width, 1)
            for ((bounds, pixels) in pages) {
                val span = bounds.intersect(PxRect(area.left, y, area.right, y + 1)) ?: continue
                val from = span.left - area.left
                val until = span.right - area.left
                if (pixels == null) {
                    mergeRow(row, null, holeColor, from, until)
                    continue
                }
                // The page's own picture may be a little smaller than its view; the rest is a hole
                val pageY = y - bounds.top
                pageRow.fill(holeColor, from, until)
                if (pageY < pixels.height) {
                    val pageFrom = span.left - bounds.left
                    val count = minOf(span.width, pixels.width - pageFrom)
                    if (count > 0) pixels.getPixels(pageRow, from, width, pageFrom, pageY, count, 1)
                }
                mergeRow(row, pageRow, holeColor, from, until)
            }
            flattenRow(row, holeColor)
            shot.setPixels(row, 0, width, 0, y - area.top, width, 1)
        }
        shot.setHasAlpha(false)
    }
}
