package com.rembrowser.rembrowse.util

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which keys of a physical keyboard skip the keyboard app and go straight to the page. */
class PhysicalTypingTest {

    private fun taken(keyCode: Int, char: Int = 0, ctrl: Boolean = false, meta: Boolean = false) =
        PhysicalTyping.goesStraightToPage(keyCode, char, ctrl, meta)

    @Test
    fun enterAndNumpadEnter_goStraightToThePage_whateverTheirChar() {
        assertTrue(taken(KeyEvent.KEYCODE_ENTER, '\n'.code))
        assertTrue(taken(KeyEvent.KEYCODE_ENTER, 0))
        assertTrue(taken(KeyEvent.KEYCODE_NUMPAD_ENTER, 0))
    }

    @Test
    fun enter_withAnyModifiers_isTaken_asNoBrowserShortcutUsesIt() {
        assertTrue(taken(KeyEvent.KEYCODE_ENTER, 0, ctrl = true))
        assertTrue(taken(KeyEvent.KEYCODE_ENTER, 0, meta = true))
        assertTrue(taken(KeyEvent.KEYCODE_ENTER, 0, ctrl = true, meta = true))
        assertTrue(taken(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, ctrl = true))
        assertTrue(taken(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, meta = true))
    }

    @Test
    fun lettersDigitsAndPunctuation_areNotTaken() {
        assertFalse(taken(KeyEvent.KEYCODE_A, 'a'.code))
        assertFalse(taken(KeyEvent.KEYCODE_A, 'A'.code))
        assertFalse(taken(KeyEvent.KEYCODE_5, '5'.code))
        assertFalse(taken(KeyEvent.KEYCODE_1, '!'.code))
        assertFalse(taken(KeyEvent.KEYCODE_COMMA, ','.code))
        assertFalse("AltGr-style character", taken(KeyEvent.KEYCODE_E, 0x20AC))
    }

    @Test
    fun space_backspace_andDelete_areNotTaken() {
        assertFalse(taken(KeyEvent.KEYCODE_SPACE, ' '.code))
        assertFalse(taken(KeyEvent.KEYCODE_SPACE, 0))
        assertFalse(taken(KeyEvent.KEYCODE_DEL, 0x7f))
        assertFalse(taken(KeyEvent.KEYCODE_DEL, 0))
        assertFalse(taken(KeyEvent.KEYCODE_FORWARD_DEL, 0))
    }

    @Test
    fun ctrlAndMetaShortcuts_areNotTaken() {
        assertFalse("Ctrl+A", taken(KeyEvent.KEYCODE_A, 'a'.code, ctrl = true))
        assertFalse("Meta+x", taken(KeyEvent.KEYCODE_X, 'x'.code, meta = true))
    }

    @Test
    fun controlCharacters_andKeysThatTypeNothing_keepTheirUsualPath() {
        assertFalse("Tab", taken(KeyEvent.KEYCODE_TAB, '\t'.code))
        assertFalse("Escape", taken(KeyEvent.KEYCODE_ESCAPE, 0x1b))
        assertFalse("Left", taken(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertFalse("Up", taken(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertFalse("F5", taken(KeyEvent.KEYCODE_F5, 0))
        assertFalse("Shift alone", taken(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertFalse("Home", taken(KeyEvent.KEYCODE_MOVE_HOME, 0))
    }

    @Test
    fun deadKeys_areNotTaken() {
        assertFalse(taken(KeyEvent.KEYCODE_GRAVE, KeyCharacterMap.COMBINING_ACCENT or '`'.code))
        assertFalse(taken(KeyEvent.KEYCODE_E, KeyCharacterMap.COMBINING_ACCENT or 0x301))
    }

    @Test
    fun theKeyIsPhysicalOnlyFromAHardwareDevice_notFromTheOnScreenKeyboard() {
        assertTrue(PhysicalTyping.isPhysical(deviceId = 3, flags = 0))
        assertTrue(PhysicalTyping.isPhysical(deviceId = 3, flags = KeyEvent.FLAG_FROM_SYSTEM))
        assertFalse("Virtual keyboard device", PhysicalTyping.isPhysical(KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        assertFalse("Soft keyboard flag", PhysicalTyping.isPhysical(3, KeyEvent.FLAG_SOFT_KEYBOARD))
        assertFalse(PhysicalTyping.isPhysical(3, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_FROM_SYSTEM))
    }

    @Test
    fun isEnter_coversBothEnterKeys_andNothingElse() {
        assertTrue(PhysicalTyping.isEnter(KeyEvent.KEYCODE_ENTER))
        assertTrue(PhysicalTyping.isEnter(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertFalse(PhysicalTyping.isEnter(KeyEvent.KEYCODE_SPACE))
        assertFalse(PhysicalTyping.isEnter(KeyEvent.KEYCODE_DEL))
    }
}
