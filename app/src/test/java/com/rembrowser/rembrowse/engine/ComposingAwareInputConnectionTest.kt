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
}
