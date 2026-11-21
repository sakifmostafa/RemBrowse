package com.rembrowser.rembrowse.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFieldTest {

    private val url = "https://www.example.com/some-path/page.html?q=1#top"

    private fun wordAt(offset: Int): String? = addressWordAt(url, offset)?.let { url.substring(it.min, it.max) }

    @Test
    fun aWordStopsAtTheAddressesPunctuation() {
        assertEquals("example", wordAt(url.indexOf("example") + 3))
        assertEquals("www", wordAt(url.indexOf("www")))
        assertEquals("com", wordAt(url.indexOf("com/")))
        assertEquals("some", wordAt(url.indexOf("some") + 2))
        assertEquals("path", wordAt(url.indexOf("path")))
        assertEquals("html", wordAt(url.indexOf("html") + 4))
        assertEquals("https", wordAt(0))
        assertEquals("top", wordAt(url.length))
    }

    @Test
    fun rightAfterAWord_isThatWord_andPunctuationAloneIsNone() {
        // The caret between "some" and "-": the word it follows
        assertEquals("some", wordAt(url.indexOf("-path")))
        // Between two slashes there is no word
        assertNull(addressWordAt(url, url.indexOf("//") + 1))
        assertNull(addressWordAt("", 0))
    }

    @Test
    fun searchesAndOtherScripts_areWordsToo() {
        val text = "café au lait 2024"
        assertEquals(TextRange(0, 4), addressWordAt(text, 2))
        assertEquals(TextRange(13, 17), addressWordAt(text, 15))
    }

    @Test
    fun editingTheSelection() {
        val value = TextFieldValue("https://www.example.com/", TextRange(12, 19))
        assertEquals("example", value.selectedText())
        val pasted = value.replaceSelection("mozilla")
        assertEquals("https://www.mozilla.com/", pasted.text)
        assertEquals("The caret after the pasted text", TextRange(19), pasted.selection)
        assertEquals("https://www..com/", value.replaceSelection("").text)
        assertEquals("", TextFieldValue("abc", TextRange(1)).selectedText())
    }

    @Test
    fun theFullAddressTakesOverTheSelection() {
        val all = TextFieldValue("https://example.com", TextRange(0, 19)).withTextKeepingSelection("https://example.com/")
        assertEquals(TextRange(0, 20), all.selection)
        val caret = TextFieldValue("https://example.com", TextRange(8)).withTextKeepingSelection("https://example.com/")
        assertEquals(TextRange(8), caret.selection)
        val shorter = TextFieldValue("https://example.com/", TextRange(20)).withTextKeepingSelection("https://example.com")
        assertEquals(TextRange(19), shorter.selection)
    }

    @Test
    fun pastedText_comesOnOneLine() {
        assertEquals("https://example.com/a-long-path", pastedOnOneLine("https://example.com/a-long-\n  path\n"))
        assertEquals("hello world", pastedOnOneLine("  hello world  "))
        assertEquals("ab", pastedOnOneLine("a\r\nb"))
    }

    @Test
    fun presses_areCountedLikeComposeCountsClicks() {
        val presses = AddressFieldPresses()
        presses.press(1_000, Offset(10f, 10f), doubleClickMillis = 300, clickSlopPx = 50f)
        presses.release()
        assertEquals(1, presses.clickCount)
        presses.press(1_200, Offset(14f, 10f), doubleClickMillis = 300, clickSlopPx = 50f)
        assertEquals(2, presses.clickCount)
        presses.release()
        presses.press(1_400, Offset(14f, 10f), doubleClickMillis = 300, clickSlopPx = 50f)
        assertEquals(3, presses.clickCount)
        presses.release()
        // Too late, or too far, starts again
        presses.press(2_000, Offset(14f, 10f), doubleClickMillis = 300, clickSlopPx = 50f)
        assertEquals(1, presses.clickCount)
        presses.release()
        presses.press(2_100, Offset(200f, 10f), doubleClickMillis = 300, clickSlopPx = 50f)
        assertEquals(1, presses.clickCount)
    }

    @Test
    fun onlyAStillDoubleClicksWordIsNarrowed_andOnlyOnce() {
        val presses = AddressFieldPresses()
        presses.lastCaret = 15
        presses.press(1_000, Offset.Zero, 300, 50f)
        presses.release()
        presses.press(1_100, Offset.Zero, 300, 50f)
        assertTrue(presses.isDoubleClickWord(TextRange(12, 23)))
        assertFalse("Not a selection around the clicked spot", presses.isDoubleClickWord(TextRange(30, 40)))
        presses.wordNarrowed = true
        assertFalse("Dragging on from the word is left to the field", presses.isDoubleClickWord(TextRange(12, 30)))

        presses.release()
        presses.press(5_000, Offset.Zero, 300, 50f)
        assertFalse("A single press's selection is a drag", presses.isDoubleClickWord(TextRange(12, 23)))
    }
}
