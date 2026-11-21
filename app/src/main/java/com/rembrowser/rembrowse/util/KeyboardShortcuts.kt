package com.rembrowser.rembrowse.util

import android.view.KeyEvent

enum class BrowserShortcut {
    NEW_TAB,
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
    GO_TO_ADDRESS
}

/** Maps a hardware key press to a browser command. Pure so it can be unit tested on the JVM. */
object KeyboardShortcuts {

    fun resolve(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        repeatCount: Int = 0,
        /**
         * A terminal has the keyboard. Its shell owns Ctrl+W, Ctrl+R, Ctrl+L and the rest, Alt
         * combinations and the function keys, so the browser keeps only Ctrl+Tab and moves its
         * letter shortcuts to Ctrl+Shift (Ctrl+Shift+T opens a tab, Ctrl+Shift+W closes one), as
         * terminal apps do. Ctrl+Shift+C and V copy and paste in the terminal itself.
         */
        inTerminal: Boolean = false
    ): BrowserShortcut? {
        // A held key must not open a tab (or close one) per auto-repeat tick.
        if (repeatCount > 0 || meta) return null
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
        // Ctrl+Shift+<key> combinations belong to the page or the system, not to us.
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
            KeyEvent.KEYCODE_BACKSLASH -> BrowserShortcut.TOGGLE_SPLIT_VIEW
            KeyEvent.KEYCODE_1 -> BrowserShortcut.LAYOUT_SINGLE
            KeyEvent.KEYCODE_2 -> BrowserShortcut.LAYOUT_SIDE_BY_SIDE
            KeyEvent.KEYCODE_3 -> BrowserShortcut.LAYOUT_STACKED
            KeyEvent.KEYCODE_4 -> BrowserShortcut.LAYOUT_GRID
            KeyEvent.KEYCODE_5 -> BrowserShortcut.LAYOUT_ONE_AND_TWO
            else -> null
        }
    }

    private fun resolveInTerminal(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): BrowserShortcut? {
        if (!ctrl || alt) return null
        if (keyCode == KeyEvent.KEYCODE_TAB) return if (shift) BrowserShortcut.PREVIOUS_TAB else BrowserShortcut.NEXT_TAB
        if (!shift) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_T -> BrowserShortcut.NEW_TAB
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
