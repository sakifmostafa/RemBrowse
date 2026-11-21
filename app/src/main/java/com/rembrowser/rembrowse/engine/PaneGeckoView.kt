package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.os.Build
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

    // The keyboard app's connection to the page's focused text box, wrapped to see whether it
    // holds a word it is still composing
    @Volatile
    private var pageInput: ComposingAwareInputConnection? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        val wrapped = connection?.let(::ComposingAwareInputConnection)
        pageInput = wrapped
        return wrapped
    }

    /**
     * Sends a physical keyboard's Enter (Shift, Ctrl and Alt included) to the page as the key it
     * is, past the keyboard app. Keyboard apps keep the word being typed as composing text and
     * turn Enter into a typed line break, or an Enter marked as part of composing, which chat
     * boxes rightly ignore: Enter then started a new line instead of sending. Here a word being
     * composed is committed first, as it would be anyway. False, leaving the key to the keyboard
     * app, when no text box on the page has the keyboard, or while a Chinese, Japanese or Korean
     * keyboard composes, where Enter picks the characters.
     */
    fun sendEnterPastKeyboardApp(event: KeyEvent): Boolean {
        val input = pageInput ?: return false
        if (!onCheckIsTextEditor()) return false
        if (input.composing && keyboardComposesCharacters()) return false
        // Input events are recycled once handled: keep a copy for the input connection's thread
        val copy = KeyEvent(event)
        val send = Runnable {
            if (input.composing) input.finishComposingText()
            input.sendKeyEvent(copy)
        }
        val thread = input.handler
        if (thread != null) thread.post(send) else send.run()
        return true
    }

    private fun keyboardComposesCharacters(): Boolean {
        val subtype = context.getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype ?: return false
        val language = subtype.languageTag.ifEmpty {
            @Suppress("DEPRECATION")
            subtype.locale
        }.lowercase()
        return language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
    }

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

/** Passes everything on to the page's input connection, noting whether a word is being composed. */
internal class ComposingAwareInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {

    @Volatile
    var composing = false
        private set

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = !text.isNullOrEmpty()
        return super.setComposingText(text, newCursorPosition)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        composing = text.isNotEmpty()
        return super.setComposingText(text, newCursorPosition, textAttribute)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composing = start != end
        return super.setComposingRegion(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean {
        composing = start != end
        return super.setComposingRegion(start, end, textAttribute)
    }

    override fun finishComposingText(): Boolean {
        composing = false
        return super.finishComposingText()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = false
        return super.commitText(text, newCursorPosition)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        composing = false
        return super.commitText(text, newCursorPosition, textAttribute)
    }
}
