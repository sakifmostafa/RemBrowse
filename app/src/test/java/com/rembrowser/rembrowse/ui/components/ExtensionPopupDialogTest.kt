package com.rembrowser.rembrowse.ui.components

import android.app.Application
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
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
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class ExtensionPopupDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var window: Window? = null

    private fun show(anchorToRail: () -> Boolean, layoutDirection: LayoutDirection = LayoutDirection.Ltr) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                RemBrowseTheme {
                    ExtensionPopupDialog(
                        popup = ExtensionPopupState("vpn@example", "Proton VPN", "popup-vpn@example"),
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

    @Test
    fun rightToLeft_sitsOnTheRailsSide() {
        show(anchorToRail = { true }, layoutDirection = LayoutDirection.Rtl)

        assertEquals(Gravity.BOTTOM or Gravity.RIGHT, attributes.gravity)
        assertEquals(52, attributes.x)
    }
}
