package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.engine.ExtensionPopups.Click
import com.rembrowser.rembrowse.engine.ExtensionPopups.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a click on an extension's button does to the popup on screen. */
class ExtensionPopupsTest {

    private val popups = ExtensionPopups<Any>(doubleClickMs = 300)
    private val vpn = "vpn@proton.ch"
    private val ubo = "uBlock0@raymondhill.net"

    private fun open(extensionId: String, session: Any = Any()) =
        popups.open(extensionId, extensionId, session)

    @Test
    fun aClickWithNothingOpen_goesToTheExtension() {
        assertEquals(Click.TRIGGER, popups.click(vpn, now = 1_000))
        assertEquals(Toggle.OPEN, popups.toggle(vpn))
    }

    @Test
    fun aClickOnTheButtonOfAPopupOnScreen_closesIt() {
        assertEquals(Click.TRIGGER, popups.click(vpn, now = 1_000))
        val popup = open(vpn)
        popups.shown(popup.sessionId)

        assertEquals(Click.CLOSE, popups.click(vpn, now = 2_000))
        assertEquals(Toggle.CLOSE, popups.toggle(vpn))
    }

    @Test
    fun aClickOnTheButtonOfAPopupStillAppearing_leavesItToAppear() {
        assertEquals(Click.TRIGGER, popups.click(vpn, now = 1_000))
        open(vpn)

        // Proton VPN's popup takes most of a second to say its size: nothing showed yet
        assertEquals(Click.IGNORE, popups.click(vpn, now = 1_500))
        assertEquals(Toggle.KEEP, popups.toggle(vpn))
        assertEquals(vpn, popups.state.value?.extensionId)
    }

    @Test
    fun theSecondClickOfADoubleClick_doesNothing() {
        assertEquals(Click.TRIGGER, popups.click(ubo, now = 1_000))
        val popup = open(ubo)
        // uBlock Origin's popup can be on screen before the second click lands
        popups.shown(popup.sessionId)
        assertEquals(Click.IGNORE, popups.click(ubo, now = 1_150))

        // A click well after that is a click of its own
        assertEquals(Click.CLOSE, popups.click(ubo, now = 1_800))
    }

    @Test
    fun bothClicksOfADoubleClickBeforeAnythingOpened_openItOnce() {
        // Both reach the button before the engine has answered the first
        assertEquals(Click.TRIGGER, popups.click(ubo, now = 1_000))
        assertEquals(Click.IGNORE, popups.click(ubo, now = 1_003))
    }

    @Test
    fun quickClicksOnTwoButtons_areNotADoubleClick() {
        assertEquals(Click.TRIGGER, popups.click(vpn, now = 1_000))
        open(vpn)
        assertEquals(Click.TRIGGER, popups.click(ubo, now = 1_100))
        assertEquals(Toggle.OPEN, popups.toggle(ubo))
    }

    @Test
    fun anotherExtensionsButton_opensItsPopupInPlaceOfTheOpenOne() {
        val popup = open(vpn)
        popups.shown(popup.sessionId)

        assertEquals(Click.TRIGGER, popups.click(ubo, now = 5_000))
        assertEquals(Toggle.OPEN, popups.toggle(ubo))
    }

    @Test
    fun eachPopupHasAnIdOfItsOwn_evenForTheSameExtension() {
        val first = open(vpn)
        popups.close()
        val second = open(vpn)
        assertNotEquals(first.sessionId, second.sessionId)
        assertNotEquals(first, second)
    }

    @Test
    fun aLateNoticeFromAnEarlierPopup_doesNotCountForTheNewOne() {
        val first = open(vpn)
        popups.close()
        open(vpn)

        popups.shown(first.sessionId)
        assertEquals(Toggle.KEEP, popups.toggle(vpn))
    }

    @Test
    fun aNewPopupStartsUnseen() {
        val first = open(vpn)
        popups.shown(first.sessionId)
        popups.close()
        open(vpn)
        assertEquals(Toggle.KEEP, popups.toggle(vpn))
    }

    @Test
    fun closing_handsBackTheSessionOnce() {
        val session = Any()
        open(vpn, session)
        assertSame(session, popups.close())
        assertNull(popups.close())
        assertNull(popups.state.value)
    }

    @Test
    fun aCloseRequestFromAReplacedPopup_isNotTheCurrentOnes() {
        val old = Any()
        val new = Any()
        open(vpn, old)
        popups.close()
        open(ubo, new)

        assertFalse(popups.isCurrent(old))
        assertTrue(popups.isCurrent(new))
    }

    @Test
    fun aSizeIsKeptOnlyForTheOpenPopup() {
        open(vpn)
        popups.sized(ubo, 300, 400)
        assertNull(popups.state.value?.contentWidth)

        popups.sized(vpn, 420, 565)
        assertEquals(420, popups.state.value?.contentWidth)
        assertEquals(565, popups.state.value?.contentHeight)
    }
}
