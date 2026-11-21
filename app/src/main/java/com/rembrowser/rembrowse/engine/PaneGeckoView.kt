package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoView

/**
 * The web view of a pane. It shows the page's mouse cursor, except while the browser's chrome
 * has the pointer ([chromeHasPointer]): then it names none, and Android goes on to the cursor
 * of the screen around it. Without this the page's cursor shows wherever the pointer is over
 * the view, also under chrome drawn on top of its edge.
 *
 * Touchpad input goes through a [TouchpadInputFilter] first, so that a touchpad's right click
 * reaches the page as a right click and resting fingers mid-scroll are never a long press.
 */
@SuppressLint("ViewConstructor") // Only ever created in code
class PaneGeckoView(
    context: Context,
    private val chromeHasPointer: () -> Boolean = { false }
) : GeckoView(context) {

    private val touchpad = TouchpadInputFilter(
        TouchpadInputFilter.scrollSlopPx(
            dpi = resources.displayMetrics.let { maxOf(it.densityDpi.toFloat(), it.xdpi, it.ydpi) },
            touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
        )
    )

    private var pointerX = Float.NaN
    private var pointerY = Float.NaN

    /**
     * Where the pointer last pressed or hovered on this view, in pixels from its top-left corner;
     * null before it has been over the view. A context menu for the page opens here.
     */
    val lastPointerPosition: PointF?
        get() = if (pointerX.isNaN()) null else PointF(pointerX, pointerY)

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        if (chromeHasPointer()) null else super.onResolvePointerIcon(event, pointerIndex)

    @SuppressLint("ClickableViewAccessibility") // GeckoView handles the page's own clicks
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) notePointer(event)
        // Without a page there is nothing to filter for; GeckoView declines the gesture
        if (session == null) return super.onTouchEvent(event)
        touchpad.filter(event) { super.onTouchEvent(it) }
        // A held-back touchpad scroll is still this view's gesture
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_BUTTON_PRESS -> notePointer(event)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun notePointer(event: MotionEvent) {
        if (event.pointerCount == 0) return
        pointerX = event.x
        pointerY = event.y
    }

    /**
     * The compositor could not use its surface and asks for a new one. GeckoView provokes it by
     * toggling the texture's visibility, which only produces a frame callback; with Standard
     * rendering frame callbacks are held back, so the next one is let through (see PageSurface).
     */
    override fun requestNewSurface() {
        letNextTextureFrameThrough()
        super.requestNewSurface()
    }
}
