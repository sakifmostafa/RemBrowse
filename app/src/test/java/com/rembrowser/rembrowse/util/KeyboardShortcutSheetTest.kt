package com.rembrowser.rembrowse.util

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The groups handed to the system's Keyboard Shortcuts sheet (Meta+/). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyboardShortcutSheetTest {

    @Test
    fun everyGroupAndRow_reachTheSheetWithTheirKeysAndModifiers() {
        val groups = keyboardShortcutGroups()
        assertEquals(
            KeyboardShortcuts.HELP_GROUPS.map { it.first } + "While taking a screenshot",
            groups.map { it.label.toString() }
        )
        groups.zip(KeyboardShortcuts.HELP_GROUPS).forEach { (group, help) ->
            assertEquals(help.second.map { it.label }, group.items.map { it.label.toString() })
            assertEquals(help.second.map { it.keyCode }, group.items.map { it.keycode })
        }

        fun row(label: String) = groups.flatMap { it.items }.single { it.label.toString() == label }
        assertEquals(KeyEvent.KEYCODE_T, row("New tab").keycode)
        assertEquals(KeyEvent.META_CTRL_ON, row("New tab").modifiers)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, row("New terminal").modifiers)
        assertEquals(KeyEvent.META_ALT_ON, row("Back").modifiers)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, row("Back").keycode)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON, row("Take screenshot").modifiers)
        assertEquals(KeyEvent.KEYCODE_COMMA, row("Settings").keycode)

        // The screenshot overlay's own keys, pressed alone
        val overlay = groups.last().items
        assertEquals(
            listOf("Capture the focused pane", "Capture the whole page", "Cancel the screenshot"),
            overlay.map { it.label.toString() }
        )
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE), overlay.map { it.keycode })
        assertEquals(listOf(0, 0, 0), overlay.map { it.modifiers })
    }

    @Test
    fun inATerminal_theSheetListsTheKeysThatReachTheBrowser() {
        val groups = keyboardShortcutGroups(inTerminal = true)
        assertEquals(
            listOf("Tabs", "Navigation", "Page", "Split view", "RemBrowse", "While taking a screenshot"),
            groups.map { it.label.toString() }
        )
        val labels = groups.dropLast(1).flatMap { it.items }.map { it.label.toString() }
        assertEquals(
            listOf(
                "New tab", "New terminal", "Close tab", "Next tab", "Previous tab", "Address bar", "Go to address",
                "Take screenshot", "Split view on or off", "Command palette", "Settings"
            ),
            labels
        )
        // The overlay has the keyboard while it is up, a terminal's too
        assertEquals(3, groups.last().items.size)

        fun row(label: String) = groups.flatMap { it.items }.single { it.label.toString() == label }
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertEquals(KeyEvent.META_CTRL_ON, row("New tab").modifiers)
        assertEquals(KeyEvent.KEYCODE_L, row("Address bar").keycode)
        assertEquals(ctrlShift, row("Address bar").modifiers)
        assertEquals(ctrlShift, row("Go to address").modifiers)
        assertEquals(ctrlShift, row("Command palette").modifiers)
        assertEquals(KeyEvent.KEYCODE_COMMA, row("Settings").keycode)
        assertEquals(ctrlShift, row("Settings").modifiers)
        assertEquals(KeyEvent.KEYCODE_BACKSLASH, row("Split view on or off").keycode)
        assertEquals(ctrlShift, row("Split view on or off").modifiers)
    }
}
