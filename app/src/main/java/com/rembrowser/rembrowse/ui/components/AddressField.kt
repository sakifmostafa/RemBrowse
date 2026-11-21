package com.rembrowser.rembrowse.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The word of an address around [offset]: the run of letters and digits between its punctuation,
 * so double-clicking `example` in `www.example.com/some-path` selects `example`, and `some` selects
 * `some`, as in desktop browsers' address bars. Compose's own word selection follows the rules for
 * prose, in which `www.example.com` and `some-path` are single words.
 *
 * Null when there is no letter or digit at or just before [offset] (a double click on a slash):
 * the selection is then left as it was.
 */
internal fun addressWordAt(text: String, offset: Int): TextRange? {
    if (text.isEmpty()) return null
    val at = offset.coerceIn(0, text.length)
    val anchor = when {
        at < text.length && isAddressWordChar(text[at]) -> at
        at > 0 && isAddressWordChar(text[at - 1]) -> at - 1
        else -> return null
    }
    var start = anchor
    while (start > 0 && isAddressWordChar(text[start - 1])) start--
    var end = anchor + 1
    while (end < text.length && isAddressWordChar(text[end])) end++
    return TextRange(start, end)
}

private fun isAddressWordChar(c: Char): Boolean = Character.isLetterOrDigit(c)

/** The selected text, or "" when nothing is selected. */
internal fun TextFieldValue.selectedText(): String = text.substring(selection.min, selection.max)

/** [insert] in place of the selection (or at the caret), with the caret after it. */
internal fun TextFieldValue.replaceSelection(insert: String): TextFieldValue {
    val start = selection.min
    val newText = text.substring(0, start) + insert + text.substring(selection.max)
    return TextFieldValue(newText, TextRange(start + insert.length))
}

/**
 * [newText] in place of the text, keeping the selection: all of it if all of the old text was
 * selected, otherwise the same positions as far as the new text reaches. For the moment focus
 * arrives and the address bar swaps its short form for the full address.
 */
internal fun TextFieldValue.withTextKeepingSelection(newText: String): TextFieldValue {
    val allSelected = text.isNotEmpty() && selection.min == 0 && selection.max == text.length
    val selection = if (allSelected) {
        TextRange(0, newText.length)
    } else {
        TextRange(selection.start.coerceAtMost(newText.length), selection.end.coerceAtMost(newText.length))
    }
    return TextFieldValue(newText, selection)
}

/**
 * Pasted text as an address bar takes it, on one line. Line breaks and the spaces around them
 * are dropped, so an address broken across lines in an email comes back whole (as Chrome does).
 */
internal fun pastedOnOneLine(text: String): String = text.replace(LineBreakRun, "").trim()

private val LineBreakRun = Regex("[ \\t]*[\\r\\n]+[ \\t]*")

/**
 * What the address bar needs to know about presses in it, kept between pointer events (main
 * thread only).
 *
 * Successive clicks are counted with Compose's own thresholds for text (the double-tap timeout,
 * and 50 dp), so a press counted as a double click here is one Compose selects a word for.
 */
internal class AddressFieldPresses {
    /** A press in the field is under way. */
    var pressed = false
        private set

    /** 1 for a single click, 2 for the second of a double click, and so on. */
    var clickCount = 0
        private set

    /** The pointer moved off the spot it pressed: a drag, whose selection is left alone. */
    var moved = false
        private set

    /** The address is to be selected once the press that gave the field focus is over. */
    var selectAllPending = false

    /** The field has focus, as the field itself last reported it. */
    var focused = false

    /** Where the caret last went by the user's hand: the spot a double click was aimed at. */
    var lastCaret: Int? = null

    /** The double click's word has been narrowed to the address's punctuation already. */
    var wordNarrowed = false

    private var lastPressAt = 0L
    private var lastPressPosition = Offset.Zero
    private var pressPosition = Offset.Zero

    fun press(uptimeMillis: Long, position: Offset, doubleClickMillis: Long, clickSlopPx: Float) {
        val again = clickCount > 0 &&
            uptimeMillis - lastPressAt <= doubleClickMillis &&
            (position - lastPressPosition).getDistance() <= clickSlopPx
        clickCount = if (again) clickCount + 1 else 1
        lastPressAt = uptimeMillis
        lastPressPosition = position
        pressPosition = position
        pressed = true
        moved = false
        wordNarrowed = false
    }

    fun move(position: Offset, slopPx: Float) {
        if ((position - pressPosition).getDistance() > slopPx) moved = true
    }

    fun release() {
        pressed = false
    }

    /** Whether a selection Compose just made is a double click's word, to be narrowed. */
    fun isDoubleClickWord(selection: TextRange): Boolean {
        val caret = lastCaret ?: return false
        return pressed && clickCount == 2 && !moved && !wordNarrowed && !selection.collapsed &&
            caret in selection.min..selection.max
    }
}
