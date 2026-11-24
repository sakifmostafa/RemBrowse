package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.PointerIcon
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoView
import com.rembrowser.rembrowse.util.PageKeyTarget

/**
 * The web view of a pane. It shows the page's mouse cursor, except while the browser's chrome
 * has the pointer ([chromeHasPointer]): then it names none, and Android goes on to the cursor
 * of the screen around it. Without this the page's cursor shows wherever the pointer is over
 * the view, also under chrome drawn on top of its edge.
 *
 * Touchpad input goes through a [TouchpadInputFilter] first, so that a touchpad's right click
 * reaches the page as a right click and resting fingers mid-scroll are never a long press, and
 * then through a [TouchpadSwipeNavigator], so a two-finger sideways swipe the page has no use
 * for goes back or forward ([swipeNavigation]), with an arrow drawn by [swipeFeedback]. Hover
 * moves made up during a two-finger scroll are kept from the page ([TouchpadGestureHoverGuard]).
 */
@SuppressLint("ViewConstructor") // Only ever created in code
class PaneGeckoView(
    context: Context,
    private val chromeHasPointer: () -> Boolean = { false },
    swipeFeedback: SwipeFeedback? = null
) : GeckoView(context), PageKeyTarget {

    private val touchpad = TouchpadInputFilter(
        TouchpadInputFilter.scrollSlopPx(
            dpi = resources.displayMetrics.let { maxOf(it.densityDpi.toFloat(), it.xdpi, it.ydpi) },
            touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
        )
    )

    private val swipe = TouchpadSwipeNavigator(
        density = resources.displayMetrics.density,
        touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        sendDown = ::sendDownForResult,
        feedback = swipeFeedback,
        haptic = {
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                } else {
                    HapticFeedbackConstants.CONTEXT_CLICK
                }
            )
        },
        fingersMoveWithPage = { touchpadNaturalScrolling(context) }
    )

    private val hoverGuard = TouchpadGestureHoverGuard()

    /** Where a two-finger swipe on this page goes; null leaves every swipe to the page. */
    var swipeNavigation: SwipeNavigation?
        get() = swipe.navigation
        set(value) {
            swipe.navigation = value
        }

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
     * Whether a physical key can go to the page past the keyboard app right now: there is a page,
     * and the keyboard app doesn't build characters from the letters (Chinese, Japanese, Korean).
     * Such a keyboard also keeps Enter while it is composing, since Enter confirms the characters.
     */
    override fun takesKeysPastKeyboardApp(enter: Boolean): Boolean {
        if (session == null) return false
        if (!keyboardComposesCharacters()) return true
        return enter && pageInput?.composing != true
    }

    /**
     * Sends a physical keyboard's key (Shift, Alt and the rest included) to the page past the
     * keyboard app, through the session's own key path (`SessionTextInput.onKeyDown` and
     * `onKeyUp`), the one GeckoView uses itself for keys the keyboard app leaves alone. Used for
     * Enter: the page gets a real Enter keydown it can cancel, where the keyboard app typed a line
     * break. Gecko commits a word still being composed before it dispatches Enter. False without a
     * page.
     *
     * (0.13.8 checked `onCheckIsTextEditor()` first, which GeckoView never overrides, so it was
     * always false and Enter never came here.)
     */
    override fun sendKeyPastKeyboardApp(event: KeyEvent): Boolean {
        val textInput = session?.textInput ?: return false
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        super.dispatchTouchEvent(event).also { took -> hoverGuard.onTouchEvent(event, took) }

    // During a touchpad's two-finger scroll, a hover move is one Compose made up at the pretend
    // finger, not the mouse (see TouchpadGestureHoverGuard): the page doesn't get it
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        hoverGuard.drops(event) || super.dispatchGenericMotionEvent(event)

    @SuppressLint("ClickableViewAccessibility") // GeckoView handles the page's own clicks
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) notePointer(event)
        // Without a page there is nothing to filter for; GeckoView declines the gesture. A swipe
        // whose page went away mid-way ends, so its arrow doesn't stay up.
        if (session == null) {
            swipe.cancel()
            return super.onTouchEvent(event)
        }
        touchpad.filter(event) { filtered -> swipe.handle(filtered) { super.onTouchEvent(it) } }
        // A held-back touchpad scroll, or a swipe taken from the page, is still this view's gesture
        return true
    }

    /**
     * A possible swipe's press, handed to the page as `onTouchEvent` would, with word back of
     * how the page handles it: what decides whether the swipe may navigate.
     */
    private fun sendDownForResult(down: MotionEvent, result: (PageScroll) -> Unit) {
        onTouchEventForDetailResult(down).accept { detail ->
            if (detail != null) {
                result(PageScroll(detail.handledResult(), detail.scrollableDirections(), detail.overscrollDirections()))
            }
        }
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

/**
 * Passes everything on to the page's input connection, noting whether a word is being composed,
 * with one change: a word that starts over selected text first deletes the selection.
 *
 * Keyboard apps (Gboard included, with a physical keyboard) type into a page as a word being
 * composed. Started over a selection, as after Ctrl+A, Android's replaceText collapses the caret
 * to the selection's end and Gecko then starts the composition over the selected text, which
 * rich-text editors (Telegram's, for one) answer by rewriting the box: one character appeared
 * and the caret landed before it. Deleting the selection first, in the same batch, starts the
 * word at a plain caret, exactly like typing into an empty box. (GeckoView already reorders
 * commitText this way; setComposingText it leaves alone.)
 *
 * Text committed over a selection ([replaceSelection]) is put in without naming a caret position
 * afterwards. GeckoView's own commitText replaces the selection and then sets the caret to
 * "selection start + text length", counted in the text as it was before. Gecko counts a line
 * break before every block element, so in an editor that keeps its text in a `<p>` (ProseMirror
 * editors such as Telegram's) the box reads "\naaa", Ctrl+A selects 0-4, typing "a" leaves "\na", and
 * caret position 0 + 1 is in front of the "a". Gboard commits every letter of a physical keyboard
 * this way.
 */
internal class ComposingAwareInputConnection(
    private val target: InputConnection
) : InputConnectionWrapper(target, true) {

    @Volatile
    var composing = false
        private set

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        startingOverSelection(text) { super.setComposingText(text, newCursorPosition) }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        startingOverSelection(text) { super.setComposingText(text, newCursorPosition, textAttribute) }

    /** Runs [compose]; a new word over selected text deletes the selection first, in one batch. */
    private inline fun startingOverSelection(text: CharSequence?, compose: () -> Boolean): Boolean {
        val replacing = !composing && !text.isNullOrEmpty() && !getSelectedText(0).isNullOrEmpty()
        if (replacing) beginBatchEdit()
        // The batch is closed whatever happens: left open, the keyboard app would get no more updates
        return try {
            if (replacing) super.commitText("", 1)
            composing = !text.isNullOrEmpty()
            compose()
        } finally {
            if (replacing) endBatchEdit()
        }
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
        return replaceSelection(text, newCursorPosition) || super.commitText(text, newCursorPosition)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        composing = false
        return replaceSelection(text, newCursorPosition) || super.commitText(text, newCursorPosition, textAttribute)
    }

    /**
     * Replaces the selected text with committed [text] and leaves the caret to the page, which
     * puts it after what it inserted. False, with nothing done, unless this is the plain case:
     * text to insert, the caret wanted right after it, a selection, and no word being composed
     * (a composing keyboard's commit replaces its word, not the selection).
     *
     * The selection is collapsed to its end first and the replaced range is then named outright,
     * as Android's own key listener does for a typed key (QwertyKeyListener.onKeyDown), which is
     * what GeckoView runs for a key no keyboard app took. Until the page answers, the caret
     * Android sees is after the new text, the same as before this change.
     */
    private fun replaceSelection(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text.isNullOrEmpty() || newCursorPosition != 1) return false
        // GeckoView's connection; null on a stale connection or with the page gone
        val editable = (target as? BaseInputConnection)?.editable ?: return false
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) return false
        val anchor = Selection.getSelectionStart(editable)
        val focus = Selection.getSelectionEnd(editable)
        if (anchor < 0 || focus < 0 || anchor == focus) return false
        val start = minOf(anchor, focus)
        val end = maxOf(anchor, focus)
        if (end > editable.length) return false
        beginBatchEdit()
        // The batch is closed whatever happens: left open, the keyboard app would get no more updates
        try {
            Selection.setSelection(editable, end)
            editable.replace(start, end, text)
        } finally {
            endBatchEdit()
        }
        return true
    }
}

/**
 * The touchpad's scroll direction (Settings → System → Touchpad → Reverse scrolling, stored as
 * `touchpad_natural_scrolling`, readable by apps): true, Android's default, when the page follows
 * the fingers. Read at every swipe, so a change applies at once; true if it can't be read.
 */
private fun touchpadNaturalScrolling(context: Context): Boolean =
    runCatching {
        android.provider.Settings.System.getInt(context.contentResolver, "touchpad_natural_scrolling", 1) != 0
    }.getOrDefault(true)
