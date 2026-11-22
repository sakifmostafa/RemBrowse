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
     * is, past the keyboard app. A keyboard app commits Enter as a typed line break, which Gecko
     * hands the page as text with a "Process" (229) key, not an Enter key: a chat box that sends
     * on Enter then started a new line instead.
     *
     * The key goes through the session's own key path (`SessionTextInput.onKeyDown/onKeyUp`), the
     * one GeckoView uses itself for keys the keyboard app leaves alone. Gecko commits a word still
     * being composed before it dispatches the key, so nothing typed is lost. False, leaving Enter
     * to the keyboard app, without a page, or while a Chinese, Japanese or Korean keyboard is
     * composing, where Enter picks the characters.
     *
     * (0.13.8 checked `onCheckIsTextEditor()` first, which GeckoView never overrides, so it was
     * always false and Enter never came here.)
     */
    fun sendEnterPastKeyboardApp(event: KeyEvent): Boolean {
        val textInput = session?.textInput ?: return false
        if (pageInput?.composing == true && keyboardComposesCharacters()) return false
        // Input events are recycled once handled; Gecko reads the key on its own thread
        val copy = KeyEvent(event)
        return when (copy.action) {
            KeyEvent.ACTION_DOWN -> textInput.onKeyDown(copy.keyCode, copy)
            KeyEvent.ACTION_UP -> textInput.onKeyUp(copy.keyCode, copy)
            else -> false
        }
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
