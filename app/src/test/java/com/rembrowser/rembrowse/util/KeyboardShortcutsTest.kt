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
        assertNull("Ctrl+Shift+T", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_T, ctrl = true, shift = true))
        assertNull("Ctrl+Alt+R", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_R, ctrl = true, alt = true))
        assertNull("Meta+L", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_L, ctrl = true, meta = true))
        assertNull("Plain arrow keys scroll the page", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false))
        assertNull("Unmapped Ctrl key", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true))
    }

    @Test
    fun ctrlH_opensTheHistory() {
        assertEquals(BrowserShortcut.OPEN_HISTORY, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_H, ctrl = true))
        assertEquals(BrowserShortcut.FIND_IN_PAGE, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F, ctrl = true))
        assertEquals(BrowserShortcut.FIND_NEXT, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true))
        assertEquals(BrowserShortcut.FIND_PREVIOUS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_G, ctrl = true, shift = true))
        assertEquals(BrowserShortcut.FIND_NEXT, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false))
        assertEquals(BrowserShortcut.FIND_PREVIOUS, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_F3, ctrl = false, shift = true))
        assertEquals(null, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_H, ctrl = false))
    }
}
