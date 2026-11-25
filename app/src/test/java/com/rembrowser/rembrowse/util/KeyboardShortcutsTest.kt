package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun escape_closesTheTabRailOpenOverThePage_wheneverTheFocusIs() {
        val esc = KeyEvent.KEYCODE_ESCAPE
        assertTrue(KeyboardShortcuts.escapeClosesTabRail(esc, railOverlayOpen = true, noModifiers = true))
        // Nothing open over the page: Esc goes where it went before
        assertFalse(KeyboardShortcuts.escapeClosesTabRail(esc, railOverlayOpen = false, noModifiers = true))
        // Shift+Esc and the rest are left alone; a held Esc closes it once
        assertFalse(KeyboardShortcuts.escapeClosesTabRail(esc, railOverlayOpen = true, noModifiers = false))
        assertFalse(KeyboardShortcuts.escapeClosesTabRail(esc, railOverlayOpen = true, noModifiers = true, repeatCount = 1))
        // Back, a laptop keyboard's Esc sent as Back included, is not Esc: the rail's Back handler closes it
        assertFalse(KeyboardShortcuts.escapeClosesTabRail(KeyEvent.KEYCODE_BACK, railOverlayOpen = true, noModifiers = true))
        assertFalse(KeyboardShortcuts.escapeClosesTabRail(KeyEvent.KEYCODE_A, railOverlayOpen = true, noModifiers = true))
    }

    @Test
    fun shortcutsThatMoveTheFocusOrOpenSomething_closeTheTabRailOverThePageFirst() {
        val keepItOpen = setOf(BrowserShortcut.CLOSE_TAB, BrowserShortcut.TOGGLE_BOOKMARK, BrowserShortcut.SCREENSHOT)
        BrowserShortcut.entries.forEach { shortcut ->
            assertEquals(shortcut.name, shortcut !in keepItOpen, KeyboardShortcuts.closesTabRail(shortcut))
        }
        // Among them, those that used to open a tab, the address bar or find under the rail
        listOf(BrowserShortcut.NEW_TAB, BrowserShortcut.FOCUS_ADDRESS_BAR, BrowserShortcut.FIND_IN_PAGE, BrowserShortcut.NEXT_TAB)
            .forEach { assertTrue(it.name, KeyboardShortcuts.closesTabRail(it)) }
    }

    @Test
    fun shortcutsThatPutTheKeyboardSomewhere_keepTheFocusTheRailWouldGiveBack() {
        // These take the keyboard themselves: the rail they close leaves it to them
        listOf(
            BrowserShortcut.NEW_TAB, BrowserShortcut.NEW_TERMINAL, BrowserShortcut.FOCUS_ADDRESS_BAR,
            BrowserShortcut.COMMAND_PALETTE, BrowserShortcut.GO_TO_ADDRESS, BrowserShortcut.FIND_IN_PAGE,
            BrowserShortcut.FIND_NEXT, BrowserShortcut.FIND_PREVIOUS, BrowserShortcut.PRINT
        ).forEach { assertTrue(it.name, KeyboardShortcuts.placesFocus(it)) }
        // These don't: the keyboard goes back to where it was before the rail opened
        listOf(
            BrowserShortcut.NEXT_TAB, BrowserShortcut.PREVIOUS_TAB, BrowserShortcut.BACK, BrowserShortcut.FORWARD,
            BrowserShortcut.RELOAD, BrowserShortcut.TOGGLE_SPLIT_VIEW, BrowserShortcut.LAYOUT_GRID, BrowserShortcut.OPEN_SETTINGS
        ).forEach { assertFalse(it.name, KeyboardShortcuts.placesFocus(it)) }
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
        // Back is not Esc, a laptop keyboard's Esc sent as Back included: predictive back hands it
        // to the Back handlers (the bar has its own), and Android 16, which shows the window its
        // press, still gives the release to the newest handler, so taking the press too would act twice
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(KeyEvent.KEYCODE_BACK, findBarOpen = true, noModifiers = true))
        // The address bar, the tab search, the tab switcher or a fullscreen page keep their Esc
        assertFalse(
            KeyboardShortcuts.escapeClosesFindBar(
                KeyEvent.KEYCODE_ESCAPE, findBarOpen = true, noModifiers = true, escapeTakenInFront = true
            )
        )
        // Other keys never close it
        assertFalse(KeyboardShortcuts.escapeClosesFindBar(KeyEvent.KEYCODE_A, findBarOpen = true, noModifiers = true))
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
        fun key(code: Int, system: Boolean = false) = KeyboardShortcuts.screenCaptureKey(code, isSystemKey = system)

        assertEquals(ScreenCaptureKey.CANCEL, key(KeyEvent.KEYCODE_ESCAPE))
        // Back, a laptop keyboard's Esc sent as Back included, goes on to the screen's Back
        // handler, which cancels too
        assertEquals(ScreenCaptureKey.PASS, key(KeyEvent.KEYCODE_BACK, system = true))
        assertEquals(ScreenCaptureKey.CAPTURE_FOCUSED, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(ScreenCaptureKey.CAPTURE_FOCUSED, key(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("Volume still works", ScreenCaptureKey.PASS, key(KeyEvent.KEYCODE_VOLUME_UP, system = true))
        listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertEquals("Key $it", ScreenCaptureKey.SWALLOW, key(it)) }
    }

    @Test
    fun whileTheScreenshotOverlayIsUp_pAlone_takesTheWholePage() {
        fun key(code: Int, noModifiers: Boolean = true) =
            KeyboardShortcuts.screenCaptureKey(code, isSystemKey = false, noModifiers = noModifiers)

        assertEquals(ScreenCaptureKey.CAPTURE_WHOLE_PAGE, key(KeyEvent.KEYCODE_P))
        assertEquals("P alone is the default", ScreenCaptureKey.CAPTURE_WHOLE_PAGE,
            KeyboardShortcuts.screenCaptureKey(KeyEvent.KEYCODE_P, isSystemKey = false))
        // Ctrl+P (print) and Shift+P are kept from the page, as every other key is
        assertEquals(ScreenCaptureKey.SWALLOW, key(KeyEvent.KEYCODE_P, noModifiers = false))
        // Enter and Esc don't care about modifiers, as before
        assertEquals(ScreenCaptureKey.CAPTURE_FOCUSED, key(KeyEvent.KEYCODE_ENTER, noModifiers = false))
        assertEquals(ScreenCaptureKey.CANCEL, key(KeyEvent.KEYCODE_ESCAPE, noModifiers = false))
        assertEquals("P", KeyboardShortcuts.WHOLE_PAGE_KEY)
    }

    @Test
    fun theSheetsScreenshotGroup_listsTheOverlaysKeys_andEachDoesWhatItSays() {
        val (title, rows) = KeyboardShortcuts.SCREEN_CAPTURE_HELP
        assertEquals("While taking a screenshot", title)
        assertEquals(
            listOf("Capture the focused pane", "Capture the whole page", "Cancel the screenshot"),
            rows.map { it.label }
        )
        rows.forEach { help ->
            assertEquals(
                help.label,
                help.key,
                KeyboardShortcuts.screenCaptureKey(help.keyCode, isSystemKey = false, noModifiers = true)
            )
        }
        assertEquals(KeyEvent.KEYCODE_P, rows.single { it.key == ScreenCaptureKey.CAPTURE_WHOLE_PAGE }.keyCode)
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

    /** A row's keys, the way the sheet draws them ("Ctrl+Shift+L"); on the JVM, so named here */
    private fun ShortcutHelp.keys(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(
            when (keyCode) {
                KeyEvent.KEYCODE_TAB -> "Tab"
                KeyEvent.KEYCODE_BACKSLASH -> "\\"
                KeyEvent.KEYCODE_COMMA -> ","
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
                else -> "key $keyCode"
            }
        )
    }

    @Test
    fun inATerminal_theSheetListsTheKeysThatReachTheBrowserThere() {
        assertEquals("Outside a terminal, everything", KeyboardShortcuts.HELP_GROUPS, KeyboardShortcuts.helpGroups(inTerminal = false))
        val groups = KeyboardShortcuts.helpGroups(inTerminal = true)
        assertEquals(
            listOf(
                "Tabs" to listOf(
                    "New tab" to "Ctrl+T",
                    "New terminal" to "Ctrl+Shift+T",
                    "Close tab" to "Ctrl+W",
                    "Next tab" to "Ctrl+Tab",
                    "Previous tab" to "Ctrl+Shift+Tab"
                ),
                // The shell keeps Ctrl+L and Ctrl+G; Alt+arrows and Ctrl+R are its too
                "Navigation" to listOf("Address bar" to "Ctrl+Shift+L", "Go to address" to "Ctrl+Shift+G"),
                "Page" to listOf("Take screenshot" to "Ctrl+Alt+S"),
                "Split view" to listOf("Split view on or off" to "Ctrl+Shift+\\"),
                "RemBrowse" to listOf("Command palette" to "Ctrl+Shift+K", "Settings" to "Ctrl+Shift+,")
            ),
            groups.map { (title, rows) -> title to rows.map { it.label to it.keys() } }
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
    fun inATerminal_aRowLeftOutHasNoCtrlOrCtrlShiftKeysThatWorkThere() {
        // The rows the sheet leaves out: neither their own keys nor Ctrl+Shift with their key
        // give their command in a terminal, so nothing that works is missing
        val listed = KeyboardShortcuts.helpGroups(inTerminal = true).flatMap { it.second }.map { it.shortcut }.toSet()
        val leftOut = KeyboardShortcuts.HELP_GROUPS.flatMap { it.second }.filter { it.shortcut !in listed }
        assertEquals(
            listOf(
                "Back", "Forward", "Reload", "Find in page", "Print", "Bookmark this page", "Single pane", "Side by side",
                "Stacked", "Grid", "One left, two right", "History", "Downloads"
            ),
            leftOut.map { it.label }
        )
        leftOut.forEach { help ->
            for (ctrl in listOf(false, true)) for (shift in listOf(false, true)) {
                assertNotEquals(
                    "${help.label}, ctrl $ctrl shift $shift",
                    help.shortcut,
                    KeyboardShortcuts.resolve(help.keyCode, ctrl, shift, help.alt, inTerminal = true)
                )
            }
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
