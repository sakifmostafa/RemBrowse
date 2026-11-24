package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo

/** The modifier bits the system's Keyboard Shortcuts sheet draws as Ctrl, Shift and Alt. */
fun ShortcutHelp.modifierState(): Int =
    (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0)

/**
 * The browser's shortcuts as the system's Keyboard Shortcuts sheet (Meta+/ on a keyboard, or
 * Keyboard shortcuts in the command palette) takes them, one group per [KeyboardShortcuts.HELP_GROUPS] entry.
 */
fun keyboardShortcutGroups(
    groups: List<Pair<String, List<ShortcutHelp>>> = KeyboardShortcuts.HELP_GROUPS
): List<KeyboardShortcutGroup> = groups.map { (title, items) ->
    KeyboardShortcutGroup(
        title,
        items.map { KeyboardShortcutInfo(it.label, it.keyCode, it.modifierState()) }
    )
}
