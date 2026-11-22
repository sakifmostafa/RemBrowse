package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardShortcutsTest {

    @Test
    fun ctrlShortcuts_mapToBrowserCommands() {
        assertEquals(BrowserShortcut.NEW_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true))
        assertEquals(BrowserShortcut.CLOSE_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_W, ctrl = true))
        assertEquals(BrowserShortcut.FOCUS_ADDRESS_BAR, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_L, ctrl = true))
        assertEquals(BrowserShortcut.RELOAD, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_R, ctrl = true))
        assertEquals(BrowserShortcut.TOGGLE_BOOKMARK, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_D, ctrl = true))
        assertEquals(BrowserShortcut.COMMAND_PALETTE, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_K, ctrl = true))
        assertEquals(BrowserShortcut.OPEN_SETTINGS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_COMMA, ctrl = true))
        assertEquals(BrowserShortcut.OPEN_DOWNLOADS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_J, ctrl = true))
        assertEquals(BrowserShortcut.TOGGLE_SPLIT_VIEW, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_BACKSLASH, ctrl = true))
        assertEquals(BrowserShortcut.LAYOUT_SINGLE, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_1, ctrl = true))
        assertEquals(BrowserShortcut.LAYOUT_GRID, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_4, ctrl = true))
        assertEquals(BrowserShortcut.LAYOUT_ONE_AND_TWO, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_5, ctrl = true))
    }

    @Test
    fun navigationShortcuts_followDesktopBrowserConventions() {
        assertEquals(BrowserShortcut.BACK, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false, alt = true))
        assertEquals(BrowserShortcut.FORWARD, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = false, alt = true))
        assertEquals(BrowserShortcut.FOCUS_ADDRESS_BAR, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_D, ctrl = false, alt = true))
        assertEquals(BrowserShortcut.RELOAD, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F5, ctrl = false))
        assertEquals(BrowserShortcut.NEXT_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(BrowserShortcut.PREVIOUS_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
    }

    @Test
    fun heldKeys_doNotRepeatTheCommand() {
        // Holding Ctrl+T used to open a new tab on every auto-repeat tick
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, repeatCount = 1))
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_W, ctrl = true, repeatCount = 7))
    }

    @Test
    fun otherModifierCombinations_areLeftForThePageAndTheSystem() {
        assertNull("Plain typing", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = false))
        assertNull("Ctrl+Shift+N", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_N, ctrl = true, shift = true))
        assertNull("Ctrl+N", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_N, ctrl = true))
        assertNull("Ctrl+Alt+R", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_R, ctrl = true, alt = true))
        assertNull("Meta+L", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_L, ctrl = true, meta = true))
        assertNull("Plain arrow keys scroll the page", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false))
        assertNull("Unmapped Ctrl key", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true))
    }

    @Test
    fun ctrlH_opensTheHistory() {
        assertEquals(BrowserShortcut.OPEN_HISTORY, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_H, ctrl = true))
        assertEquals(BrowserShortcut.FIND_IN_PAGE, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F, ctrl = true))
        assertEquals("Ctrl+G is Go to address", BrowserShortcut.GO_TO_ADDRESS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true))
        assertEquals("Ctrl+Shift+G is left to the page", null, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.FIND_NEXT, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false))
        assertEquals(BrowserShortcut.FIND_PREVIOUS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false, shift = true))
        assertEquals(null, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_H, ctrl = false))
    }

    @Test
    fun inATerminal_theShellKeepsCtrlAndAlt_andTheBrowserMovesToCtrlShift() {
        fun inTerminal(keyCode: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
            KeyboardShortcuts.resolve(keyCode, ctrl = ctrl, shift = shift, alt = alt, inTerminal = true)

        // The shell's own: delete word, reverse search, clear, kill line, find, EOF, back and forward a word
        listOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_T)
            .forEach { assertNull(inTerminal(it, ctrl = true)) }
        assertNull(inTerminal(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
        assertNull(inTerminal(KeyEvent.KEYCODE_F5))
        assertNull(inTerminal(KeyEvent.KEYCODE_F3))
        assertNull("Copy is the terminal's", inTerminal(KeyEvent.KEYCODE_C, ctrl = true, shift = true))

        assertEquals(BrowserShortcut.NEW_TERMINAL, inTerminal(KeyEvent.KEYCODE_T, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.NEW_TAB, inTerminal(KeyEvent.KEYCODE_N, ctrl = true, shift = true))
        assertNull("Ctrl+N stays the shell's", inTerminal(KeyEvent.KEYCODE_N, ctrl = true))
        assertEquals(BrowserShortcut.CLOSE_TAB, inTerminal(KeyEvent.KEYCODE_W, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.FOCUS_ADDRESS_BAR, inTerminal(KeyEvent.KEYCODE_L, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.COMMAND_PALETTE, inTerminal(KeyEvent.KEYCODE_K, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.NEXT_TAB, inTerminal(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(BrowserShortcut.PREVIOUS_TAB, inTerminal(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))

        // Outside a terminal nothing changes
        assertEquals(BrowserShortcut.CLOSE_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_W, ctrl = true))
    }

    @Test
    fun ctrlG_isGoToAddress_andCtrlShiftG_isLeftToThePageOutsideTerminals() {
        assertEquals(BrowserShortcut.GO_TO_ADDRESS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true))
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, shift = true))
        assertNull("Plain G is typing", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = false))
        assertNull("A held Ctrl+G does not repeat", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, repeatCount = 1))
    }

    @Test
    fun inATerminal_ctrlShiftG_isGoToAddress_andCtrlGStaysTheShells() {
        assertEquals(
            BrowserShortcut.GO_TO_ADDRESS,
            KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, shift = true, inTerminal = true)
        )
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, inTerminal = true))
    }

    @Test
    fun f3_stillFindsNextAndPrevious() {
        assertEquals(BrowserShortcut.FIND_NEXT, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false))
        assertEquals(BrowserShortcut.FIND_PREVIOUS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false, shift = true))
    }

    @Test
    fun ctrlShiftT_opensANewTerminal_inAndOutOfATerminal() {
        assertEquals(BrowserShortcut.NEW_TERMINAL, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true))
        assertEquals(
            BrowserShortcut.NEW_TERMINAL,
            KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true, inTerminal = true)
        )
    }

    @Test
    fun ctrlShiftN_opensATab_inATerminal() {
        assertEquals(
            BrowserShortcut.NEW_TAB,
            KeyboardShortcuts.resolve(KeyEvent.KEYCODE_N, ctrl = true, shift = true, inTerminal = true)
        )
    }

    @Test
    fun ctrlN_isLeftToThePage_outsideATerminal() {
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_N, ctrl = true))
    }

    @Test
    fun ctrlT_isStillANewTab_outsideATerminal() {
        assertEquals(BrowserShortcut.NEW_TAB, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true))
    }

    @Test
    fun ctrlShiftT_isIgnoredOnRepeatAndWithAltOrMeta() {
        for (inTerminal in listOf(false, true)) {
            assertNull("Held key, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true, repeatCount = 1, inTerminal = inTerminal))
            assertNull("Alt, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true, alt = true, inTerminal = inTerminal))
            assertNull("Meta, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true, meta = true, inTerminal = inTerminal))
        }
    }
}
