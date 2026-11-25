package com.rembrowser.rembrowse.engine

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A device is a phone or not by its screen, never by the size of the app's window. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceScreenTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext<Application>()

    /** [app] as it is when its window is [widthDp] by [heightDp]: the configuration follows the window. */
    private fun inWindow(widthDp: Int, heightDp: Int): Context = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply {
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            smallestScreenWidthDp = minOf(widthDp, heightDp)
        }
    )

    private fun isPhone(context: Context) = EngineConfig.isPhone(DeviceScreen.smallestWidthDp(context))

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-xxhdpi")
    fun aPhoneIsAPhone() {
        assertEquals(400, DeviceScreen.smallestWidthDp(app))
        assertTrue(isPhone(app))
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp-land-xxhdpi")
    fun aPhoneTurnedOnItsSideIsStillAPhone() {
        assertEquals(400, DeviceScreen.smallestWidthDp(app))
        assertTrue(isPhone(app))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun aTabletInFullScreenIsNotAPhone() {
        assertEquals(800, DeviceScreen.smallestWidthDp(app))
        assertFalse(isPhone(app))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun aTabletWhoseWindowIsNarrowIsNotAPhone() {
        val splitScreen = inWindow(widthDp = 400, heightDp = 800)
        // What the configuration says, and what classed the tablet as a phone before.
        assertEquals(400, splitScreen.resources.configuration.smallestScreenWidthDp)

        assertEquals(800, DeviceScreen.smallestWidthDp(splitScreen))
        assertFalse(isPhone(splitScreen))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun aLaptopWhoseWindowIsSmallIsNotAPhone() {
        assertFalse(isPhone(inWindow(widthDp = 500, heightDp = 380)))
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-xxhdpi")
    fun aFoldableOnItsOuterScreenIsAPhone() {
        // Measured on the screen in use: see DeviceScreen.smallestWidthDp.
        assertTrue(isPhone(app))
    }

    @Test
    fun theShorterSideIsMeasuredInDp() {
        assertEquals(800, DeviceScreen.smallestWidthDp(widthPx = 2560, heightPx = 1600, densityDpi = 320))
        assertEquals(800, DeviceScreen.smallestWidthDp(widthPx = 1600, heightPx = 2560, densityDpi = 320))
        assertEquals(411, DeviceScreen.smallestWidthDp(widthPx = 2340, heightPx = 1080, densityDpi = 420))
        assertEquals(0, DeviceScreen.smallestWidthDp(widthPx = 1080, heightPx = 2340, densityDpi = 0))
    }
}
