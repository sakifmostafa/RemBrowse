package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        // The shell's own: reverse search, clear, kill line, find, EOF, back and forward a word
        listOf(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_C)
            .forEach { assertNull(inTerminal(it, ctrl = true)) }
        // Opening and closing tabs keep the same keys as everywhere else (the owner's choice)
        assertEquals(BrowserShortcut.NEW_TAB, inTerminal(KeyEvent.KEYCODE_T, ctrl = true))
        assertEquals(BrowserShortcut.CLOSE_TAB, inTerminal(KeyEvent.KEYCODE_W, ctrl = true))
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
    fun ctrlP_printsThePage_butOnlyOutsideTerminalsAndWithoutOtherModifiers() {
        assertEquals(BrowserShortcut.PRINT, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_P, ctrl = true))
        assertNull("Ctrl+Shift+P", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_P, ctrl = true, shift = true))
        assertNull("Ctrl+Alt+P", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_P, ctrl = true, alt = true))
        assertNull("Plain P is typing", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_P, ctrl = false))
        assertNull(
            "Ctrl+P is the shell's previous-command key",
            KeyboardShortcuts.resolve(KeyEvent.KEYCODE_P, ctrl = true, inTerminal = true)
        )
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

    @Test
    fun escape_closesTheOpenFindBar_wheneverTheFocusIs_butNotInATerminal() {
        val esc = KeyEvent.KEYCODE_ESCAPE
        assertTrue(KeyboardShortcuts.escapeClosesFindBar(esc, findBarOpen = true, noModifiers = true))
        // Nothing to close: Esc stays the page's (or the system's)
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(esc, findBarOpen = false, noModifiers = true))
        // Shift+Esc, Ctrl+Esc and the rest are left alone
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(esc, findBarOpen = true, noModifiers = false))
        // A held Esc closes it once
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(esc, findBarOpen = true, noModifiers = true, repeatCount = 1))
        // vim and the shell keep their Esc
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(esc, findBarOpen = true, noModifiers = true, inTerminal = true))
        // The system's Back gesture or button is not Esc (the bar has its own Back handling)
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(KeyEvent.KEYCODE_BACK, findBarOpen = true, noModifiers = true))
        // A laptop keyboard that sends its Esc key as Back
        assertTrue(
            KeyboardShortcuts.escapeClosesFindBar(
                KeyEvent.KEYCODE_BACK, findBarOpen = true, noModifiers = true, fromPhysicalKeyboard = true
            )
        )
        assertFalse(
            KeyboardShortcuts.escapeClosesFindBar(
                KeyEvent.KEYCODE_BACK, findBarOpen = false, noModifiers = true, fromPhysicalKeyboard = true
            )
        )
        // The address bar, the tab search, the tab switcher or a fullscreen page keep their Esc
        assertFalse(
            KeyboardShortcuts.escapeClosesFindBar(
                KeyEvent.KEYCODE_ESCAPE, findBarOpen = true, noModifiers = true, escapeTakenInFront = true
            )
        )
        // Other keys never close it
        assertFalse(
            KeyboardShortcuts.escapeClosesFindBar(
                KeyEvent.KEYCODE_A, findBarOpen = true, noModifiers = true, fromPhysicalKeyboard = true
            )
        )
    }

    @Test
    fun ctrlAltS_takesAScreenshot_everywhere_aTerminalIncluded() {
        for (inTerminal in listOf(false, true)) {
            assertEquals(
                "inTerminal=$inTerminal",
                BrowserShortcut.SCREENSHOT,
                KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, alt = true, inTerminal = inTerminal)
            )
            assertNull("Held, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, alt = true, repeatCount = 1, inTerminal = inTerminal))
            assertNull("With Shift, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, shift = true, alt = true, inTerminal = inTerminal))
            assertNull("With Meta, inTerminal=$inTerminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, alt = true, meta = true, inTerminal = inTerminal))
        }
        // The page's and the shell's own keys stay theirs: Ctrl+S saves in web apps, Ctrl+S is XOFF in a shell
        assertNull("Ctrl+S", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true))
        assertNull("Ctrl+S in a terminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, inTerminal = true))
        assertNull("Alt+S", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = false, alt = true))
        assertNull("Alt+S in a terminal", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = false, alt = true, inTerminal = true))
        assertNull("Ctrl+Shift+S", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = true, shift = true))
        assertNull("Plain S is typing", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_S, ctrl = false))
        // Other Ctrl+Alt keys are still left alone
        assertNull("Ctrl+Alt+D", KeyboardShortcuts.resolve(KeyEvent.KEYCODE_D, ctrl = true, alt = true))
    }

    @Test
    fun whileTheScreenshotOverlayIsUp_escCancels_enterTakes_andOtherKeysStayOffThePage() {
        fun key(code: Int, system: Boolean = false, physical: Boolean = true) =
            KeyboardShortcuts.screenCaptureKey(code, isSystemKey = system, fromPhysicalKeyboard = physical)

        assertEquals(ScreenCaptureKey.CANCEL, key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("An on-screen Esc too", ScreenCaptureKey.CANCEL, key(KeyEvent.KEYCODE_ESCAPE, physical = false))
        // A laptop keyboard that sends its Esc key as Back
        assertEquals(ScreenCaptureKey.CANCEL, key(KeyEvent.KEYCODE_BACK, system = true))
        // The system's own Back goes on to the screen's Back handler, which cancels too
        assertEquals(ScreenCaptureKey.PASS, key(KeyEvent.KEYCODE_BACK, system = true, physical = false))
        assertEquals(ScreenCaptureKey.CAPTURE_FOCUSED, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(ScreenCaptureKey.CAPTURE_FOCUSED, key(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("Volume still works", ScreenCaptureKey.PASS, key(KeyEvent.KEYCODE_VOLUME_UP, system = true))
        listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertEquals("Key $it", ScreenCaptureKey.SWALLOW, key(it)) }
    }

    @Test
    fun everyShortcutInTheSystemSheet_pressesWhatItSays() {
        val all = KeyboardShortcuts.HELP_GROUPS.flatMap { it.second }
        all.forEach { help ->
            assertEquals(
                help.label,
                help.shortcut,
                KeyboardShortcuts.resolve(help.keyCode, help.ctrl, help.shift, help.alt, meta = false, repeatCount = 0)
            )
        }
        assertEquals(listOf("Tabs", "Navigation", "Page", "Split view", "RemBrowse"), KeyboardShortcuts.HELP_GROUPS.map { it.first })
        assertEquals("Each row is listed once", all.size, all.map { it.label }.toSet().size)
        assertEquals("Each command is listed once", all.size, all.map { it.shortcut }.toSet().size)
    }

    @Test
    fun inATerminal_theSheetLeavesOutWhatTheShellKeeps() {
        assertEquals("Outside a terminal, everything", KeyboardShortcuts.HELP_GROUPS, KeyboardShortcuts.helpGroups(inTerminal = false))
        val groups = KeyboardShortcuts.helpGroups(inTerminal = true)
        assertEquals(
            listOf(
                "Tabs" to listOf("New tab", "New terminal", "Close tab", "Next tab", "Previous tab"),
                "Page" to listOf("Take screenshot")
            ),
            groups.map { (title, rows) -> title to rows.map { it.label } }
        )
        groups.flatMap { it.second }.forEach { help ->
            assertEquals(
                help.label,
                help.shortcut,
                KeyboardShortcuts.resolve(help.keyCode, help.ctrl, help.shift, help.alt, inTerminal = true)
            )
        }
    }

    @Test
    fun aMouseForwardButton_goesForward_inAPageAndInATerminal() {
        assertEquals(BrowserShortcut.FORWARD, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_FORWARD, ctrl = false))
        assertEquals(BrowserShortcut.FORWARD, KeyboardShortcuts.resolve(KeyEvent.KEYCODE_FORWARD, ctrl = false, inTerminal = true))
        // Held down, it goes forward once
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_FORWARD, ctrl = false, repeatCount = 1))
        assertNull(KeyboardShortcuts.resolve(KeyEvent.KEYCODE_FORWARD, ctrl = true))
    }
}
