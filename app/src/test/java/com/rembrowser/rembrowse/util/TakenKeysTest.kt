package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A press the browser took is followed by its repeats and its release, and by nothing after them. */
class TakenKeysTest {

    private val keys = TakenKeys()
    private val s = KeyEvent.KEYCODE_S

    private fun down(code: Int, repeat: Int = 0) = keys.takesFollowUp(KeyEvent.ACTION_DOWN, code, repeat)
    private fun up(code: Int) = keys.takesFollowUp(KeyEvent.ACTION_UP, code, 0)

    @Test
    fun aHeldShortcut_takesItsRepeatsAndItsRelease_andNoLaterRelease() {
        // Ctrl+Alt+S held past auto-repeat: the shortcut took the press
        assertFalse("The press itself is the shortcut's to handle", down(s))
        keys.take(s)
        assertTrue(down(s, repeat = 1))
        assertTrue(down(s, repeat = 30))
        assertTrue("Its release", up(s))

        // Typing S in a page afterwards: its key-up reaches the page
        assertFalse(down(s))
        assertFalse(up(s))
    }

    @Test
    fun keysNeverTaken_areLeftAlone() {
        keys.take(s)
        assertFalse(down(KeyEvent.KEYCODE_A, repeat = 3))
        assertFalse("Ctrl let go after Ctrl+Alt+S", up(KeyEvent.KEYCODE_CTRL_LEFT))
        assertTrue(up(s))
    }

    @Test
    fun aMissedRelease_doesNotSwallowTheNextPress() {
        keys.take(s)
        // The release never came (focus moved to another window); a new press starts afresh
        assertFalse(down(s))
        assertFalse(down(s, repeat = 1))
        assertFalse(up(s))
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_MULTIPLE still arrives for some keys
    fun otherActions_areLeftAlone() {
        keys.take(s)
        assertFalse(keys.takesFollowUp(KeyEvent.ACTION_MULTIPLE, s, 0))
        assertTrue(up(s))
    }
}
