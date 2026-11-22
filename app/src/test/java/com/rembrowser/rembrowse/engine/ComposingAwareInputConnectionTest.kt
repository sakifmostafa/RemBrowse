package com.rembrowser.rembrowse.engine

import android.app.Application
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Enter past the keyboard app needs to know whether a word is still being composed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ComposingAwareInputConnectionTest {

    /** Records what reaches the page's own input connection. */
    private class Recorder : InputConnectionWrapper(null, true) {
        val calls = mutableListOf<String>()
        var selectedText: CharSequence? = null
        var failComposing = false
        override fun getSelectedText(flags: Int): CharSequence? = selectedText
        override fun beginBatchEdit(): Boolean {
            calls += "beginBatchEdit"
            return true
        }
        override fun endBatchEdit(): Boolean {
            calls += "endBatchEdit"
            return true
        }
        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.TIRAMISU)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
            calls += "setComposingText+attr($text,$newCursorPosition)"
            if (failComposing) throw IllegalStateException("boom")
            return true
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += "setComposingText($text,$newCursorPosition)"
            if (failComposing) throw IllegalStateException("boom")
            return true
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            calls += "setComposingRegion($start,$end)"
            return true
        }
        override fun finishComposingText(): Boolean {
            calls += "finishComposingText"
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += "commitText($text,$newCursorPosition)"
            return true
        }
    }

    private val target = Recorder()
    private val connection = ComposingAwareInputConnection(target as InputConnection)

    @Test
    fun startsNotComposing_andComposingTextStartsIt() {
        assertFalse(connection.composing)
        assertTrue(connection.setComposingText("hel", 1))
        assertTrue(connection.composing)
        connection.setComposingText("hello", 1)
        assertTrue(connection.composing)
    }

    @Test
    fun emptyComposingText_endsIt() {
        connection.setComposingText("hel", 1)
        connection.setComposingText("", 1)
        assertFalse(connection.composing)
    }

    @Test
    fun committingOrFinishing_endsIt() {
        connection.setComposingText("hel", 1)
        connection.commitText("hello", 1)
        assertFalse(connection.composing)

        connection.setComposingText("wor", 1)
        assertTrue(connection.composing)
        connection.finishComposingText()
        assertFalse(connection.composing)
    }

    @Test
    fun composingRegion_isComposingOnlyWhenItCoversText() {
        connection.setComposingRegion(2, 2)
        assertFalse(connection.composing)
        connection.setComposingRegion(0, 3)
        assertTrue(connection.composing)
        connection.setComposingRegion(4, 4)
        assertFalse(connection.composing)
    }

    @Test
    fun everyCall_isForwardedToThePagesConnection() {
        connection.setComposingText("hel", 1)
        connection.setComposingRegion(0, 3)
        connection.commitText("hello", 1)
        connection.finishComposingText()
        assertEquals(
            listOf("setComposingText(hel,1)", "setComposingRegion(0,3)", "commitText(hello,1)", "finishComposingText"),
            target.calls
        )
    }

    private val attribute: android.view.inputmethod.TextAttribute? = null

    @Test
    fun newWordOverASelection_deletesTheSelectionInOneBatchFirst() {
        target.selectedText = "old"
        assertTrue(connection.setComposingText("n", 1))
        assertEquals(
            listOf("beginBatchEdit", "commitText(,1)", "setComposingText(n,1)", "endBatchEdit"),
            target.calls
        )
        assertTrue(connection.composing)
    }

    @Test
    fun newWordWithNoSelection_onlySetsTheComposingText() {
        target.selectedText = ""
        connection.setComposingText("n", 1)
        assertEquals(listOf("setComposingText(n,1)"), target.calls)
        target.calls.clear()
        target.selectedText = null
        connection.finishComposingText()
        target.calls.clear()
        connection.setComposingText("m", 1)
        assertEquals(listOf("setComposingText(m,1)"), target.calls)
    }

    @Test
    fun alreadyComposingWithASelection_onlySetsTheComposingText() {
        connection.setComposingText("n", 1)
        target.calls.clear()
        target.selectedText = "old"
        connection.setComposingText("ne", 1)
        assertEquals(listOf("setComposingText(ne,1)"), target.calls)
        assertTrue(connection.composing)
    }

    @Test
    fun emptyComposingText_deletesNothingEvenOverASelection() {
        target.selectedText = "old"
        connection.setComposingText("", 1)
        assertEquals(listOf("setComposingText(,1)"), target.calls)
        assertFalse(connection.composing)
    }

    @Test
    fun theBatchIsEnded_evenWhenSettingTheComposingTextThrows() {
        target.selectedText = "old"
        target.failComposing = true
        try {
            connection.setComposingText("n", 1)
            org.junit.Assert.fail("expected the failure to propagate")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(
            listOf("beginBatchEdit", "commitText(,1)", "setComposingText(n,1)", "endBatchEdit"),
            target.calls
        )
    }

    @Test
    fun theTextAttributeOverload_behavesTheSame() {
        target.selectedText = "old"
        assertTrue(connection.setComposingText("n", 1, attribute))
        assertEquals(
            listOf("beginBatchEdit", "commitText(,1)", "setComposingText+attr(n,1)", "endBatchEdit"),
            target.calls
        )
        assertTrue(connection.composing)

        target.calls.clear()
        connection.setComposingText("ne", 1, attribute)
        assertEquals("Already composing", listOf("setComposingText+attr(ne,1)"), target.calls)

        target.calls.clear()
        connection.finishComposingText()
        target.calls.clear()
        connection.setComposingText("", 1, attribute)
        assertEquals("Empty text deletes nothing", listOf("setComposingText+attr(,1)"), target.calls)
        assertFalse(connection.composing)
    }

    @Test
    fun theTextAttributeOverload_endsTheBatchWhenItThrows() {
        target.selectedText = "old"
        target.failComposing = true
        try {
            connection.setComposingText("n", 1, attribute)
            org.junit.Assert.fail("expected the failure to propagate")
        } catch (expected: IllegalStateException) {
        }
        assertEquals("endBatchEdit", target.calls.last())
    }

    /** A BaseInputConnection over an editable the test controls, recording what reaches it. */
    private class EditableTarget(view: android.view.View, text: String) : android.view.inputmethod.BaseInputConnection(view, true) {
        val text = android.text.SpannableStringBuilder(text)
        val calls = mutableListOf<String>()
        override fun getEditable(): android.text.Editable = text
        override fun beginBatchEdit(): Boolean {
            calls += "begin"
            return true
        }
        override fun endBatchEdit(): Boolean {
            calls += "end"
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += "commit($text,$newCursorPosition)"
            return true
        }
        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.TIRAMISU)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
            calls += "commit+attr($text,$newCursorPosition)"
            return true
        }
    }

    private val view = android.view.View(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>())

    private fun editable(text: String, anchor: Int, focus: Int): EditableTarget =
        EditableTarget(view, text).also { android.text.Selection.setSelection(it.text, anchor, focus) }

    private fun ComposingAwareInputConnection.commitAs(overload: Boolean, text: CharSequence?, cursor: Int): Boolean =
        if (overload && text != null) commitText(text, cursor, attribute) else commitText(text, cursor)

    private fun assertReplaced(target: EditableTarget, expected: String, caret: Int) {
        assertEquals(expected, target.text.toString())
        assertEquals(caret, android.text.Selection.getSelectionStart(target.text))
        assertEquals("Collapsed", caret, android.text.Selection.getSelectionEnd(target.text))
        assertEquals("The target's own commitText is not used", listOf("begin", "end"), target.calls)
    }

    @Test
    fun commitOverASelection_replacesItInOneBatch_withoutTheTargetsCommitText() {
        for (overload in listOf(false, true)) {
            val target = editable("aaa", 0, 3)
            val connection = ComposingAwareInputConnection(target)
            assertTrue(connection.commitAs(overload, "x", 1))
            assertReplaced(target, "x", 1)
            assertFalse(connection.composing)
        }
    }

    @Test
    fun aReversedSelection_isReplacedTheSame() {
        for (overload in listOf(false, true)) {
            val target = editable("aaa", 3, 0)
            val connection = ComposingAwareInputConnection(target)
            assertTrue(connection.commitAs(overload, "x", 1))
            assertReplaced(target, "x", 1)
        }
    }

    @Test
    fun aSelectionInTheMiddle_isReplacedAndTheCaretLandsAfterTheText() {
        for (overload in listOf(false, true)) {
            val target = editable("abcde", 1, 3)
            ComposingAwareInputConnection(target).commitAs(overload, "X", 1)
            assertReplaced(target, "aXde", 2)
        }
    }

    private fun assertFallsThrough(target: EditableTarget, text: CharSequence?, cursor: Int, original: String) {
        for (overload in listOf(false, true)) {
            target.calls.clear()
            val connection = ComposingAwareInputConnection(target)
            if (overload && text == null) continue
            assertTrue(connection.commitAs(overload, text, cursor))
            val expected = if (overload) "commit+attr($text,$cursor)" else "commit($text,$cursor)"
            assertEquals(listOf(expected), target.calls)
            assertEquals("The editable is untouched", original, target.text.toString())
            assertFalse(connection.composing)
        }
    }

    @Test
    fun aCollapsedSelection_fallsThroughToTheTargetsCommit() {
        assertFallsThrough(editable("abc", 2, 2), "x", 1, "abc")
    }

    @Test
    fun emptyOrNullText_fallsThrough() {
        assertFallsThrough(editable("abc", 0, 3), "", 1, "abc")
        assertFallsThrough(editable("abc", 0, 3), null, 1, "abc")
    }

    @Test
    fun aCursorPositionOtherThanOne_fallsThrough() {
        assertFallsThrough(editable("abc", 0, 3), "x", 0, "abc")
        assertFallsThrough(editable("abc", 0, 3), "x", 2, "abc")
        assertFallsThrough(editable("abc", 0, 3), "x", -1, "abc")
    }

    @Test
    fun aComposingSpan_fallsThrough() {
        val target = editable("abc", 0, 3)
        android.view.inputmethod.BaseInputConnection.setComposingSpans(target.text)
        assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(target.text) >= 0)
        assertFallsThrough(target, "x", 1, "abc")
    }

    @Test
    fun aTargetThatIsNotABaseInputConnection_fallsThrough() {
        val recorder = Recorder().also { it.selectedText = "abc" }
        val connection = ComposingAwareInputConnection(recorder as InputConnection)
        assertTrue(connection.commitText("x", 1))
        assertEquals(listOf("commitText(x,1)"), recorder.calls)
        assertFalse(connection.composing)
    }

    @Test
    fun commitClearsComposing_whetherOrNotTheSelectionWasReplaced() {
        val target = editable("abc", 0, 3)
        val connection = ComposingAwareInputConnection(target)
        connection.setComposingText("n", 1)
        assertTrue(connection.composing)
        connection.commitText("x", 1)
        assertFalse(connection.composing)

        val collapsed = editable("abc", 1, 1)
        val other = ComposingAwareInputConnection(collapsed)
        other.setComposingText("n", 1)
        other.commitText("x", 1)
        assertFalse(other.composing)
    }
}
