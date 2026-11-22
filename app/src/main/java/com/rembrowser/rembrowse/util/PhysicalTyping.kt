package com.rembrowser.rembrowse.util

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Which keys from a physical keyboard go straight to a web page, past the keyboard app (input
 * method): Enter, with any modifiers (Ctrl+Enter sends in many web apps; no browser shortcut uses
 * Enter). Through the keyboard app it arrived as a typed line break,
 * which a chat box that sends on Enter can't tell from text; straight to the page it is a real
 * Enter keydown the page can cancel (measured with browse.example.com/keys.html, 0.13.9).
 *
 * Only Enter: inside a text box, GeckoView passes just Enter, Tab, Backspace and Delete on as real
 * keys (`GeckoEditable.sendKeyEvent`); any other key becomes inserted text with a made-up keydown
 * whichever way it is sent, so routing letters past the keyboard app would gain nothing and would
 * bypass keyboards that build characters (accents, transliteration). Pure, so it is tested on the JVM.
 */
object PhysicalTyping {

    /** A key from a physical keyboard, not an on-screen one. */
    fun isPhysical(deviceId: Int, flags: Int): Boolean =
        deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0

    /**
     * Whether the key goes straight to the page: Enter, whatever modifiers are held. The other
     * parameters are unused today; kept so the rule can grow without changing its callers.
     */
    @Suppress("UNUSED_PARAMETER")
    fun goesStraightToPage(keyCode: Int, unicodeChar: Int, ctrl: Boolean, meta: Boolean): Boolean =
        isEnter(keyCode)

    fun isEnter(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}
