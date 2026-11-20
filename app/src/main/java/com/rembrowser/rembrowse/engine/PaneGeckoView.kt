package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.PointerIcon
import org.mozilla.geckoview.GeckoView

/**
 * The web view of a pane. It shows the page's mouse cursor, except while the browser's chrome
 * has the pointer ([chromeHasPointer]): then it names none, and Android goes on to the cursor
 * of the screen around it. Without this the page's cursor shows wherever the pointer is over
 * the view, also under chrome drawn on top of its edge.
 */
@SuppressLint("ViewConstructor") // Only ever created in code
class PaneGeckoView(context: Context, private val chromeHasPointer: () -> Boolean) : GeckoView(context) {

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        if (chromeHasPointer()) null else super.onResolvePointerIcon(event, pointerIndex)
}
