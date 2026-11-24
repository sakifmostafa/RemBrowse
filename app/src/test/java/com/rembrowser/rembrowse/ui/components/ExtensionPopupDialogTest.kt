package com.rembrowser.rembrowse.ui.components

import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Where an extension popup's window goes, beside the rail or centred. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ExtensionPopupDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var window: Window? = null

    private fun show(
        anchorToRail: () -> Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        popup: () -> ExtensionPopupState = { ExtensionPopupState("vpn@example", "Proton VPN", "popup-vpn@example") }
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                RemBrowseTheme {
                    ExtensionPopupDialog(
                        popup = popup(),
                        onDismiss = {},
                        popupContent = { modifier ->
                            window = (LocalView.current.parent as? DialogWindowProvider)?.window
                            Box(modifier)
                        },
                        anchorToRail = anchorToRail()
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val attributes get() = window!!.attributes
    private val dimmed get() = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0

    @Test
    fun besideTheRail_sitsBottomLeftWithoutDimming() {
        show(anchorToRail = { true })

        assertEquals(Gravity.BOTTOM or Gravity.LEFT, attributes.gravity)
        // 56dp rail + 4dp gap - 8dp shadow margin, at mdpi
        assertEquals(52, attributes.x)
        assertFalse(dimmed)
    }

    @Test
    fun windowBecomingPhoneSized_centresAndDimsTheOpenPopup() {
        var anchored by mutableStateOf(true)
        show(anchorToRail = { anchored })

        anchored = false
        composeRule.waitForIdle()

        assertEquals(Gravity.CENTER, attributes.gravity)
        assertEquals(0, attributes.x)
        assertTrue(dimmed)

        anchored = true
        composeRule.waitForIdle()
        assertEquals(Gravity.BOTTOM or Gravity.LEFT, attributes.gravity)
        assertFalse(dimmed)
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

    @Test
    fun rightToLeft_sitsOnTheRailsSide() {
        show(anchorToRail = { true }, layoutDirection = LayoutDirection.Rtl)

        assertEquals(Gravity.BOTTOM or Gravity.RIGHT, attributes.gravity)
        assertEquals(52, attributes.x)
    }
}
