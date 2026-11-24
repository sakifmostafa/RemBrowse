package com.rembrowser.rembrowse.util

import android.view.KeyEvent

enum class BrowserShortcut {
    NEW_TAB,

    /** A new terminal tab (Ctrl+Shift+T everywhere, as in terminal apps). */
    NEW_TERMINAL,
    CLOSE_TAB,
    FOCUS_ADDRESS_BAR,
    RELOAD,
    BACK,
    FORWARD,
    NEXT_TAB,
    PREVIOUS_TAB,
    TOGGLE_BOOKMARK,
    COMMAND_PALETTE,
    OPEN_SETTINGS,
    OPEN_DOWNLOADS,
    OPEN_HISTORY,
    TOGGLE_SPLIT_VIEW,
    LAYOUT_SINGLE,
    LAYOUT_SIDE_BY_SIDE,
    LAYOUT_STACKED,
    LAYOUT_GRID,

    /** One pane on the left, two stacked on the right. */
    LAYOUT_ONE_AND_TWO,
    FIND_IN_PAGE,
    FIND_NEXT,
    FIND_PREVIOUS,

    /** The Go to address popup (Ctrl+G). */
    GO_TO_ADDRESS,

    /** The print dialog for the page (Ctrl+P), which can also save it as a PDF. */
    PRINT,

    /** Take a screenshot of part of the window (Ctrl+Alt+S, also in a terminal). */
    SCREENSHOT
}

/** What a key does while the screenshot overlay is up. */
enum class ScreenCaptureKey {
    /** Esc (or a keyboard's Back key): close the overlay, taking nothing. */
    CANCEL,

    /** Enter: take the focused pane, or the tab switcher when it is open. */
    CAPTURE_FOCUSED,

    /** Any other key is kept from the page and the terminal underneath while the overlay is up. */
    SWALLOW,

    /** System keys (volume, power, media, the system's own Back) carry on as usual. */
    PASS
}

/**
 * One row of the system's Keyboard Shortcuts sheet (Meta+/): its [label], the key and modifiers
 * that press it, and the [shortcut] those keys resolve to, so the sheet can never list keys that
 * do something else.
 */
data class ShortcutHelp(
    val label: String,
    val keyCode: Int,
    val ctrl: Boolean = true,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val shortcut: BrowserShortcut
)

/** Maps a hardware key press to a browser command. Pure so it can be unit tested on the JVM. */
object KeyboardShortcuts {

    /**
     * What the system's Keyboard Shortcuts sheet lists, by group, in the More menu's words: the
     * shortcuts as they work outside a terminal. [helpGroups] leaves out the ones a terminal keeps
     * for itself; the terminal's own keys are in the user guide.
     */
    val HELP_GROUPS: List<Pair<String, List<ShortcutHelp>>> = listOf(
        "Tabs" to listOf(
            ShortcutHelp("New tab", KeyEvent.KEYCODE_T, shortcut = BrowserShortcut.NEW_TAB),
            ShortcutHelp("New terminal", KeyEvent.KEYCODE_T, shift = true, shortcut = BrowserShortcut.NEW_TERMINAL),
            ShortcutHelp("Close tab", KeyEvent.KEYCODE_W, shortcut = BrowserShortcut.CLOSE_TAB),
            ShortcutHelp("Next tab", KeyEvent.KEYCODE_TAB, shortcut = BrowserShortcut.NEXT_TAB),
            ShortcutHelp("Previous tab", KeyEvent.KEYCODE_TAB, shift = true, shortcut = BrowserShortcut.PREVIOUS_TAB)
        ),
        "Navigation" to listOf(
            ShortcutHelp("Back", KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false, alt = true, shortcut = BrowserShortcut.BACK),
            ShortcutHelp("Forward", KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = false, alt = true, shortcut = BrowserShortcut.FORWARD),
            ShortcutHelp("Reload", KeyEvent.KEYCODE_R, shortcut = BrowserShortcut.RELOAD),
            ShortcutHelp("Address bar", KeyEvent.KEYCODE_L, shortcut = BrowserShortcut.FOCUS_ADDRESS_BAR),
            ShortcutHelp("Go to address", KeyEvent.KEYCODE_G, shortcut = BrowserShortcut.GO_TO_ADDRESS)
        ),
        "Page" to listOf(
            ShortcutHelp("Find in page", KeyEvent.KEYCODE_F, shortcut = BrowserShortcut.FIND_IN_PAGE),
            ShortcutHelp("Print", KeyEvent.KEYCODE_P, shortcut = BrowserShortcut.PRINT),
            ShortcutHelp("Bookmark this page", KeyEvent.KEYCODE_D, shortcut = BrowserShortcut.TOGGLE_BOOKMARK),
            ShortcutHelp("Take screenshot", KeyEvent.KEYCODE_S, alt = true, shortcut = BrowserShortcut.SCREENSHOT)
        ),
        "Split view" to listOf(
            ShortcutHelp("Split view on or off", KeyEvent.KEYCODE_BACKSLASH, shortcut = BrowserShortcut.TOGGLE_SPLIT_VIEW),
            ShortcutHelp("Single pane", KeyEvent.KEYCODE_1, shortcut = BrowserShortcut.LAYOUT_SINGLE),
            ShortcutHelp("Side by side", KeyEvent.KEYCODE_2, shortcut = BrowserShortcut.LAYOUT_SIDE_BY_SIDE),
            ShortcutHelp("Stacked", KeyEvent.KEYCODE_3, shortcut = BrowserShortcut.LAYOUT_STACKED),
            ShortcutHelp("Grid", KeyEvent.KEYCODE_4, shortcut = BrowserShortcut.LAYOUT_GRID),
            ShortcutHelp("One left, two right", KeyEvent.KEYCODE_5, shortcut = BrowserShortcut.LAYOUT_ONE_AND_TWO)
        ),
        "RemBrowse" to listOf(
            ShortcutHelp("Command palette", KeyEvent.KEYCODE_K, shortcut = BrowserShortcut.COMMAND_PALETTE),
            ShortcutHelp("History", KeyEvent.KEYCODE_H, shortcut = BrowserShortcut.OPEN_HISTORY),
            ShortcutHelp("Downloads", KeyEvent.KEYCODE_J, shortcut = BrowserShortcut.OPEN_DOWNLOADS),
            ShortcutHelp("Settings", KeyEvent.KEYCODE_COMMA, shortcut = BrowserShortcut.OPEN_SETTINGS)
        )
    )

    /**
     * [HELP_GROUPS] as they work where the keyboard is: with a terminal tab active
     * ([inTerminal]), only the rows whose keys [resolve] still gives the browser there (Ctrl+T,
     * Ctrl+W, Ctrl+Tab, Ctrl+Shift+T, Ctrl+Alt+S); a group left empty is left out too.
     */
    fun helpGroups(inTerminal: Boolean): List<Pair<String, List<ShortcutHelp>>> =
        HELP_GROUPS
            .map { (title, rows) ->
                title to rows.filter { help ->
                    resolve(help.keyCode, help.ctrl, help.shift, help.alt, inTerminal = inTerminal) == help.shortcut
                }
            }
            .filter { (_, rows) -> rows.isNotEmpty() }

    /**
     * Whether a press of [keyCode] closes the find bar: a plain Esc while the bar is open
     * ([findBarOpen]), wherever the focus is (the bar itself, or the page after a search),
     * except in a terminal, whose programs use Esc. Read before the keyboard app, which may keep
     * a hardware Esc for itself or turn it into Back. Some laptop keyboards send their Esc key
     * as Back, so a Back from a physical keyboard ([fromPhysicalKeyboard]) counts as Esc too;
     * the system's Back gesture or button does not. Only up to Android 12L: from Android 13 Back
     * never arrives as a key (predictive back is on), and the find bar's Back handler closes it.
     */
    fun escapeClosesFindBar(
        keyCode: Int,
        findBarOpen: Boolean,
        noModifiers: Boolean,
        repeatCount: Int = 0,
        inTerminal: Boolean = false,
        fromPhysicalKeyboard: Boolean = false,
        /**
         * Something in front uses Esc itself: the address bar or the tab search being typed in
         * (Esc abandons the edit), the tab switcher, or a page in fullscreen (Esc leaves it).
         */
        escapeTakenInFront: Boolean = false
    ): Boolean {
        val isEscape = keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BACK && fromPhysicalKeyboard
        return isEscape && findBarOpen && noModifiers && repeatCount == 0 && !inTerminal && !escapeTakenInFront
    }

    fun resolve(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        repeatCount: Int = 0,
        /**
         * A terminal has the keyboard. Its shell owns Ctrl+R, Ctrl+L, Ctrl+D, Ctrl+C and the rest,
         * Alt combinations and the function keys. The browser keeps Ctrl+Tab, and Ctrl+T and
         * Ctrl+W (a new tab, close the tab) as everywhere else, the owner's choice: the shell
         * loses swapping two letters and deleting a word with them (Alt+Backspace still deletes
         * a word). The other letter shortcuts move to Ctrl+Shift (Ctrl+Shift+N and Ctrl+Shift+W
         * too), as terminal apps do. Ctrl+Shift+T opens another terminal, here as everywhere
         * else, and Ctrl+Alt+S takes a screenshot. Ctrl+Shift+C and V copy and paste in the
         * terminal itself.
         */
        inTerminal: Boolean = false
    ): BrowserShortcut? {
        // A held key must not open a tab (or close one) per auto-repeat tick.
        if (repeatCount > 0 || meta) return null
        // A mouse's Forward button arrives as this key (its Back button as the system's Back)
        if (keyCode == KeyEvent.KEYCODE_FORWARD && !ctrl && !shift && !alt) return BrowserShortcut.FORWARD
        // The same keys everywhere, a terminal included: a shell has no use for Ctrl+Alt+S
        if (isScreenshot(keyCode, ctrl, shift, alt)) return BrowserShortcut.SCREENSHOT
        if (inTerminal) return resolveInTerminal(keyCode, ctrl, shift, alt)

        if (!ctrl && !alt && !shift && keyCode == KeyEvent.KEYCODE_F5) {
            return BrowserShortcut.RELOAD
        }

        if (!ctrl && !alt && keyCode == KeyEvent.KEYCODE_F3) {
            return if (shift) BrowserShortcut.FIND_PREVIOUS else BrowserShortcut.FIND_NEXT
        }

        if (alt && !ctrl && !shift) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> BrowserShortcut.BACK
                KeyEvent.KEYCODE_DPAD_RIGHT -> BrowserShortcut.FORWARD
                KeyEvent.KEYCODE_D -> BrowserShortcut.FOCUS_ADDRESS_BAR
                else -> null
            }
        }

        if (!ctrl || alt) return null

        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return if (shift) BrowserShortcut.PREVIOUS_TAB else BrowserShortcut.NEXT_TAB
        }
        // Ctrl+G is Go to address; finding the next and previous match stays on F3, Shift+F3 and
        // Enter in the find bar. Ctrl+Shift+G is left to the page.
        if (keyCode == KeyEvent.KEYCODE_G) {
            return if (shift) null else BrowserShortcut.GO_TO_ADDRESS
        }
        // A new terminal, as in terminal apps: the same keys inside a terminal and out
        if (keyCode == KeyEvent.KEYCODE_T && shift) return BrowserShortcut.NEW_TERMINAL
        // Other Ctrl+Shift+<key> combinations belong to the page or the system, not to us.
        if (shift) return null

        return when (keyCode) {
            KeyEvent.KEYCODE_T -> BrowserShortcut.NEW_TAB
            KeyEvent.KEYCODE_W -> BrowserShortcut.CLOSE_TAB
            KeyEvent.KEYCODE_L -> BrowserShortcut.FOCUS_ADDRESS_BAR
            KeyEvent.KEYCODE_R -> BrowserShortcut.RELOAD
            KeyEvent.KEYCODE_D -> BrowserShortcut.TOGGLE_BOOKMARK
            KeyEvent.KEYCODE_K -> BrowserShortcut.COMMAND_PALETTE
            KeyEvent.KEYCODE_COMMA -> BrowserShortcut.OPEN_SETTINGS
            KeyEvent.KEYCODE_J -> BrowserShortcut.OPEN_DOWNLOADS
            KeyEvent.KEYCODE_H -> BrowserShortcut.OPEN_HISTORY
            KeyEvent.KEYCODE_F -> BrowserShortcut.FIND_IN_PAGE
            KeyEvent.KEYCODE_P -> BrowserShortcut.PRINT
            KeyEvent.KEYCODE_BACKSLASH -> BrowserShortcut.TOGGLE_SPLIT_VIEW
            KeyEvent.KEYCODE_1 -> BrowserShortcut.LAYOUT_SINGLE
            KeyEvent.KEYCODE_2 -> BrowserShortcut.LAYOUT_SIDE_BY_SIDE
            KeyEvent.KEYCODE_3 -> BrowserShortcut.LAYOUT_STACKED
            KeyEvent.KEYCODE_4 -> BrowserShortcut.LAYOUT_GRID
            KeyEvent.KEYCODE_5 -> BrowserShortcut.LAYOUT_ONE_AND_TWO
            else -> null
        }
    }

    private fun isScreenshot(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
        keyCode == KeyEvent.KEYCODE_S && ctrl && alt && !shift

    /**
     * What a key press does while the screenshot overlay is up (see [ScreenCaptureKey]). Read
     * before the keyboard app, like the shortcuts. [isSystemKey] is `KeyEvent.isSystem`; a Back
     * from a physical keyboard ([fromPhysicalKeyboard]) is that keyboard's Esc key, as for the
     * find bar (up to Android 12L; from 13 the overlay's Back handler cancels it).
     */
    fun screenCaptureKey(keyCode: Int, isSystemKey: Boolean, fromPhysicalKeyboard: Boolean): ScreenCaptureKey = when {
        keyCode == KeyEvent.KEYCODE_ESCAPE -> ScreenCaptureKey.CANCEL
        keyCode == KeyEvent.KEYCODE_BACK && fromPhysicalKeyboard -> ScreenCaptureKey.CANCEL
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> ScreenCaptureKey.CAPTURE_FOCUSED
        isSystemKey -> ScreenCaptureKey.PASS
        else -> ScreenCaptureKey.SWALLOW
    }

    private fun resolveInTerminal(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): BrowserShortcut? {
        if (!ctrl || alt) return null
        if (keyCode == KeyEvent.KEYCODE_TAB) return if (shift) BrowserShortcut.PREVIOUS_TAB else BrowserShortcut.NEXT_TAB
        // Opening and closing tabs take the same keys as everywhere else
        if (!shift) {
            return when (keyCode) {
                KeyEvent.KEYCODE_T -> BrowserShortcut.NEW_TAB
                KeyEvent.KEYCODE_W -> BrowserShortcut.CLOSE_TAB
                else -> null
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_T -> BrowserShortcut.NEW_TERMINAL
            KeyEvent.KEYCODE_N -> BrowserShortcut.NEW_TAB
            KeyEvent.KEYCODE_W -> BrowserShortcut.CLOSE_TAB
            KeyEvent.KEYCODE_L -> BrowserShortcut.FOCUS_ADDRESS_BAR
            KeyEvent.KEYCODE_K -> BrowserShortcut.COMMAND_PALETTE
            KeyEvent.KEYCODE_G -> BrowserShortcut.GO_TO_ADDRESS
            KeyEvent.KEYCODE_COMMA -> BrowserShortcut.OPEN_SETTINGS
            KeyEvent.KEYCODE_BACKSLASH -> BrowserShortcut.TOGGLE_SPLIT_VIEW
            else -> null
        }
    }
}
