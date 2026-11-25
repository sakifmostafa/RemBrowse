package com.rembrowser.rembrowse.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Go to address sizes itself by the window as the configuration gives it: without the system
 * bars on Android 13 and 14, as it was laid out and tried with. Robolectric draws no system bars, so
 * a configuration that leaves 48dp of bars out of the window is put in by hand.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class ScreenSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** What screenSizeDp() and LocalWindowInfo say, under a configuration 48dp shorter and narrower than the window. */
    private fun measure(): Pair<DpSize, DpSize> {
        var screen = DpSize.Zero
        var container = DpSize.Zero
        composeRule.setContent {
            val withoutBars = Configuration(LocalConfiguration.current).apply {
                screenWidthDp -= 48
                screenHeightDp -= 48
            }
            CompositionLocalProvider(LocalConfiguration provides withoutBars) {
                screen = screenSizeDp()
                container = LocalWindowInfo.current.containerDpSize
            }
        }
        composeRule.waitForIdle()
        return screen to container
    }

    @Test
    @Config(sdk = [33])
    fun onAndroid13_theConfigurationsSize() = assertConfigurationsSize()

    @Test
    @Config(sdk = [34])
    fun onAndroid14_theConfigurationsSize() = assertConfigurationsSize()

    private fun assertConfigurationsSize() {
        val (screen, container) = measure()
        assertNotEquals(DpSize.Zero, container)
        assertEquals(DpSize(container.width - 48.dp, container.height - 48.dp), screen)
    }

    @Test
    @Config(sdk = [35])
    fun fromAndroid15_theWholeWindow() {
        val (screen, container) = measure()
        assertNotEquals(DpSize.Zero, container)
        assertEquals(container, screen)
    }
}
