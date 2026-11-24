package com.rembrowser.rembrowse.util

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** How a physical keyboard's key presses, repeats and releases follow each other to a page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhysicalKeyRouterTest {

    private class FakePage(
        var takes: Boolean = true,
        var sends: Boolean = true
    ) : PageKeyTarget {
        val sent = mutableListOf<Pair<Int, Int>>()
        val askedEnter = mutableListOf<Boolean>()
        override fun takesKeysPastKeyboardApp(enter: Boolean): Boolean {
            askedEnter += enter
            return takes
        }
        override fun sendKeyPastKeyboardApp(event: KeyEvent): Boolean {
            sent += event.action to event.keyCode
            return sends
        }
    }

    private val router = PhysicalKeyRouter()
    private val page = FakePage()

    private fun key(
        action: Int,
        keyCode: Int,
        meta: Int = 0,
        repeat: Int = 0,
        deviceId: Int = KeyCharacterMap.BUILT_IN_KEYBOARD,
        flags: Int = 0
    ) = KeyEvent(0, 0, action, keyCode, repeat, meta, deviceId, 0, flags, 0)

    private fun down(code: Int, meta: Int = 0, repeat: Int = 0, deviceId: Int = KeyCharacterMap.BUILT_IN_KEYBOARD, flags: Int = 0) =
        key(KeyEvent.ACTION_DOWN, code, meta, repeat, deviceId, flags)

    private fun up(code: Int, meta: Int = 0) = key(KeyEvent.ACTION_UP, code, meta)

    private fun route(event: KeyEvent, focused: PageKeyTarget? = page, swallow: () -> Boolean = { false }) =
        router.route(event, focused, swallow)

    private val enter = KeyEvent.KEYCODE_ENTER
    private val numpadEnter = KeyEvent.KEYCODE_NUMPAD_ENTER

    @Test
    fun anEnterPress_isTakenAndSent_andItsRepeatAndReleaseFollow() {
        assertTrue(route(down(enter)))
        assertTrue(route(down(enter, repeat = 1)))
        assertTrue(route(up(enter)))
        assertEquals(
            listOf(
                KeyEvent.ACTION_DOWN to enter,
                KeyEvent.ACTION_DOWN to enter,
                KeyEvent.ACTION_UP to enter
            ),
            page.sent
        )
    }

    @Test
    fun ctrlEnterAndMetaEnter_areTakenAndFollowed() {
        for (meta in listOf(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        )) {
            page.sent.clear()
            assertTrue(route(down(enter, meta = meta)))
            assertTrue(route(up(enter, meta = meta)))
            assertEquals(2, page.sent.size)
        }
    }

    @Test
    fun numpadEnter_isTakenToo() {
        assertTrue(route(down(numpadEnter)))
        assertTrue(route(up(numpadEnter)))
        assertEquals(2, page.sent.size)
    }

    @Test
    fun repeatAndRelease_goToTheSamePage_evenIfFocusMovedOrIsGone() {
        val other = FakePage()
        assertTrue(route(down(enter)))
        assertTrue(route(down(enter, repeat = 1), focused = other))
        assertTrue(route(up(enter), focused = null))
        assertEquals(3, page.sent.size)
        assertTrue(other.sent.isEmpty())
    }

    @Test
    fun afterTheRelease_theKeyIsNoLongerFollowed() {
        route(down(enter))
        route(up(enter))
        assertFalse("A stray repeat", route(down(enter, repeat = 1)))
        assertFalse("A second release", route(up(enter)))
    }

    private fun assertNotTaken(press: KeyEvent, release: KeyEvent, focused: PageKeyTarget? = page) {
        assertFalse(route(press, focused))
        assertFalse("Its release is left alone", route(release, focused))
    }

    @Test
    fun letters_space_backspace_delete_tab_andArrows_areNotTaken_andNeitherIsTheirRelease() {
        assertNotTaken(down(KeyEvent.KEYCODE_A), up(KeyEvent.KEYCODE_A))
        assertNotTaken(down(KeyEvent.KEYCODE_SPACE), up(KeyEvent.KEYCODE_SPACE))
        assertNotTaken(down(KeyEvent.KEYCODE_DEL), up(KeyEvent.KEYCODE_DEL))
        assertNotTaken(down(KeyEvent.KEYCODE_FORWARD_DEL), up(KeyEvent.KEYCODE_FORWARD_DEL))
        assertNotTaken(down(KeyEvent.KEYCODE_A, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON), up(KeyEvent.KEYCODE_A))
        assertNotTaken(down(KeyEvent.KEYCODE_TAB), up(KeyEvent.KEYCODE_TAB))
        assertNotTaken(down(KeyEvent.KEYCODE_DPAD_LEFT), up(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNotTaken(down(KeyEvent.KEYCODE_F5), up(KeyEvent.KEYCODE_F5))
        assertTrue(page.sent.isEmpty())
        assertTrue("Not even asked", page.askedEnter.isEmpty())
    }

    @Test
    fun virtualKeyboardAndSoftKeyboardFlag_areNotTaken() {
        assertNotTaken(down(enter, deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD), up(enter))
        assertNotTaken(down(enter, flags = KeyEvent.FLAG_SOFT_KEYBOARD), up(enter))
        assertTrue(page.sent.isEmpty())
    }

    @Test
    fun aPageThatRefuses_orFailsToSend_leavesTheKeyAndItsReleaseAlone() {
        page.takes = false
        assertNotTaken(down(enter), up(enter))
        assertTrue(page.sent.isEmpty())

        val failing = FakePage(sends = false)
        assertNotTaken(down(numpadEnter), up(numpadEnter), focused = failing)
        assertEquals("Tried once, on the press", 1, failing.sent.size)
    }

    @Test
    fun theKeyboardAppQuestion_isAskedAsEnter() {
        route(down(enter))
        route(down(KeyEvent.KEYCODE_A))
        assertEquals(listOf(true), page.askedEnter)
    }

    @Test
    fun noFocusedPage_nothingIsTaken() {
        assertNotTaken(down(enter), up(enter), focused = null)
    }

    @Test
    fun anEnterReleaseThatMustBeSwallowed_isSwallowedAndClearsTheTakenEntry() {
        assertTrue(route(down(KeyEvent.KEYCODE_ENTER)))
        assertTrue(route(up(KeyEvent.KEYCODE_ENTER), swallow = { true }))
        assertEquals("The page got only the press", 1, page.sent.size)
        assertFalse("Cleared: a later release finds nothing", route(up(KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun anEnterReleaseThatIsNotSwallowed_followsItsPress() {
        assertTrue(route(down(KeyEvent.KEYCODE_ENTER)))
        assertTrue(route(up(KeyEvent.KEYCODE_ENTER)))
        assertEquals(2, page.sent.size)
    }

    @Test
    fun swallowEnterRelease_isNotAskedForOtherKeys_orForPresses() {
        var asked = 0
        val swallow = { asked++; false }
        route(down(KeyEvent.KEYCODE_A), swallow = swallow)
        route(up(KeyEvent.KEYCODE_A), swallow = swallow)
        route(down(KeyEvent.KEYCODE_ENTER), swallow = swallow)
        route(down(KeyEvent.KEYCODE_ENTER, repeat = 1), swallow = swallow)
        assertEquals(0, asked)
        route(up(KeyEvent.KEYCODE_ENTER), swallow = swallow)
        assertEquals(1, asked)
    }

    @Test
    fun anEnterReleaseWithNothingTaken_isStillSwallowedWhenAsked() {
        // The Enter that submitted the address bar: its press never reached a page
        assertTrue(route(up(KeyEvent.KEYCODE_ENTER), focused = null, swallow = { true }))
        assertTrue(page.sent.isEmpty())
    }

    @Test
    fun severalKeysHeldAtOnce_eachFollowItsOwnPress() {
        val first = FakePage()
        val second = FakePage()
        // Shift held (not taken), Enter on one page, numpad Enter on another, released crosswise
        assertFalse("Shift alone types nothing", route(down(KeyEvent.KEYCODE_SHIFT_LEFT), first))
        assertTrue(route(down(enter, meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON), first))
        assertTrue(route(down(numpadEnter), second))
        assertFalse("A letter in between is left alone", route(down(KeyEvent.KEYCODE_A), second))
        assertTrue(route(up(enter), second))
        assertTrue(route(up(numpadEnter), first))
        assertFalse("Shift's release is left alone", route(up(KeyEvent.KEYCODE_SHIFT_LEFT), first))
        assertFalse("The letter's release too", route(up(KeyEvent.KEYCODE_A), second))

        assertEquals(listOf(KeyEvent.ACTION_DOWN to enter, KeyEvent.ACTION_UP to enter), first.sent)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to numpadEnter, KeyEvent.ACTION_UP to numpadEnter), second.sent)
    }
}
