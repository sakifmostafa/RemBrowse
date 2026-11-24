package com.rembrowser.rembrowse

import android.app.Application
import android.os.Build
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The launch screen's and the window's colours, from the resources. Before Android 12 the app
 * can't choose its mode before it starts, so they are dark whatever the device is set to, as in
 * 0.14.0 (MainActivity sets the light window at start); from Android 12 they follow the mode the
 * app is started in (night or not).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WindowThemeTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun colorOf(@StyleRes style: Int, @AttrRes attr: Int): Int {
        val values = context.obtainStyledAttributes(style, intArrayOf(attr))
        try {
            return values.getColor(0, 0)
        } finally {
            values.recycle()
        }
    }

    /** The style's isLightTheme, which the text selection toolbar reads (Android 10+ API). */
    private fun isLight(@StyleRes style: Int): Boolean {
        val values = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.isLightTheme))
        try {
            return values.getBoolean(0, false)
        } finally {
            values.recycle()
        }
    }

    private val launchBackground: Int
        get() = colorOf(R.style.Theme_RemBrowse_Starting, androidx.core.splashscreen.R.attr.windowSplashScreenBackground)

    private fun assertWindow(label: String, @StyleRes style: Int, background: Int, light: Boolean) {
        assertEquals("$label background", background, colorOf(style, android.R.attr.windowBackground))
        // The attribute is public from Android 10; the toolbar reads it from 8 on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assertEquals("$label isLightTheme", light, isLight(style))
    }

    @Test
    @Config(sdk = [26, 28, 30])
    fun beforeAndroid12_onALightDevice_launchScreenAndWindowAreDark_asIn0140() {
        assertEquals(DARK, context.getColor(R.color.rem_chrome))
        assertEquals(DARK, launchBackground)
        assertWindow("Theme.RemBrowse", R.style.Theme_RemBrowse, DARK, light = false)
        assertWindow("The light window set at start", R.style.Theme_RemBrowse_Light, LIGHT, light = true)
    }

    @Test
    @Config(sdk = [29, 30], qualifiers = "night")
    fun beforeAndroid12_onADarkDevice_launchScreenAndWindowAreDark() {
        assertEquals(DARK, launchBackground)
        assertWindow("Theme.RemBrowse", R.style.Theme_RemBrowse, DARK, light = false)
        assertWindow("The light window set at start", R.style.Theme_RemBrowse_Light, LIGHT, light = true)
    }

    @Test
    @Config(sdk = [31, 34], qualifiers = "notnight")
    fun fromAndroid12_startedLight_launchScreenAndWindowAreLight() {
        assertEquals(LIGHT, launchBackground)
        assertWindow("Theme.RemBrowse", R.style.Theme_RemBrowse, LIGHT, light = true)
    }

    @Test
    @Config(sdk = [31, 34], qualifiers = "night")
    fun fromAndroid12_startedDark_launchScreenAndWindowAreDark() {
        assertEquals(DARK, launchBackground)
        assertWindow("Theme.RemBrowse", R.style.Theme_RemBrowse, DARK, light = false)
    }

    @Test
    fun windowThemeBeforeAndroid12_isTheManifestsForDark_andTheLightOneOtherwise() {
        assertEquals(R.style.Theme_RemBrowse, windowThemeBeforeAndroid12(dark = true))
        assertEquals(R.style.Theme_RemBrowse_Light, windowThemeBeforeAndroid12(dark = false))
    }

    private companion object {
        const val DARK = 0xFF14161B.toInt()
        const val LIGHT = 0xFFF4F2F3.toInt()
    }
}
