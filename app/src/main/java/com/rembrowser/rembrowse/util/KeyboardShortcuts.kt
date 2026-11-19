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
    TOGGLE_SPLIT_VIEW,
    LAYOUT_SINGLE,
    LAYOUT_SIDE_BY_SIDE,
    LAYOUT_STACKED,
    LAYOUT_GRID
}

/** Maps a hardware key press to a browser command. Pure so it can be unit tested on the JVM. */
object KeyboardShortcuts {

    fun resolve(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        repeatCount: Int = 0
    ): BrowserShortcut? {
        // A held key must not open a tab (or close one) per auto-repeat tick.
        if (repeatCount > 0 || meta) return null

        if (!ctrl && !alt && !shift && keyCode == KeyEvent.KEYCODE_F5) {
            return BrowserShortcut.RELOAD
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
            KeyEvent.KEYCODE_BACKSLASH -> BrowserShortcut.TOGGLE_SPLIT_VIEW
            KeyEvent.KEYCODE_1 -> BrowserShortcut.LAYOUT_SINGLE
            KeyEvent.KEYCODE_2 -> BrowserShortcut.LAYOUT_SIDE_BY_SIDE
            KeyEvent.KEYCODE_3 -> BrowserShortcut.LAYOUT_STACKED
            KeyEvent.KEYCODE_4 -> BrowserShortcut.LAYOUT_GRID
            else -> null
        }
    }
}
