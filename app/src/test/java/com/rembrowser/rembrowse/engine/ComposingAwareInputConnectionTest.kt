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
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += "setComposingText($text,$newCursorPosition)"
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
}
