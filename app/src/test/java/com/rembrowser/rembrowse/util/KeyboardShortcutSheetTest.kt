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
        assertEquals(KeyboardShortcuts.HELP_GROUPS.map { it.first }, groups.map { it.label.toString() })
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
    }

    @Test
    fun inATerminal_theSheetListsOnlyTheKeysThatReachTheBrowser() {
        val groups = keyboardShortcutGroups(inTerminal = true)
        assertEquals(listOf("Tabs", "Page"), groups.map { it.label.toString() })
        val labels = groups.flatMap { it.items }.map { it.label.toString() }
        assertEquals(listOf("New tab", "New terminal", "Close tab", "Next tab", "Previous tab", "Take screenshot"), labels)
    }
}
