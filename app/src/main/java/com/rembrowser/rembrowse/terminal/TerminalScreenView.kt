package com.rembrowser.rembrowse.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import com.termux.view.TerminalRenderer
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows a [TerminalConnection]'s screen and sends it what is typed.
 *
 * - **Keyboard**: every key goes to the shell, Ctrl and Alt combinations included (the browser's
 *   own shortcuts move to Ctrl+Shift while a terminal has focus), apart from copy and paste:
 *   Ctrl+C copies what is selected (with nothing selected it is the shell's), Ctrl+V pastes
 *   ([ctrlVPastes]), and Ctrl+Shift+C or Ctrl+Insert and Ctrl+Shift+V or Shift+Insert always do.
 *   Shift+Page Up and Page Down scroll the history.
 * - **Mouse and touchpad**: drag to select, double-click for a word, triple-click for a line, each
 *   copied when the button is let go ([copyOnSelect]); right-click for the menu ([onContextMenu]).
 *   When the program asks for the mouse (vim, htop, tmux with `mouse on`), presses and the wheel
 *   go to it instead; Shift still selects.
 * - **A program's copy** (OSC 52, as tmux and vim send) goes on the clipboard right after the
 *   user's input in this terminal ([programsCanCopy], [TerminalConnection.onCopyTextToClipboard]).
 * - **Wheel and two-finger scrolling** move through the history, or send arrow keys to full-screen
 *   programs that keep no history of their own (`less`, `man`).
 * - **Touchscreen** (and stylus): drag to scroll, tap to type (opens the on-screen keyboard),
 *   long-press to select, with handles and the floating Copy, Paste and Select all toolbar
 *   ([TouchSelection]). An on-screen keyboard's own Select all, Copy and Paste buttons work too.
 */
@SuppressLint("ViewConstructor")
class TerminalScreenView(context: Context) : View(context) {

    /** The terminal shown. Setting it starts at the bottom of its screen, with nothing selected. */
    var connection: TerminalConnection? = null
        set(value) {
            if (field === value) return
            field?.onScreenChanged = null
            field?.onProgramCopy = null
            field = value
            topRow = 0
            selection = null
            value?.emulator?.clearScrollCounter()
            value?.applyTheme()
            value?.setCursorStyle(cursorStyle.emulatorStyle)
            resetLine()
            value?.onScreenChanged = ::onScreenUpdated
            value?.onProgramCopy = ::copyFromProgram
            updateSize()
            invalidate()
        }

    /** A right click where the program has not asked for the mouse: the screen opens its menu here. */
    var onContextMenu: ((x: Float, y: Float) -> Unit)? = null

    /** Enter on a terminal that has disconnected: the screen offers to connect again. */
    var onReconnectRequested: (() -> Unit)? = null

    /**
     * Whether a program (tmux, vim) may put what it copies on the clipboard (OSC 52). Even then,
     * only right after the user's input in its terminal; see [TerminalConnection.onCopyTextToClipboard].
     */
    var programsCanCopy: Boolean = true

    /** Whether Ctrl+V pastes. Off, it goes to the program as ^V (vim's block selection, nano's next page). */
    var ctrlVPastes: Boolean = true

    /** Whether text selected with the mouse or touchpad is copied as soon as the button is let go. */
    var copyOnSelect: Boolean = true

    /** Text size in pixels. */
    var textSizePx: Float = 14f * resources.displayMetrics.scaledDensity
        set(value) {
            if (field == value) return
            field = value
            renderer = TerminalRenderer(value.roundToInt(), typeface)
            ghostPaint.textSize = value
            updateSize()
            invalidate()
        }

    /**
     * How terminals look: the [theme] (with [accentCursor], RemBrowse's accent, for its cursor
     * unless the theme has its own), the [font] and the [cursorStyle]. Themes apply to every
     * terminal; each picks the change up when it is next shown.
     */
    fun setAppearance(theme: TerminalThemePreset, accentCursor: Int, font: TerminalFont, cursorStyle: TerminalCursorStyle) {
        TerminalTheme.apply(theme, accentCursor)
        connection?.applyTheme()
        if (font != this.font) {
            this.font = font
            typeface = typefaceFor(font)
            renderer = TerminalRenderer(textSizePx.roundToInt(), typeface)
            ghostPaint.typeface = typeface
            updateSize()
        }
        this.cursorStyle = cursorStyle
        connection?.setCursorStyle(cursorStyle.emulatorStyle)
        invalidate()
    }

    private var font = TerminalFont.DEFAULT
    private var cursorStyle = TerminalCursorStyle.DEFAULT
    private var typeface: Typeface = typefaceFor(font)

    /** The bundled fonts (SIL Open Font License), or the system's monospace font. */
    private fun typefaceFor(font: TerminalFont): Typeface {
        val resource = when (font) {
            TerminalFont.JETBRAINS_MONO -> R.font.jetbrains_mono
            TerminalFont.FIRA_MONO -> R.font.fira_mono
            TerminalFont.SYSTEM -> return Typeface.MONOSPACE
        }
        return runCatching { resources.getFont(resource) }.getOrNull() ?: Typeface.MONOSPACE
    }

    /**
     * Suggests how the command being typed ends (the whole command), or null. Set while
     * suggestions are on; see [CommandSuggestions].
     */
    var suggest: ((typed: String) -> String?)? = null
        set(value) {
            field = value
            updateSuggestion()
        }

    /** A command was run at the prompt (Enter on a line the view followed), for the history. */
    var onCommandRun: ((String) -> Unit)? = null

    /** The suggestion shown after the cursor: the whole command, which starts with what was typed. */
    var suggestion: String? = null
        private set

    // The line being typed at the prompt, as far as the view can follow it: printable keys,
    // Backspace, Enter, Ctrl+C and Ctrl+U. Anything else (arrows, the shell's own Tab completion,
    // history, Ctrl+W) makes the line unknown until the next Enter, and nothing is suggested.
    private val typedLine = StringBuilder()
    private var lineFollowed = true

    private val ghostPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private var renderer = TerminalRenderer(textSizePx.roundToInt(), typeface)
    // Room around the text: a little more at the sides, where the header's title starts
    private val insetX = (16 * resources.displayMetrics.density).roundToInt()
    private val insetY = (12 * resources.displayMetrics.density).roundToInt()

    /** The first row shown: 0 for the live screen, negative while looking back through the history. */
    var topRow = 0
        private set

    /** What is selected, in screen cells (rows < 0 are in the history), or null. */
    var selection: TerminalSelection? = null
        private set(value) {
            field = value
            // However the selection goes (typing, a new size, another terminal, the mouse), touch
            // selection's handles and toolbar go with it
            if (value == null) touchSelection.end()
        }

    // Scrolling by finger or touchpad: what is left over from whole rows
    private var scrollRemainder = 0f
    private var lastTouchY = 0f
    private var touchMoved = false

    // Mouse selection
    private var selectionAnchor: Cell? = null
    private var lastClickAt = 0L
    private var lastClickCell: Cell? = null
    private var clickCount = 0
    private var mousePressedForProgram = false
    // The button now down selects text (not a press for the program, nor a right click), and
    // whether it has left the cell it went down on: a click that wobbles within its cell selects nothing
    private var mouseSelecting = false
    private var mouseDragged = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        TerminalTheme.ensureApplied()
        ghostPaint.typeface = typeface
    }

    val hasSelection: Boolean get() = selection != null

    // Zero only where text is not measured at all (a zero text size): nothing can be laid out then
    private val hasFontMetrics: Boolean get() = renderer.fontWidth > 0f && renderer.fontLineSpacing > 0

    // --- Size and drawing ---

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    private fun updateSize() {
        val terminal = connection ?: return
        val usableWidth = width - 2 * insetX
        val usableHeight = height - 2 * insetY
        if (usableWidth <= 0 || usableHeight <= 0 || !hasFontMetrics) return
        val columns = max(MIN_COLUMNS, (usableWidth / renderer.fontWidth).toInt())
        val rows = max(MIN_ROWS, (usableHeight - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing)
        // A new width rewraps the lines, so a selection's cells would point at other text (or
        // past the end of a row): it goes
        if (columns != terminal.emulator.mColumns || rows != terminal.emulator.mRows) {
            selection = null
            selectionAnchor = null
        }
        terminal.resize(columns, rows, renderer.fontWidth.roundToInt(), renderer.fontLineSpacing)
        clampTopRow()
    }

    override fun onDraw(canvas: Canvas) {
        val terminal = connection
        // Compose draws this view without clipping it, so stay inside its own bounds: a bare
        // drawColor would paint over the header above it
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(terminal?.emulator?.mColors?.mCurrentColors?.get(com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND) ?: TerminalTheme.BACKGROUND)
        // Nothing printed yet (connecting, or a question on top): a clean background, no lone cursor
        if (terminal == null || !terminal.hasOutput) {
            canvas.restore()
            return
        }
        canvas.translate(insetX.toFloat(), insetY.toFloat())
        val selected = selection?.normalized()
        renderer.render(
            terminal.emulator,
            canvas,
            topRow,
            selected?.startRow ?: -1,
            selected?.endRow ?: -1,
            selected?.startColumn ?: -1,
            selected?.endColumn ?: -1
        )
        drawSuggestion(canvas, terminal.emulator)
        canvas.restore()
        touchSelection.draw(canvas)
    }

    /**
     * The rest of the suggested command, dimmed (how much depends on the theme), from the cursor
     * on; and a "Tab" keycap after it while there is room and more than a couple of letters to take.
     */
    private fun drawSuggestion(canvas: Canvas, emulator: TerminalEmulator) {
        val whole = suggestion ?: return
        if (topRow != 0 || !hasFocus() || !hasFontMetrics) return
        val room = emulator.mColumns - emulator.cursorCol
        if (room <= 0) return
        // As much of the rest as fits on the row, in cells: wide characters (CJK, emoji) take two
        val (rest, cells) = fitToCells(whole.substring(typedLine.length), room)
        if (rest.isEmpty()) return
        val colors = emulator.mColors.mCurrentColors
        val foreground = colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND]
        val background = colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
        val lineSpacing = renderer.fontLineSpacing.toFloat()
        val baseline = (emulator.cursorRow + 1) * lineSpacing
        val left = emulator.cursorCol * renderer.fontWidth
        ghostPaint.textSize = textSizePx
        ghostPaint.color = withAlpha(foreground, TerminalTheme.current.ghostAlpha)
        canvas.drawText(rest, left, baseline, ghostPaint)
        // A block cursor covers the first letter: draw it again on top, as the cursor shows text
        if (emulator.cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK && emulator.shouldCursorBeVisible()) {
            val first = rest.substring(0, Character.charCount(rest.codePointAt(0)))
            ghostPaint.color = background or (0xFF shl 24)
            canvas.drawText(first, left, baseline, ghostPaint)
        }

        // The "Tab" keycap: not for the last letter or two, nor over a selection
        if (rest.codePointCount(0, rest.length) < MIN_GHOST_FOR_HINT || selection != null) return
        val hint = "Tab"
        hintPaint.typeface = typeface
        hintPaint.textSize = textSizePx * 0.78f
        val padding = renderer.fontWidth * 0.6f
        val capLeft = left + (cells + 1.5f) * renderer.fontWidth
        val capWidth = hintPaint.measureText(hint) + 2 * padding
        if (capLeft + capWidth > emulator.mColumns * renderer.fontWidth) return
        val rowCenter = renderer.fontLineSpacingAndAscent + (emulator.cursorRow + 0.5f) * lineSpacing
        val capHeight = lineSpacing * 0.78f
        val capTop = rowCenter - capHeight / 2f
        val radius = 4 * resources.displayMetrics.density
        hintPaint.style = android.graphics.Paint.Style.FILL
        hintPaint.color = withAlpha(foreground, 0x14)
        canvas.drawRoundRect(capLeft, capTop, capLeft + capWidth, capTop + capHeight, radius, radius, hintPaint)
        hintPaint.style = android.graphics.Paint.Style.STROKE
        hintPaint.strokeWidth = resources.displayMetrics.density
        hintPaint.color = withAlpha(foreground, 0x40)
        canvas.drawRoundRect(capLeft, capTop, capLeft + capWidth, capTop + capHeight, radius, radius, hintPaint)
        hintPaint.style = android.graphics.Paint.Style.FILL
        hintPaint.color = withAlpha(foreground, 0xB3)
        val metrics = hintPaint.fontMetrics
        canvas.drawText(hint, capLeft + padding, rowCenter - (metrics.ascent + metrics.descent) / 2f, hintPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** The start of [text] that fits in [room] cells, whole characters only, and the cells it takes. */
    private fun fitToCells(text: String, room: Int): Pair<String, Int> {
        var cells = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val width = WcWidth.width(codePoint).coerceAtLeast(0)
            if (cells + width > room) break
            cells += width
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end) to cells
    }

    private fun onScreenUpdated() {
        val emulator = connection?.emulator ?: return
        // Output scrolled the screen by this many rows: whatever is being looked at or selected
        // in the history moves up with it, so it stays in place on screen
        val scrolled = emulator.scrollCounter
        if (scrolled != 0) {
            if (topRow < 0) topRow -= scrolled
            selection = selection?.shiftedUp(scrolled)
            emulator.clearScrollCounter()
        }
        clampTopRow()
        updateSuggestion()
        invalidate()
    }

    private fun clampTopRow() {
        val emulator = connection?.emulator ?: return
        val history = emulator.screen.activeTranscriptRows
        topRow = topRow.coerceIn(-history, 0)
    }

    // --- Keyboard ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password text: on-screen keyboards then offer no suggestions or corrections,
        // which would otherwise rewrite commands as they are typed
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(MAX_DELETE)) { sendToShell("\u007f") }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            // The keyboard's own Select all, Copy and Paste buttons (Gboard's text editing panel,
            // some keyboards' clipboard) do what the terminal's menu does. Text can't be taken
            // back out of a terminal, so Cut copies.
            override fun performContextMenuAction(id: Int): Boolean = when (id) {
                android.R.id.selectAll -> {
                    selectAll()
                    true
                }
                android.R.id.copy, android.R.id.cut -> {
                    copySelection()
                    true
                }
                android.R.id.paste, android.R.id.pasteAsPlainText -> {
                    paste()
                    true
                }
                else -> super.performContextMenuAction(id)
            }

            private fun sendEditable() {
                val content = editable ?: return
                val text = content.toString()
                content.clear()
                when {
                    text.isEmpty() -> Unit
                    // The keyboard's Enter
                    text == "\n" -> sendToShell("\r")
                    // Several lines or control characters: a paste from the keyboard's clipboard.
                    // It goes in as a paste, so a shell with bracketed paste doesn't run it line
                    // by line, and escape sequences in it are dropped
                    text.any { it == '\n' || it == '\r' || it.isISOControl() } -> pasteText(text)
                    else -> sendToShell(text)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val terminal = connection ?: return super.onKeyDown(keyCode, event)
        if (handleViewShortcut(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_BACK || event.isSystem && keyCode != KeyEvent.KEYCODE_ENTER) {
            return super.onKeyDown(keyCode, event)
        }
        if (!terminal.isConnected) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (terminal.status.value is TerminalStatus.Disconnected) onReconnectRequested?.invoke()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // Tab (or → at the end of the line) takes the suggestion; without one, Tab is the shell's
        val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
        if (plain && (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && acceptSuggestion()) return true
        val code = encodeKey(keyCode, event, terminal.emulator, deadKey) ?: return super.onKeyDown(keyCode, event)
        if (code.isNotEmpty()) sendToShell(code)
        return true
    }

    /** An accent typed with a dead key, waiting for its letter. */
    private val deadKey = DeadKey()

    /** Types the rest of the suggestion. False when there is none. */
    fun acceptSuggestion(): Boolean {
        val whole = suggestion ?: return false
        sendToShell(whole.substring(typedLine.length))
        return true
    }

    /** Follows [text] being sent from the keyboard into the line typed at the prompt. */
    private fun followTyped(text: String) {
        for (char in text) {
            when {
                char == '\r' -> {
                    val command = typedLine.toString()
                    // Only a line the screen shows: what is typed at a prompt that doesn't echo
                    // (a password) is never remembered. The last few keys may not have come back
                    // over the network yet
                    val shown = connection?.emulator?.let {
                        !it.isAlternateBufferActive && echoedAtCursor(it, command, notYetEchoed = MAX_PENDING_ECHO)
                    } == true
                    if (lineFollowed && command.isNotBlank() && shown) onCommandRun?.invoke(command)
                    resetLine()
                }
                char == '\u0003' || char == '\u0015' -> resetLine()
                char == '\u007f' || char == '\b' -> if (typedLine.isNotEmpty()) {
                    val end = typedLine.length
                    typedLine.delete(Character.offsetByCodePoints(typedLine, end, -1), end)
                }
                // Escape sequences (arrows, Alt+…), Tab, Ctrl+W and the like: the line can't be
                // followed until the next Enter
                char.isISOControl() -> lineFollowed = false
                lineFollowed -> typedLine.append(char)
            }
        }
    }

    private fun resetLine() {
        typedLine.clear()
        lineFollowed = true
        if (suggestion != null) {
            suggestion = null
            invalidate()
        }
    }

    /**
     * Works out what to suggest for the line typed so far. Only when the screen shows that line
     * just before the cursor with nothing after it: a prompt that hides what is typed (a password)
     * or a full-screen program gets no suggestion.
     */
    private fun updateSuggestion() {
        val next = run {
            val suggest = suggest ?: return@run null
            val emulator = connection?.emulator ?: return@run null
            if (!lineFollowed || typedLine.isEmpty() || emulator.isAlternateBufferActive) return@run null
            if (!echoedAtCursor(emulator, typedLine.toString())) return@run null
            suggest(typedLine.toString())?.takeIf { it.length > typedLine.length && it.startsWith(typedLine) }
        }
        if (next != suggestion) {
            suggestion = next
            invalidate()
        }
    }

    /**
     * Whether the screen shows [typed] just before the cursor, with nothing after it. Up to
     * [notYetEchoed] of the last characters may be missing (still on their way back), as long as
     * at least three, or all of a shorter line, are there.
     */
    private fun echoedAtCursor(emulator: TerminalEmulator, typed: String, notYetEchoed: Int = 0): Boolean {
        val row = emulator.cursorRow
        val column = emulator.cursorCol
        if (column <= 0) return false
        val after = if (column < emulator.mColumns) emulator.screen.getSelectedText(column, row, emulator.mColumns - 1, row) else ""
        if (after.isNotBlank()) return false
        val shown = emulator.screen.getSelectedText(0, row, column - 1, row).trimEnd()
        val shortest = maxOf(typed.length - notYetEchoed, minOf(typed.length, 3))
        return (typed.length downTo shortest).any { length ->
            val part = typed.substring(0, length).trimEnd()
            part.isNotEmpty() && shown.endsWith(part)
        }
    }

    /** Copy, paste and scrolling the history: the keys a terminal keeps for itself. */
    private fun handleViewShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val ctrlShift = event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed
        val ctrl = event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed
        return when {
            ctrlShift && keyCode == KeyEvent.KEYCODE_C || ctrl && keyCode == KeyEvent.KEYCODE_INSERT -> {
                copySelection()
                true
            }
            // False: the shell's ^C
            ctrl && keyCode == KeyEvent.KEYCODE_C -> ctrlCCopies(event)
            ctrlShift && keyCode == KeyEvent.KEYCODE_V || event.isShiftPressed && !event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_INSERT -> {
                paste()
                true
            }
            // Never ^V while it pastes, even with nothing to paste: bash would take the next key,
            // or the escape that starts the next paste, literally
            ctrl && keyCode == KeyEvent.KEYCODE_V && ctrlVPastes -> {
                paste()
                true
            }
            event.isShiftPressed && !event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_PAGE_UP -> {
                scrollHistory(-(connection?.emulator?.mRows ?: 1))
                true
            }
            event.isShiftPressed && !event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> {
                scrollHistory(connection?.emulator?.mRows ?: 1)
                true
            }
            else -> false
        }
    }

    /**
     * Whether Ctrl+C copies, as in other apps, instead of interrupting the program: only with a
     * selection that is at least partly on screen and has text. It is copied and cleared, so the
     * next Ctrl+C interrupts; one that couldn't be copied (too long) stays, so pressing again
     * can't interrupt by surprise. The press's key repeats are taken too, or holding the keys a
     * moment too long would interrupt right after the copy. A selection out of sight (output
     * pushed it into the history, or the view scrolled away from it) is cleared instead, and the
     * key goes to the program: a Ctrl+C meant to stop something must never just copy.
     */
    private fun ctrlCCopies(event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return event.downTime == ctrlCTakenPress
        ctrlCTakenPress = null
        val range = selection?.normalized() ?: return false
        val rows = connection?.emulator?.mRows ?: return false
        val onScreen = range.endRow >= topRow && range.startRow <= topRow + rows - 1
        val text = if (onScreen) selectedText() else null
        if (text == null) {
            clearSelection()
            return false
        }
        if (putOnClipboard(text)) clearSelection()
        ctrlCTakenPress = event.downTime
        return true
    }

    // The Ctrl+C press (its down time) taken for copying, whether or not the clipboard took the
    // text: its key repeats are taken too
    private var ctrlCTakenPress: Long? = null

    private fun sendToShell(text: String) {
        val terminal = connection ?: return
        if (!terminal.isConnected) return
        // Typing goes back to the live screen
        if (topRow != 0 || selection != null) {
            topRow = 0
            selection = null
            invalidate()
        }
        // The toolbar of a long-press on blank space has no selection to clear: it goes too
        touchSelection.end()
        followTyped(text)
        terminal.sendInput(text.toByteArray(StandardCharsets.UTF_8))
        updateSuggestion()
    }

    // --- Pointer ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val terminal = connection ?: return false
        val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        // The mouse takes over: touch selection's handles and toolbar go (a right click keeps the
        // selection itself, for the menu's Copy)
        if (isMouse && event.actionMasked == MotionEvent.ACTION_DOWN) touchSelection.end()
        if (isMouse) return onMouseEvent(event, terminal.emulator)
        // A finger tap: type here
        if (onFingerEvent(event)) performClick()
        return true
    }

    private fun onMouseEvent(event: MotionEvent, emulator: TerminalEmulator): Boolean {
        val cell = cellAt(event.x, event.y)
        val toProgram = emulator.isMouseTrackingActive && event.metaState and KeyEvent.META_SHIFT_ON == 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val secondary = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                mouseSelecting = false
                mouseDragged = false
                when {
                    toProgram -> {
                        mousePressedForProgram = true
                        val button = if (secondary) MOUSE_RIGHT_BUTTON else TerminalEmulator.MOUSE_LEFT_BUTTON
                        emulator.sendMouseEvent(button, cell.column + 1, cell.row - topRow + 1, true)
                        connection?.markUserInput()
                    }
                    secondary -> onContextMenu?.invoke(event.x, event.y)
                    else -> {
                        startSelection(cell, event.eventTime)
                        mouseSelecting = true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when {
                mousePressedForProgram -> {
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, cell.column + 1, cell.row - topRow + 1, true)
                    connection?.markUserInput()
                }
                selectionAnchor != null -> {
                    if (cell != selectionAnchor) mouseDragged = true
                    if (mouseDragged) extendSelection(cell)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mousePressedForProgram) {
                    mousePressedForProgram = false
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, cell.column + 1, cell.row - topRow + 1, false)
                    connection?.markUserInput()
                }
                // Copied as the button is let go, as tmux and ChromeOS's terminal do: a drag, or a
                // word or line picked with a double or triple click. A plain click copies nothing.
                val selected = mouseSelecting && (mouseDragged || clickCount > 1)
                if (event.actionMasked == MotionEvent.ACTION_UP && selected && copyOnSelect) copySelection()
                mouseSelecting = false
                selectionAnchor = null
            }
        }
        return true
    }

    private fun startSelection(cell: Cell, time: Long) {
        val again = lastClickCell?.let { it.row == cell.row && abs(it.column - cell.column) <= 1 } == true &&
            time - lastClickAt <= ViewConfiguration.getDoubleTapTimeout()
        clickCount = if (again) clickCount + 1 else 1
        lastClickAt = time
        lastClickCell = cell
        selection = when (clickCount) {
            1 -> null
            2 -> wordAt(cell)
            else -> lineAt(cell)
        }
        selectionAnchor = cell.takeIf { clickCount == 1 }
        invalidate()
    }

    private fun extendSelection(cell: Cell) {
        val anchor = selectionAnchor ?: return
        selection = TerminalSelection(anchor.column, anchor.row, cell.column, cell.row)
        invalidate()
    }

    /**
     * A finger's event; true for a tap. Fingers and styluses on the screen go to [touchSelection]:
     * a drag scrolls, a long-press selects. A touchpad's fingers only scroll, here.
     */
    private fun onFingerEvent(event: MotionEvent): Boolean {
        if (TouchSelection.isOnScreen(event)) return touchSelection.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                scrollRemainder = 0f
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - event.y
                lastTouchY = event.y
                if (abs(dy) > 0f) touchMoved = true
                scrollRemainder += dy
                val rows = if (hasFontMetrics) (scrollRemainder / renderer.fontLineSpacing).toInt() else 0
                if (rows != 0) {
                    scrollRemainder -= rows * renderer.fontLineSpacing
                    scrollBy(rows)
                }
            }
            MotionEvent.ACTION_UP -> return !touchMoved && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        }
        return false
    }

    /** A tap, or an accessibility service's click: the keyboard comes up to type into the terminal. */
    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (notches != 0f) {
                val rows = -(notches * WHEEL_ROWS).roundToInt().let { if (it == 0) (if (notches > 0) 1 else -1) else it }
                scrollBy(rows)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * The browser's own chrome has the pointer: a card or banner drawn over this view, or a pane
     * divider's grab area reaching over it. The cursor is then the chrome's (an arrow over
     * buttons), not the text cursor. Set by TerminalPage from the window's cursor claim.
     */
    var chromeHasPointer: () -> Boolean = { false }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (chromeHasPointer()) return null
        val program = connection?.emulator?.isMouseTrackingActive == true
        return if (program) super.onResolvePointerIcon(event, pointerIndex) else PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TEXT)
    }

    /**
     * Scrolls by [rows] (positive toward the newest output). A program that asked for the mouse
     * gets wheel events; a full-screen program without history (`less`) gets arrow keys; at the
     * shell, the view moves through the history.
     */
    fun scrollBy(rows: Int) {
        val emulator = connection?.emulator ?: return
        when {
            emulator.isMouseTrackingActive -> {
                val button = if (rows < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                repeat(abs(rows)) { emulator.sendMouseEvent(button, 1, 1, true) }
                // The arrow keys below count by themselves, through sendInput
                connection?.markUserInput()
            }
            emulator.isAlternateBufferActive -> {
                val key = if (rows < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                val code = KeyHandler.getCode(key, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                if (code != null && connection?.isConnected == true) repeat(abs(rows)) { connection?.sendInput(code) }
            }
            else -> scrollHistory(rows)
        }
    }

    private fun scrollHistory(rows: Int) {
        topRow += rows
        clampTopRow()
        invalidate()
    }

    // --- Selection, copy and paste ---

    /** Copies what is selected. False when nothing is, or it couldn't be copied (the user is told why). */
    fun copySelection(): Boolean {
        val text = selectedText() ?: return false
        return putOnClipboard(text)
    }

    /** A program's copy (OSC 52) that its terminal let through, if Settings let programs copy. */
    private fun copyFromProgram(text: String) {
        if (programsCanCopy) putOnClipboard(text)
    }

    /** Puts [text] on the clipboard. False, after a short message, when it is too long or the system refuses it. */
    private fun putOnClipboard(text: String): Boolean {
        if (text.length > MAX_COPY_CHARS) {
            Toast.makeText(context, "This selection is too long to copy", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
            true
        } catch (e: RuntimeException) {
            // The system refused the clip (a binder transaction too large, say): no crash for it
            Log.w(TAG, "Couldn't copy ${text.length} characters", e)
            Toast.makeText(context, "Couldn't copy the text", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun selectedText(): String? {
        val emulator = connection?.emulator ?: return null
        val range = selection?.normalized() ?: return null
        return emulator.screen.getSelectedText(range.startColumn, range.startRow, range.endColumn, range.endRow)
            .takeIf { it.isNotEmpty() }
    }

    fun paste() {
        val terminal = connection ?: return
        if (!terminal.isConnected) return
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context).toString() }
        pasteText(text)
    }

    /** Pastes [text] into the shell, as the emulator does: bracketed when the shell asks for it. */
    private fun pasteText(text: String) {
        val terminal = connection ?: return
        if (!terminal.isConnected || text.isEmpty()) return
        topRow = 0
        selection = null
        // Pasted text isn't followed: suggestions wait for the next line
        lineFollowed = false
        suggestion = null
        invalidate()
        terminal.emulator.paste(text)
        // It reaches the server through the emulator, so it doesn't count as input by itself
        terminal.markUserInput()
    }

    /** Selects the whole history and screen. */
    fun selectAll() {
        val emulator = connection?.emulator ?: return
        selection = TerminalSelection(0, -emulator.screen.activeTranscriptRows, emulator.mColumns - 1, emulator.mRows - 1)
        invalidate()
    }

    fun clearSelection() {
        selection = null
        invalidate()
    }

    /** The cell under a point of the view; rows count from the live screen's top (negative in the history). */
    fun cellAt(x: Float, y: Float): Cell {
        val emulator = connection?.emulator
        val columns = emulator?.mColumns ?: 1
        val rows = emulator?.mRows ?: 1
        if (!hasFontMetrics) return Cell(0, topRow)
        val column = ((x - insetX) / renderer.fontWidth).toInt().coerceIn(0, columns - 1)
        val row = ((y - insetY - renderer.fontLineSpacingAndAscent) / renderer.fontLineSpacing).toInt().coerceIn(0, rows - 1)
        return Cell(column, row + topRow)
    }

    private fun cellText(column: Int, row: Int): String =
        connection?.emulator?.screen?.getSelectedText(column, row, column, row).orEmpty()

    private fun wordAt(cell: Cell): TerminalSelection? {
        val columns = connection?.emulator?.mColumns ?: return null
        fun isWordCell(column: Int) = cellText(column, cell.row).let { it.isNotBlank() && it.none { c -> c in WORD_STOPS } }
        if (!isWordCell(cell.column)) return null
        var start = cell.column
        while (start > 0 && isWordCell(start - 1)) start--
        var end = cell.column
        while (end < columns - 1 && isWordCell(end + 1)) end++
        return TerminalSelection(start, cell.row, end, cell.row)
    }

    private fun lineAt(cell: Cell): TerminalSelection? {
        val columns = connection?.emulator?.mColumns ?: return null
        return TerminalSelection(0, cell.row, columns - 1, cell.row)
    }

    // --- Touch selection ---

    /**
     * Selecting with a finger or a stylus: a long-press, the handles and the floating toolbar.
     * Internal for tests, which drive its gestures and toolbar.
     */
    internal val touchSelection = TouchSelection(this, object : TouchSelection.Host {
        override fun select(selection: TerminalSelection?) {
            this@TerminalScreenView.selection = selection
            invalidate()
        }

        override fun wordAt(cell: Cell): TerminalSelection? = this@TerminalScreenView.wordAt(cell)

        override fun scrollHistory(rows: Int) = this@TerminalScreenView.scrollHistory(rows)

        override fun columnLeft(column: Int): Float = insetX + column * renderer.fontWidth

        override fun rowTop(row: Int): Float =
            insetY + renderer.fontLineSpacingAndAscent + (row - topRow) * renderer.fontLineSpacing.toFloat()
    })

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The toolbar and a long-press still to come belong to the window this view just left
        touchSelection.detached()
    }

    /** A screen cell: [row] counts from the live screen's top, negative in the history. */
    data class Cell(val column: Int, val row: Int)

    companion object {
        private const val MIN_COLUMNS = 4
        private const val MIN_ROWS = 4
        private const val WHEEL_ROWS = 3
        private const val MAX_DELETE = 64
        private const val MOUSE_RIGHT_BUTTON = 2

        /** The "Tab" keycap shows only while at least this much of a suggestion is left to take. */
        private const val MIN_GHOST_FOR_HINT = 3

        /** How many of a command's last keys may still be on their way back when Enter is pressed. */
        private const val MAX_PENDING_ECHO = 3

        /** Characters a double-click word stops at, besides spaces: brackets and quotes. */
        private const val WORD_STOPS = "()[]{}<>\"'`|;,"

        /**
         * The most text copied at once. The clip goes to the system in one binder transaction,
         * and one over about 1 MB fails with an exception that would end the app. Text takes one
         * to three bytes a character there, so 256K characters stays below that, while Select all
         * over a full history (5,000 rows of a wide window) can be several times as much. A
         * program's copy is never this long: the emulator keeps at most 100 KB of an OSC 52.
         */
        private const val MAX_COPY_CHARS = 256 * 1024

        private const val TAG = "TerminalScreenView"

        /**
         * What a key press sends to the shell, or null when it is not for the shell (the view
         * passes it on). Ctrl turns letters into control characters, and Alt sends Escape first
         * (so Alt+B and Alt+F move by words in the shell).
         */
        fun encodeKey(keyCode: Int, event: KeyEvent, emulator: TerminalEmulator, deadKey: DeadKey = DeadKey()): String? {
            var keyMod = 0
            if (event.isCtrlPressed) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
            if (event.isAltPressed) keyMod = keyMod or KeyHandler.KEYMOD_ALT
            if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
            if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
            KeyHandler.getCode(keyCode, keyMod, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                ?.let { return it }

            // Right Alt is AltGr on many layouts (German @, { and }, …): what the layout types
            // with it is the character, not Alt plus a letter. Only where it types nothing is it Alt.
            val ctrlAndMeta = KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK
            var alt = event.isAltPressed
            var unicode = 0
            if (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0) {
                unicode = event.getUnicodeChar(event.metaState and (ctrlAndMeta or KeyEvent.META_ALT_LEFT_ON).inv())
                if (unicode != 0) alt = event.metaState and KeyEvent.META_ALT_LEFT_ON != 0
            }
            if (unicode == 0) unicode = event.getUnicodeChar(event.metaState and (ctrlAndMeta or KeyEvent.META_ALT_MASK).inv())
            if (unicode == 0) return null

            // A dead key: the accent waits for the next letter (´ then e is é; ´ then space is ´)
            if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
                deadKey.accent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
                return ""
            }
            if (deadKey.accent != 0) {
                val combined = KeyCharacterMap.getDeadChar(deadKey.accent, unicode)
                deadKey.accent = 0
                if (combined != 0) unicode = combined
            }
            return encodeCodePoint(unicode, event.isCtrlPressed, alt)
        }

        /** [codePoint] as typed with [ctrl] and [alt] held. */
        fun encodeCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean): String {
            var cp = codePoint
            if (ctrl) {
                cp = when (cp) {
                    in 'a'.code..'z'.code -> cp - 'a'.code + 1
                    in 'A'.code..'Z'.code -> cp - 'A'.code + 1
                    ' '.code, '2'.code, '@'.code -> 0
                    '['.code, '3'.code -> 27
                    '\\'.code, '4'.code -> 28
                    ']'.code, '5'.code -> 29
                    '^'.code, '6'.code -> 30
                    '_'.code, '7'.code, '/'.code -> 31
                    '8'.code, '?'.code -> 127
                    else -> cp
                }
            }
            val text = String(Character.toChars(cp))
            return if (alt) "\u001b$text" else text
        }
    }
}

/** The accent of a dead key that was pressed, waiting to combine with the next letter; 0 for none. */
class DeadKey {
    var accent: Int = 0
}

/** A run of text on the terminal, from one cell to another (inclusive), in either direction. */
data class TerminalSelection(val anchorColumn: Int, val anchorRow: Int, val focusColumn: Int, val focusRow: Int) {

    /** From the earlier cell to the later one. */
    fun normalized(): Range {
        val anchorFirst = anchorRow < focusRow || anchorRow == focusRow && anchorColumn <= focusColumn
        return if (anchorFirst) {
            Range(anchorColumn, anchorRow, focusColumn, focusRow)
        } else {
            Range(focusColumn, focusRow, anchorColumn, anchorRow)
        }
    }

    fun shiftedUp(rows: Int) = copy(anchorRow = anchorRow - rows, focusRow = focusRow - rows)

    data class Range(val startColumn: Int, val startRow: Int, val endColumn: Int, val endRow: Int)
}
