package com.rembrowser.rembrowse.util

import android.os.Build
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
     * shortcuts as they work outside a terminal. [helpGroups] gives them as they work in a
     * terminal; the terminal's own keys are in the user guide.
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
     * [HELP_GROUPS] as they work where the keyboard is. With a terminal focused ([inTerminal], as
     * [resolve] is asked), each row is checked against [resolve]: it keeps its keys where they
     * still reach the browser (Ctrl+T, Ctrl+W, Ctrl+Tab, Ctrl+Shift+T, Ctrl+Alt+S), takes the
     * Ctrl+Shift version of its Ctrl keys where that is what reaches it (Address bar, Go to
     * address, Command palette, Settings, Split view on or off), and is left out otherwise; a
     * group left empty is left out too. So the sheet can't list keys that do something else.
     */
    fun helpGroups(inTerminal: Boolean): List<Pair<String, List<ShortcutHelp>>> =
        HELP_GROUPS
            .map { (title, rows) -> title to rows.mapNotNull { it.asWorkingIn(inTerminal) } }
            .filter { (_, rows) -> rows.isNotEmpty() }

    /** This row with the keys that press its shortcut ([helpGroups]), or null if none do. */
    private fun ShortcutHelp.asWorkingIn(inTerminal: Boolean): ShortcutHelp? {
        val ctrlShift = if (inTerminal && ctrl && !shift && !alt) copy(shift = true) else null
        return listOfNotNull(this, ctrlShift).firstOrNull { help ->
            resolve(help.keyCode, help.ctrl, help.shift, help.alt, inTerminal = inTerminal) == shortcut
        }
    }

    /**
     * Whether a physical keyboard's Back is read as that keyboard's Esc key by the browser's own
     * key checks ([escapeClosesFindBar], [screenCaptureKey]) on Android [sdkInt]: only up to
     * Android 12L. From Android 13 predictive back is on, and Android takes a Back key before the
     * window sees it: Android 13 to 15 and 17 never pass its press on, so the Back handlers in
     * RemBrowseScreen act on it alone (the on-screen keyboard first, when it is up). Android 16
     * passes the press on but still gives the release to the newest Back handler, so taking the
     * press here as well would do two things for one key: close the find bar, then go back in
     * the page or leave the app when no handler is left.
     */
    fun physicalBackIsEscape(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

    /**
     * Whether a press of [keyCode] closes the find bar: a plain Esc while the bar is open
     * ([findBarOpen]), wherever the focus is (the bar itself, or the page after a search),
     * except in a terminal, whose programs use Esc. Read before the keyboard app, which may keep
     * a hardware Esc for itself or turn it into Back. Some laptop keyboards send their Esc key
     * as Back, so a Back from a physical keyboard ([fromPhysicalKeyboard]) counts as Esc too, up
     * to Android 12L ([physicalBackIsEscape]); the system's Back gesture or button does not.
     * From Android 13 the find bar's Back handler closes it.
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
        escapeTakenInFront: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        val isEscape = keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BACK && fromPhysicalKeyboard && physicalBackIsEscape(sdkInt)
        return isEscape && findBarOpen && noModifiers && repeatCount == 0 && !inTerminal && !escapeTakenInFront
    }

    /**
     * Whether a press of [keyCode] closes the expanded tab rail open over the page
     * ([railOverlayOpen]): a plain Esc, wherever the focus is (the page, a terminal, the rail),
     * since the rail is in front of all of them. Read before the keyboard app and before the find
     * bar's Esc, the rail being in front of the find bar too. A physical keyboard's Back counts
     * as its Esc up to Android 12L ([physicalBackIsEscape]); from Android 13 the rail's Back
     * handler closes it.
     */
    fun escapeClosesTabRail(
        keyCode: Int,
        railOverlayOpen: Boolean,
        noModifiers: Boolean,
        repeatCount: Int = 0,
        fromPhysicalKeyboard: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        val isEscape = keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BACK && fromPhysicalKeyboard && physicalBackIsEscape(sdkInt)
        return isEscape && railOverlayOpen && noModifiers && repeatCount == 0
    }

    /**
     * Whether [shortcut] first closes the expanded tab rail open over the page. Every shortcut
     * that moves the keyboard or opens something in the page's place does (a new tab or terminal,
     * the address bar, find, Go to address, the palette, Settings, another tab, a layout), so it
     * lands where it would with the rail closed: left open, the rail would keep the keyboard
     * while a new tab or the find bar opened under the dimmed page. Closing the tab and
     * bookmarking the page change what the rail itself shows, and a screenshot takes the window
     * as it is, so those leave it open, as closing a tab from the rail does.
     */
    fun closesTabRail(shortcut: BrowserShortcut): Boolean = when (shortcut) {
        BrowserShortcut.CLOSE_TAB, BrowserShortcut.TOGGLE_BOOKMARK, BrowserShortcut.SCREENSHOT -> false
        else -> true
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
     * find bar, up to Android 12L ([physicalBackIsEscape]); from 13 it passes on to the
     * overlay's Back handler, which cancels it.
     */
    fun screenCaptureKey(
        keyCode: Int,
        isSystemKey: Boolean,
        fromPhysicalKeyboard: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ScreenCaptureKey = when {
        keyCode == KeyEvent.KEYCODE_ESCAPE -> ScreenCaptureKey.CANCEL
        keyCode == KeyEvent.KEYCODE_BACK && fromPhysicalKeyboard && physicalBackIsEscape(sdkInt) -> ScreenCaptureKey.CANCEL
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
