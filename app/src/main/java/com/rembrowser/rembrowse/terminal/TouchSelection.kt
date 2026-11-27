package com.rembrowser.rembrowse.terminal

import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.rembrowser.rembrowse.terminal.TerminalScreenView.Cell
import com.termux.terminal.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Selecting on a [TerminalScreenView] with a finger or a stylus, as in Android's text fields.
 *
 * - **Long-press** a word to select it, and keep the finger down to drag over more, a word at a
 *   time. A long-press on blank space selects nothing and offers Paste and Select all.
 * - **Handles**, teardrops in the cursor's colour, move either end cell by cell. Dragging one past
 *   the other swaps them, and near the top or bottom edge the history scrolls.
 * - **The platform's floating toolbar** offers Copy, Paste and Select all. It hides while a finger
 *   scrolls or drags, and comes back afterwards.
 *
 * A tap puts a selection away; a drag without a long-press scrolls, as it always has. Nothing here
 * reaches the program, even one that asked for the mouse. The view calls [end] whenever its
 * selection goes, however it goes, so that is the one place touch selection ends.
 */
internal class TouchSelection(private val view: TerminalScreenView, private val host: Host) {

    /** What touch selection needs of the view that the view keeps to itself. */
    interface Host {
        /** Selects [selection], or nothing for null, and redraws. */
        fun select(selection: TerminalSelection?)

        /** The word at [cell], or null on blank space and brackets. */
        fun wordAt(cell: Cell): TerminalSelection?

        /** Moves through the history by [rows] (positive toward the newest), never the program. */
        fun scrollHistory(rows: Int)

        /** Where [column] starts, in the view's pixels. */
        fun columnLeft(column: Int): Float

        /** Where [row] starts (counted from the live screen's top, negative in the history), in the view's pixels. */
        fun rowTop(row: Int): Float
    }

    /** Whether touch selection is showing: the handles on what is selected, if anything, and the toolbar. */
    var isActive = false
        private set

    /** The floating toolbar; null while touch selection is off, or where the window gives none. */
    var toolbar: ActionMode? = null
        private set

    private enum class Gesture {
        /** No finger down. */
        NONE,

        /** Down without moving past the touch slop: a tap, or a long-press to come. */
        PRESSED,
        SCROLLING,

        /** A long-press on a word, still held: dragging takes in more words. */
        EXTENDING,

        /** A long-press on blank space, still held: the toolbar shows and dragging does nothing. */
        HOLDING,

        /** Dragging a handle. */
        HANDLE,

        /** Touch selection ended under the finger: it does nothing more until it lifts. */
        IGNORED
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val radius = HANDLE_RADIUS_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gesture = Gesture.NONE
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrollRemainder = 0f

    // A long-press on a word: that word, which stays selected however the finger drags
    private var pressedWord: TerminalSelection? = null

    // A long-press on blank space: where, for the toolbar to show beside it
    private var pressedCell: Cell? = null

    // A handle being dragged: the selection's other end, which stays put, and how far the dragged
    // end's cell is from the finger, so the selection doesn't jump to where the handle was taken
    private var fixedEnd: Cell? = null
    private var grabX = 0f
    private var grabY = 0f

    // While a drag is near the top or bottom edge: -1 scrolls up the history, 1 down, 0 neither
    private var autoScroll = 0

    // Where the toolbar was last told the selection is
    private val reportedRect = Rect()
    private val currentRect = Rect()

    /**
     * A finger's or a stylus's [event] on the screen. True for a tap with nothing selected, which
     * the view answers by opening the keyboard, as before.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(event.getPointerId(0), event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) move(event.getX(index), event.getY(index))
            }
            // A second finger makes it neither a tap nor a long-press; the first one scrolls
            MotionEvent.ACTION_POINTER_DOWN -> if (gesture == Gesture.PRESSED) startScrolling()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) finishGesture()
            MotionEvent.ACTION_UP -> return release()
            MotionEvent.ACTION_CANCEL -> finishGesture()
        }
        return false
    }

    /**
     * Ends touch selection: the handles and the toolbar go. The view calls this whenever its
     * selection goes (typing, a new size, another terminal, the mouse); the selection itself is
     * the caller's to clear.
     */
    fun end() {
        stopAutoScroll()
        // A finger still on a word or a handle no longer changes anything
        if (gesture == Gesture.EXTENDING || gesture == Gesture.HOLDING || gesture == Gesture.HANDLE) gesture = Gesture.IGNORED
        pressedWord = null
        pressedCell = null
        fixedEnd = null
        if (!isActive && toolbar == null) return
        isActive = false
        val mode = toolbar
        toolbar = null
        mode?.finish()
        view.invalidate()
    }

    /** The view left its window: no long-press may fire later, and the toolbar goes with it. */
    fun detached() {
        resetGesture()
        end()
    }

    /** Draws the handles over the text, and keeps the toolbar beside the selection as it moves. */
    fun draw(canvas: Canvas) {
        if (!isActive) return
        val palette = view.connection?.emulator?.mColors?.mCurrentColors
        val background = palette?.get(TextStyle.COLOR_INDEX_BACKGROUND) ?: TerminalTheme.BACKGROUND
        val color = handleColor(palette?.get(TextStyle.COLOR_INDEX_CURSOR) ?: TerminalTheme.DEFAULT_CURSOR, background)
        // Compose draws this view without clipping it: stay inside its own bounds
        canvas.save()
        canvas.clipRect(0, 0, view.width, view.height)
        handleShape(start = true)?.let { drawHandle(canvas, it, color, background) }
        handleShape(start = false)?.let { drawHandle(canvas, it, color, background) }
        canvas.restore()

        // Output and scrolling move the selection: the toolbar follows once this frame is drawn
        val mode = toolbar ?: return
        contentRect(currentRect)
        if (currentRect != reportedRect) {
            reportedRect.set(currentRect)
            handler.post { if (toolbar === mode) mode.invalidateContentRect() }
        }
    }

    /** The middle of [cell], in the view's pixels. */
    fun cellCenter(cell: Cell): PointF = PointF(
        (host.columnLeft(cell.column) + host.columnLeft(cell.column + 1)) / 2,
        (host.rowTop(cell.row) + host.rowTop(cell.row + 1)) / 2
    )

    /** The middle of the [start] or end handle's round part, or null while it isn't on screen. */
    fun handleCenter(start: Boolean): PointF? = handleShape(start)?.let { PointF(it.centerX, it.centerY) }

    // --- Gestures ---

    private val longPress = Runnable {
        if (gesture != Gesture.PRESSED) return@Runnable
        val cell = view.cellAt(downX, downY)
        val word = host.wordAt(cell)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (word != null) {
            host.select(word)
            pressedWord = word
            pressedCell = null
            gesture = Gesture.EXTENDING
        } else {
            // Nothing to select there: the toolbar alone, for Paste and Select all
            host.select(null)
            pressedCell = cell
            gesture = Gesture.HOLDING
        }
        show()
    }

    private fun press(id: Int, x: Float, y: Float) {
        resetGesture()
        pointerId = id
        downX = x
        downY = y
        lastX = x
        lastY = y
        scrollRemainder = 0f
        val handle = handleAt(x, y)
        if (handle != null) {
            grab(handle)
        } else {
            gesture = Gesture.PRESSED
            handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun move(x: Float, y: Float) {
        when (gesture) {
            // Past the slop it is a scroll, never a long-press: scrolling starts from here
            Gesture.PRESSED -> if (hypot(x - downX, y - downY) > touchSlop) {
                lastY = y
                startScrolling()
            }
            Gesture.SCROLLING -> {
                hideToolbar()
                scroll(y)
            }
            Gesture.EXTENDING, Gesture.HANDLE -> {
                hideToolbar()
                lastX = x
                lastY = y
                follow()
                autoScrollNear(y)
            }
            Gesture.NONE, Gesture.HOLDING, Gesture.IGNORED -> Unit
        }
    }

    private fun startScrolling() {
        handler.removeCallbacks(longPress)
        gesture = Gesture.SCROLLING
        hideToolbar()
    }

    /** Scrolls with the finger as the view always has: by whole rows, keeping the rest for later. */
    private fun scroll(y: Float) {
        scrollRemainder += lastY - y
        lastY = y
        val rowHeight = rowHeight()
        if (rowHeight <= 0f) return
        val rows = (scrollRemainder / rowHeight).toInt()
        if (rows != 0) {
            scrollRemainder -= rows * rowHeight
            view.scrollBy(rows)
        }
    }

    private fun release(): Boolean {
        val tapped = gesture == Gesture.PRESSED
        finishGesture()
        if (!tapped) return false
        // A tap puts a selection (or the toolbar) away; only a tap on nothing opens the keyboard
        if (!isActive && view.selection == null) return true
        host.select(null)
        return false
    }

    /** The finger lifted, or was taken away: a toolbar hidden for a scroll or a drag comes back. */
    private fun finishGesture() {
        val moved = gesture != Gesture.NONE && gesture != Gesture.PRESSED
        resetGesture()
        if (moved) showToolbar()
    }

    private fun resetGesture() {
        handler.removeCallbacks(longPress)
        stopAutoScroll()
        gesture = Gesture.NONE
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    /** Starts dragging the [start] or end handle; the other end stays put. */
    private fun grab(start: Boolean) {
        val range = view.selection?.normalized() ?: return
        val dragged = if (start) Cell(range.startColumn, range.startRow) else Cell(range.endColumn, range.endRow)
        fixedEnd = if (start) Cell(range.endColumn, range.endRow) else Cell(range.startColumn, range.startRow)
        val center = cellCenter(dragged)
        grabX = center.x - downX
        grabY = center.y - downY
        gesture = Gesture.HANDLE
    }

    /** Moves the dragged end to the cell under the finger, with a light tick for each change. */
    private fun follow() {
        val next = when (gesture) {
            Gesture.EXTENDING -> extendedTo(view.cellAt(lastX, lastY))
            Gesture.HANDLE -> fixedEnd?.let { fixed ->
                // Past the other end, the dragged one becomes the start (or the end): they swap
                val cell = view.cellAt(lastX + grabX, lastY + grabY)
                TerminalSelection(fixed.column, fixed.row, cell.column, cell.row)
            }
            else -> null
        } ?: return
        if (next.normalized() == view.selection?.normalized()) return
        host.select(next)
        view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    }

    /** The long-pressed word stretched to [cell], taking in the whole word there if [cell] is in one. */
    private fun extendedTo(cell: Cell): TerminalSelection? {
        val word = pressedWord?.normalized() ?: return null
        val there = host.wordAt(cell)?.normalized()
        return when {
            isBefore(word.endColumn, word.endRow, cell.column, cell.row) ->
                TerminalSelection(word.startColumn, word.startRow, there?.endColumn ?: cell.column, cell.row)
            isBefore(cell.column, cell.row, word.startColumn, word.startRow) ->
                TerminalSelection(word.endColumn, word.endRow, there?.startColumn ?: cell.column, cell.row)
            else -> pressedWord
        }
    }

    private fun isBefore(column: Int, row: Int, otherColumn: Int, otherRow: Int) =
        row < otherRow || row == otherRow && column < otherColumn

    private val autoScrollStep = object : Runnable {
        override fun run() {
            if (autoScroll == 0) return
            val before = view.topRow
            host.scrollHistory(autoScroll)
            // The top of the history, or back at the live screen: nothing more to scroll
            if (view.topRow == before) {
                autoScroll = 0
                return
            }
            follow()
            handler.postDelayed(this, AUTO_SCROLL_INTERVAL_MS)
        }
    }

    /** Scrolls the history a row at a time, at a steady rate, while a drag is near the top or bottom edge. */
    private fun autoScrollNear(y: Float) {
        val zone = max(rowHeight(), EDGE_ZONE_DP * density)
        val direction = when {
            y < zone -> -1
            y > view.height - zone -> 1
            else -> 0
        }
        if (direction == autoScroll) return
        stopAutoScroll()
        autoScroll = direction
        if (direction != 0) handler.postDelayed(autoScrollStep, AUTO_SCROLL_INTERVAL_MS)
    }

    private fun stopAutoScroll() {
        autoScroll = 0
        handler.removeCallbacks(autoScrollStep)
    }

    private fun rowHeight() = host.rowTop(1) - host.rowTop(0)

    // --- Handles ---

    /**
     * A handle as drawn: the point at ([x], [y]) that meets the text, at the selection's outer
     * corner, and the round part hanging from it [across] (-1 left, 1 right) and [down] (1 below
     * the row, -1 above it).
     */
    private inner class HandleShape(val x: Float, val y: Float, val across: Int, val down: Int) {
        val centerX get() = x + across * radius
        val centerY get() = y + down * radius
    }

    /** Where the [start] or end handle is, or null while touch selection is off or its row isn't on screen. */
    private fun handleShape(start: Boolean): HandleShape? {
        if (!isActive) return null
        val range = view.selection?.normalized() ?: return null
        val rows = view.connection?.emulator?.mRows ?: return null
        val row = if (start) range.startRow else range.endRow
        if (row < view.topRow || row >= view.topRow + rows || rowHeight() <= 0f) return null
        val x = if (start) host.columnLeft(range.startColumn) else host.columnLeft(range.endColumn + 1)
        // Below the row and outward from the selection, as on Android; turned over where that
        // would leave the view, so no part of a handle is ever cut off
        val outward = if (start) -1 else 1
        val across = if (x + outward * 2 * radius in 0f..view.width.toFloat()) outward else -outward
        val below = host.rowTop(row + 1) + 2 * radius <= view.height
        return if (below) HandleShape(x, host.rowTop(row + 1), across, 1) else HandleShape(x, host.rowTop(row), across, -1)
    }

    /** Which handle a press at [x], [y] takes, within its 48dp target: true for the start, false for the end. */
    private fun handleAt(x: Float, y: Float): Boolean? {
        val reach = HANDLE_TARGET_DP * density / 2
        return listOf(true, false)
            .mapNotNull { start -> handleShape(start)?.let { start to it } }
            .filter { (_, shape) -> abs(x - shape.centerX) <= reach && abs(y - shape.centerY) <= reach }
            .minByOrNull { (_, shape) -> hypot(x - shape.centerX, y - shape.centerY) }
            ?.first
    }

    /** A teardrop: a circle with the square corner that points at the text. */
    private fun drawHandle(canvas: Canvas, shape: HandleShape, color: Int, background: Int) {
        val left = min(shape.x, shape.centerX)
        val right = max(shape.x, shape.centerX)
        val top = min(shape.y, shape.centerY)
        val bottom = max(shape.y, shape.centerY)
        // A thin edge in the background's colour first: unseen on the background, it keeps the
        // handle apart from a selection or coloured text underneath
        val edge = HANDLE_EDGE_DP * density
        paint.color = background
        canvas.drawCircle(shape.centerX, shape.centerY, radius + edge, paint)
        canvas.drawRect(left - edge, top - edge, right + edge, bottom + edge, paint)
        paint.color = color
        canvas.drawCircle(shape.centerX, shape.centerY, radius, paint)
        canvas.drawRect(left, top, right, bottom, paint)
    }

    // --- Toolbar ---

    /** Shows the handles and the toolbar for what is selected now, or for [pressedCell]. */
    private fun show() {
        isActive = true
        val current = toolbar
        if (current != null) current.invalidate() else toolbar = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        view.invalidate()
    }

    private fun hideToolbar() {
        toolbar?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
    }

    private fun showToolbar() {
        val mode = toolbar ?: return
        // Paste may have changed meanwhile; this also puts the toolbar beside the selection again
        mode.invalidate()
        mode.hide(0)
    }

    /** Around what is selected and the handles below it, or the long-pressed cell: the toolbar goes beside it. */
    private fun contentRect(out: Rect) {
        val range = view.selection?.normalized()
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (range != null) {
            val oneRow = range.startRow == range.endRow
            val columns = view.connection?.emulator?.mColumns ?: (range.endColumn + 1)
            left = host.columnLeft(if (oneRow) range.startColumn else 0)
            right = host.columnLeft(if (oneRow) range.endColumn + 1 else columns)
            top = host.rowTop(range.startRow)
            bottom = host.rowTop(range.endRow + 1) + 2 * radius
        } else {
            val cell = pressedCell ?: view.cellAt(downX, downY)
            left = host.columnLeft(cell.column)
            right = host.columnLeft(cell.column + 1)
            top = host.rowTop(cell.row)
            bottom = host.rowTop(cell.row + 1)
        }
        out.set(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
    }

    /** Paste needs a connection and text on the clipboard. Only its description is read, which Android doesn't announce. */
    private fun canPaste(): Boolean {
        if (view.connection?.isConnected != true) return false
        val clipboard = view.context.getSystemService(ClipboardManager::class.java) ?: return false
        return clipboard.primaryClipDescription?.hasMimeType("text/*") == true
    }

    private fun MenuItem.offer(available: Boolean) {
        isVisible = available
        isEnabled = available
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // The platform's own words, as in every other app's text toolbar
            menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.copy)?.offer(view.selection != null)
            menu.findItem(android.R.id.paste)?.offer(canPaste())
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                // A selection that couldn't be copied stays, so it can be made smaller
                android.R.id.copy -> if (view.copySelection()) view.clearSelection()
                android.R.id.paste -> {
                    view.paste()
                    view.clearSelection()
                }
                android.R.id.selectAll -> {
                    view.selectAll()
                    show()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (mode !== toolbar) return
            // Closed by the system, as when a selection elsewhere in the window takes over: this one goes
            toolbar = null
            host.select(null)
        }

        override fun onGetContentRect(mode: ActionMode, originatingView: View, outRect: Rect) {
            contentRect(outRect)
            reportedRect.set(outRect)
        }
    }

    companion object {
        /** Half of Android's 22dp text handle. */
        private const val HANDLE_RADIUS_DP = 11f

        /** What a finger can take a handle by, more than it shows: Material's 48dp. */
        private const val HANDLE_TARGET_DP = 48f

        /** The background-coloured edge around a handle. */
        private const val HANDLE_EDGE_DP = 1.5f

        /** How near the top or bottom edge a drag scrolls the history (and at least a row). */
        private const val EDGE_ZONE_DP = 24f

        /** A row every this many milliseconds while a drag scrolls the history. */
        private const val AUTO_SCROLL_INTERVAL_MS = 50L

        /** The contrast a handle needs against the background: WCAG's 3:1 for controls. */
        const val MIN_HANDLE_CONTRAST = 3.0

        private const val OPAQUE = 0xFF000000.toInt()

        /** Where black and white stand out from a background equally (relative luminance). */
        private const val MID_LUMINANCE = 0.179

        private const val LIGHTNESS_STEP = 0.02f

        /** Whether [event] is a finger or a stylus on the screen; a touchpad's fingers are not. */
        fun isOnScreen(event: MotionEvent): Boolean =
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || event.isFromSource(InputDevice.SOURCE_STYLUS)

        /**
         * The handles' colour: [cursor], or where it doesn't stand out from [background] by
         * [MIN_HANDLE_CONTRAST] (RemBrowse's accent on Paper, say), the nearest shade of it that
         * does: darker on a light background, lighter on a dark one.
         */
        fun handleColor(cursor: Int, background: Int): Int {
            val opaqueBackground = background or OPAQUE
            val color = cursor or OPAQUE
            if (ColorUtils.calculateContrast(color, opaqueBackground) >= MIN_HANDLE_CONTRAST) return color
            val darker = ColorUtils.calculateLuminance(opaqueBackground) > MID_LUMINANCE
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            while (if (darker) hsl[2] > 0f else hsl[2] < 1f) {
                hsl[2] = (hsl[2] + if (darker) -LIGHTNESS_STEP else LIGHTNESS_STEP).coerceIn(0f, 1f)
                val shade = ColorUtils.HSLToColor(hsl)
                if (ColorUtils.calculateContrast(shade, opaqueBackground) >= MIN_HANDLE_CONTRAST) return shade
            }
            return if (darker) Color.BLACK else Color.WHITE
        }
    }
}
