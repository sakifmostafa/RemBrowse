package com.rembrowser.rembrowse.ui.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.ui.theme.RemDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where an extension popup appears, beside the rail or centred, measured in its window, and
 * when it appears.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ExtensionPopupDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var window: Window? = null
    private var windowView: View? = null
    private var page = Rect.Zero
    private var dismissals = 0

    private fun show(
        anchorToRail: () -> Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        railWidth: Dp = RemDimens.RailWidth,
        popup: () -> ExtensionPopupState = { ExtensionPopupState("vpn@example", "Proton VPN", "popup-vpn@example") }
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                RemBrowseTheme {
                    ExtensionPopupDialog(
                        popup = popup(),
                        onDismiss = { dismissals++ },
                        popupContent = { modifier ->
                            val view = LocalView.current
                            window = (view.parent as? DialogWindowProvider)?.window
                            windowView = view.rootView
                            // Where the popup's page is in its window, which is where it is on
                            // screen beside the rail (that window fills the screen)
                            Box(modifier.onGloballyPositioned { page = it.boundsInWindow() })
                        },
                        anchorToRail = anchorToRail(),
                        railWidth = railWidth
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val attributes get() = window!!.attributes
    private val dimmed get() = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
    private val windowWidth get() = windowView!!.width.toFloat()
    private val windowHeight get() = windowView!!.height.toFloat()

    // At mdpi a dp is a pixel. The popup starts 4dp past the rail; its page is its full width.
    private fun assertBesideTheRail(railWidth: Dp, layoutDirection: LayoutDirection) {
        val gap = railWidth.value + 4f
        if (layoutDirection == LayoutDirection.Ltr) {
            assertEquals(gap, page.left, 0.5f)
        } else {
            assertEquals(gap, windowWidth - page.right, 0.5f)
        }
        // At the foot of the window, above the popup's shadow margin, not in its middle
        assertEquals(8f, windowHeight - page.bottom, 0.5f)
        assertFalse(dimmed)
        // The window fills the screen: an animation of its own would move it all
        assertEquals(0, attributes.windowAnimations)
    }

    private fun assertCentred() {
        assertTrue(page.left > 0f)
        assertEquals(page.left, windowWidth - page.right, 1f)
        assertTrue(dimmed)
        // Opens and closes as a dialog does
        assertTrue(attributes.windowAnimations != 0)
    }

    @Test
    fun besideTheCollapsedRail_sitsBottomLeftWithoutDimming() {
        show(anchorToRail = { true })
        assertBesideTheRail(RemDimens.RailWidth, LayoutDirection.Ltr)
    }

    @Test
    fun besideTheExpandedRail_startsPastIt() {
        show(anchorToRail = { true }, railWidth = RemDimens.RailExpandedWidth)
        assertBesideTheRail(RemDimens.RailExpandedWidth, LayoutDirection.Ltr)
    }

    @Test
    fun rightToLeft_sitsOnTheRailsSide() {
        show(anchorToRail = { true }, layoutDirection = LayoutDirection.Rtl)
        assertBesideTheRail(RemDimens.RailWidth, LayoutDirection.Rtl)
    }

    @Test
    fun rightToLeft_besideTheExpandedRail() {
        show(anchorToRail = { true }, layoutDirection = LayoutDirection.Rtl, railWidth = RemDimens.RailExpandedWidth)
        assertBesideTheRail(RemDimens.RailExpandedWidth, LayoutDirection.Rtl)
    }

    @Test
    fun aLaterSize_keepsTheCornerBesideTheRail() {
        var popup by mutableStateOf(ExtensionPopupState("pass@proton.me", "Proton Pass", "popup-1"))
        show(anchorToRail = { true }, popup = { popup })

        popup = popup.copy(contentWidth = 356, contentHeight = 300)
        composeRule.waitForIdle()
        val before = page
        popup = popup.copy(contentWidth = 600, contentHeight = 560)
        composeRule.waitForIdle()

        // It grows away from its corner, up and to the right
        assertEquals(before.left, page.left, 0.5f)
        assertEquals(before.bottom, page.bottom, 0.5f)
        assertEquals(600f, page.width, 0.5f)
        assertTrue(page.top < before.top)
    }

    @Test
    @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun phoneSizedWindow_centresAndDims() {
        show(anchorToRail = { false })
        assertCentred()
    }

    @Test
    fun windowBecomingPhoneSized_centresAndDimsTheOpenPopup() {
        var anchored by mutableStateOf(true)
        show(anchorToRail = { anchored })

        anchored = false
        composeRule.waitForIdle()
        assertCentred()

        anchored = true
        composeRule.waitForIdle()
        assertBesideTheRail(RemDimens.RailWidth, LayoutDirection.Ltr)
    }

    @Test
    fun besideTheRail_aTapOutsideClosesIt() {
        show(anchorToRail = { true })
        val dialog = composeRule.onNode(isDialog())

        // On the popup: it stays
        dialog.performTouchInput { click(page.center) }
        composeRule.waitForIdle()
        assertEquals(0, dismissals)

        // On the page beside it, and on the rail
        dialog.performTouchInput { click(Offset(width - 20f, height / 2f)) }
        composeRule.waitForIdle()
        assertEquals(1, dismissals)
        dialog.performTouchInput { click(Offset(20f, height - 20f)) }
        composeRule.waitForIdle()
        assertEquals(2, dismissals)
    }

    private fun pageSize(): Pair<Dp, Dp> {
        val bounds = composeRule.onNodeWithTag("extension_popup_page", useUnmergedTree = true).getUnclippedBoundsInRoot()
        return (bounds.right - bounds.left) to (bounds.bottom - bounds.top)
    }

    @Test
    fun withoutAReportedSize_usesTheDefault() {
        show(anchorToRail = { true })
        assertEquals(420.dp to 540.dp, pageSize())
    }

    @Test
    fun aReportedSize_sizesThePopupToItsContent() {
        var popup by mutableStateOf(ExtensionPopupState("pass@proton.me", "Proton Pass", "popup-1"))
        show(anchorToRail = { true }, popup = { popup })

        // Proton Pass's content is 600 wide
        popup = popup.copy(contentWidth = 600, contentHeight = 560)
        composeRule.waitForIdle()
        assertEquals(600.dp to 560.dp, pageSize())

        // A narrow popup (Proton VPN) shrinks to fit
        popup = popup.copy(contentWidth = 356, contentHeight = 600)
        composeRule.waitForIdle()
        assertEquals(356.dp to 600.dp, pageSize())
    }

    @Test
    fun aReportedSize_staysWithinDesktopFirefoxLimits() {
        show(anchorToRail = { true }, popup = {
            ExtensionPopupState("x@example", "X", "popup-x", contentWidth = 1500, contentHeight = 2000)
        })
        assertEquals(800.dp to 600.dp, pageSize())
    }

    // When it is shown: the clock is driven by hand

    private var watched by mutableStateOf(ExtensionPopupState("x@example", "X", "popup-x"))
    private var shown = false

    private fun watch(popup: ExtensionPopupState) {
        watched = popup
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val state = watched
            shown = rememberPopupShown(state, atDefaultSize = state.contentWidth == 420 && state.contentHeight == 540)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** The popup helper's report of the content's size. */
    private fun report(width: Int, height: Int) {
        Snapshot.withMutableSnapshot { watched = watched.copy(contentWidth = width, contentHeight = height) }
    }

    private fun advance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis)
    }

    @Test
    fun aSizeIsShownOnceItHasSettled() {
        watch(ExtensionPopupState("vpn@proton.ch", "Proton VPN", "popup-vpn"))

        // Proton VPN on the emulator: 370 wide, then 420 at that width 100ms later
        advance(230)
        report(370, 565)
        advance(100)
        assertFalse(shown)
        report(420, 565)
        advance(POPUP_SIZE_SETTLE_MS - 50)
        assertFalse(shown)
        advance(100)
        assertTrue(shown)
    }

    @Test
    fun theSizeItAlreadyHas_isShownAtOnce() {
        watch(ExtensionPopupState("uBlock0@raymondhill.net", "uBlock Origin", "popup-ubo"))

        // uBlock Origin's popup is made for Android: it reports the default size it has
        advance(380)
        assertFalse(shown)
        report(420, 540)
        advance(50)
        assertTrue(shown)
    }

    @Test
    fun aPopupThatKeepsChanging_isShownAfterTheWait() {
        watch(ExtensionPopupState("x@example", "X", "popup-x"))

        var height = 200
        var elapsed = 0L
        while (elapsed < POPUP_SIZE_WAIT_MS - 100) {
            report(360, height)
            height += 40
            advance(100)
            elapsed += 100
            assertFalse(shown)
        }
        advance(150)
        assertTrue(shown)
    }

    @Test
    fun aPopupThatNeverSaysItsSize_isShownAfterTheWait() {
        watch(ExtensionPopupState("x@example", "X", "popup-x"))
        advance(POPUP_SIZE_WAIT_MS - 50)
        assertFalse(shown)
        advance(100)
        assertTrue(shown)
    }

    @Test
    fun onceShown_itStaysShown() {
        watch(ExtensionPopupState("x@example", "X", "popup-x"))
        report(360, 300)
        advance(POPUP_SIZE_SETTLE_MS + 50)
        assertTrue(shown)

        report(360, 480)
        advance(50)
        assertTrue(shown)
    }
}
