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
        popups.sized(PopupContentSize(ubo, 300, 400))
        assertNull(popups.state.value?.contentWidth)

        popups.sized(PopupContentSize(vpn, 420, 565))
        assertEquals(420, popups.state.value?.contentWidth)
        assertEquals(565, popups.state.value?.contentHeight)
    }

    // --- Sizes that follow the viewport ---
    // The numbers are Gecko's: each page was measured the way the popup helper does, in a
    // viewport of the popup's size, then given the size it reported, and measured again.

    /** A report of [height] measured in a viewport [viewportHeight] tall, 420 wide in a 420 one. */
    private fun sized(height: Int, viewportHeight: Int, extensionId: String = vpn) =
        popups.sized(PopupContentSize(extensionId, 420, height, 420, viewportHeight))

    private val height get() = popups.state.value?.contentHeight

    @Test
    fun contentSizedFromTheViewport_settlesAfterTwoResizes() {
        // A 44px header over `min-height: calc(100vh - 44px)`, with the body's 8px margin: it
        // always measures 16px more than its viewport. Taken as asked, it grew 16px a round,
        // 540, 556, 572, 588, up to the largest popup.
        open(vpn)
        sized(556, viewportHeight = 540)
        assertEquals(556, height)
        // Right after the first report, a step with the viewport is still taken
        sized(572, viewportHeight = 556)
        assertEquals(572, height)

        sized(588, viewportHeight = 572)
        assertEquals(572, height)
        // And again if the viewport moves some other way, such as a shorter window
        sized(532, viewportHeight = 516)
        assertEquals(572, height)
    }

    @Test
    fun contentTwoPixelsTallerThanItsViewport_settlesToo() {
        // The smallest change the helper reports, 2px, still counts as the viewport moving
        open(vpn)
        sized(542, viewportHeight = 540)
        sized(544, viewportHeight = 542)
        sized(546, viewportHeight = 544)
        assertEquals(544, height)
    }

    @Test
    fun contentThatReallyGrows_stillGrows() {
        open(vpn)
        sized(300, viewportHeight = 540)
        assertEquals(300, height)
        sized(300, viewportHeight = 300)
        assertEquals(300, height)

        // A list opens: same viewport, more content
        sized(450, viewportHeight = 300)
        assertEquals(450, height)
        sized(450, viewportHeight = 450)
        assertEquals(450, height)

        // And closes again
        sized(320, viewportHeight = 450)
        assertEquals(320, height)
    }

    @Test
    fun rowsAppearingOneAtATime_growThePopupRowByRow() {
        open(vpn)
        sized(300, viewportHeight = 540)
        sized(300, viewportHeight = 300)
        // A 48px row appears
        sized(348, viewportHeight = 300)
        assertEquals(348, height)
        // The next one is measured just as the popup grew by the first: the same 48px as the
        // viewport, but the content's own
        sized(396, viewportHeight = 348)
        assertEquals(396, height)
        sized(396, viewportHeight = 396)
        assertEquals(396, height)
    }

    @Test
    fun aReportForTheOtherDimension_doesNotLetTheHeightGrowAStep() {
        open(vpn)
        sized(556, viewportHeight = 540)
        sized(572, viewportHeight = 556)
        sized(588, viewportHeight = 572)
        assertEquals(572, height)

        // The content's width changes twice; its height and viewport, already judged, come again
        popups.sized(PopupContentSize(vpn, 430, 588, 420, 572))
        assertEquals(430, popups.state.value?.contentWidth)
        assertEquals(572, height)
        popups.sized(PopupContentSize(vpn, 440, 589, 430, 572))
        assertEquals(440, popups.state.value?.contentWidth)
        assertEquals(572, height)
        popups.sized(PopupContentSize(vpn, 450, 590, 440, 572))
        assertEquals(572, height)
    }

    @Test
    fun aReportForTheOtherDimension_keepsTheAllowanceForTheNextRow() {
        open(vpn)
        sized(300, viewportHeight = 540)
        sized(300, viewportHeight = 300)
        sized(348, viewportHeight = 300)
        // The width changes before the popup has grown by the row
        popups.sized(PopupContentSize(vpn, 430, 348, 420, 300))
        // The next row, measured just as the popup grew by the first, is still taken
        popups.sized(PopupContentSize(vpn, 430, 396, 430, 348))
        assertEquals(396, height)
    }

    @Test
    fun growthMeasuredJustAfterAResize_isStillTaken() {
        open(vpn)
        sized(556, viewportHeight = 540)
        // The viewport grew 16px and the content 136px: 120px of it is the content's own
        sized(692, viewportHeight = 556)
        assertEquals(692, height)
    }

    @Test
    fun aKeyboardCountedOverThePage_doesNotGrowThePopup() {
        // Proton VPN signed in: its page is never shorter than 564px and otherwise fills the
        // viewport (`max-height: calc(100vh - 44px)` under a 44px bar), and it focuses its search
        // box, which brings up the keyboard. Gecko lays a page out taller by the height of a
        // keyboard reported over it; taken as asked, each report grew the popup by that much and
        // it stayed at the largest popup after the keyboard was gone.
        open(vpn)
        sized(564, viewportHeight = 540)
        sized(564, viewportHeight = 564)
        assertEquals(564, height)

        for (keyboard in listOf(30, 60, 90, 40, 0)) {
            sized(564 + keyboard, viewportHeight = 564 + keyboard)
            assertEquals(564, height)
        }
    }

    @Test
    fun onAScreenWhoseDensityTheEngineRounds_aPageFillingItsViewportSettles() {
        // At 420 dpi Gecko puts 23 of its 60 units in a device pixel, so a popup H dp tall is
        // H * 1.00625 CSS px. Proton VPN signed in fills its viewport: each report was a few
        // pixels more than the popup, 565, 569, 573, 577... up to the largest popup.
        open(vpn)
        sized(565, viewportHeight = 543) // still loading, in the 540 dp default
        sized(565, viewportHeight = 569) // at 565 dp
        sized(569, viewportHeight = 569) // signed in, filling it
        sized(573, viewportHeight = 573) // at 569 dp
        sized(577, viewportHeight = 577) // at 573 dp
        assertEquals(573, height)
    }

    @Test
    fun aWidthThatFollowsTheViewport_settlesToo() {
        open(vpn)
        // `width: 100vw` with the body's margin: 16px wider than whatever it is given
        popups.sized(PopupContentSize(vpn, 436, 300, 420, 300))
        popups.sized(PopupContentSize(vpn, 452, 300, 436, 300))
        popups.sized(PopupContentSize(vpn, 468, 300, 452, 300))
        assertEquals(452, popups.state.value?.contentWidth)
        // The height, measured in an unchanged viewport, is taken all along
        assertEquals(300, height)
    }

    @Test
    fun contentShrinkingWithTheViewport_stopsShrinkingThePopup() {
        // `height: calc(100vh - 20px)`: 20px short of whatever it is given
        open(vpn)
        sized(520, viewportHeight = 540)
        sized(500, viewportHeight = 520)
        sized(480, viewportHeight = 500)
        assertEquals(500, height)
    }

    @Test
    fun popupsWithTheirOwnViewport_takeEveryReportAsBefore() {
        // uBlock Origin's popup carries its own viewport tag and reports the size it has, with
        // no viewport to compare
        open(ubo)
        popups.sized(PopupContentSize(ubo, 420, 540))
        popups.sized(PopupContentSize(ubo, 420, 500))
        assertEquals(500, popups.state.value?.contentHeight)
        popups.sized(PopupContentSize(ubo, 420, 520))
        assertEquals(520, popups.state.value?.contentHeight)
    }

    @Test
    fun aReportWithoutAViewport_isTakenAsAsked() {
        open(vpn)
        sized(556, viewportHeight = 540)
        popups.sized(PopupContentSize(vpn, 420, 572))
        assertEquals(572, height)
    }

    @Test
    fun eachPopupComparesOnlyItsOwnReports() {
        open(vpn)
        sized(556, viewportHeight = 540)
        popups.close()

        // Opened again, its first report is taken as it is
        open(vpn)
        sized(572, viewportHeight = 556)
        assertEquals(572, height)
    }

    @Test
    fun aChangeOfTheContentsOwn_isToldFromOneThatFollowsTheViewport() {
        assertTrue(ExtensionPopups.changedByItself(300, 300, 348, 300))
        assertTrue(ExtensionPopups.changedByItself(556, 540, 692, 556))
        // Without a viewport, any change of 2px or more is the content's
        assertTrue(ExtensionPopups.changedByItself(556, null, 572, null))
        assertFalse(ExtensionPopups.changedByItself(556, 540, 572, 556))
        // A content that stayed while its viewport moved did not change
        assertFalse(ExtensionPopups.changedByItself(564, 540, 564, 564))
    }

    @Test
    fun followsViewport_allowsForRoundingButNotForRealChanges() {
        // Sizes are rounded up to whole pixels, and so is the viewport
        assertTrue(ExtensionPopups.followsViewport(556, 540, 574, 556))
        assertTrue(ExtensionPopups.followsViewport(556, 540, 570, 556))
        assertFalse(ExtensionPopups.followsViewport(556, 540, 575, 556))
        assertFalse(ExtensionPopups.followsViewport(556, 540, 569, 556))
        // A viewport that moved less than the helper reports says nothing either way
        assertFalse(ExtensionPopups.followsViewport(556, 540, 557, 541))
        assertTrue(ExtensionPopups.followsViewport(556, 540, 558, 542))
        // Without a viewport there is nothing to compare
        assertFalse(ExtensionPopups.followsViewport(556, null, 572, 556))
        assertFalse(ExtensionPopups.followsViewport(556, 540, 572, null))
    }
}
