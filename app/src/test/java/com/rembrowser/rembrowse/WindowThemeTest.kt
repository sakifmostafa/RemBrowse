package com.rembrowser.rembrowse

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The launch screen's and the window's colours, from the resources. Before Android 12 the app
 * can't choose its mode before it starts, so they are dark whatever the device is set to, as in
 * 0.14.0 (MainActivity sets the light window at start); from Android 12 they follow the mode the
 * app is started in (night or not). While the app runs, MainActivity brings the window to the
 * theme on screen with applyWindowTheme, checked here on a plain activity.
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

    /** An activity on the app's window theme, as MainActivity is once its launch screen is gone */
    private fun startActivity(): Activity {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(R.style.Theme_RemBrowse)
        return controller.setup().get()
    }

    /** What the window shows where nothing is drawn */
    private val Activity.windowBackground: Int get() = (window.decorView.background as ColorDrawable).color

    /** The activity's own theme's window background, which the pieces made from then on take */
    private val Activity.themeWindowBackground: Int
        get() {
            val values = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            try {
                return values.getColor(0, 0)
            } finally {
                values.recycle()
            }
        }

    @Test
    @Config(sdk = [31, 34], qualifiers = "notnight")
    fun fromAndroid12_aThemeChangeWhileRunning_repaintsTheWindowBackground() {
        // MainActivity handles uiMode changes itself, so its window isn't made again
        val activity = startActivity()
        assertEquals("Started light", LIGHT, activity.windowBackground)
        activity.applyWindowTheme(dark = true)
        assertEquals(DARK, activity.windowBackground)
        activity.applyWindowTheme(dark = false)
        assertEquals(LIGHT, activity.windowBackground)
    }

    @Test
    @Config(sdk = [26, 30])
    fun beforeAndroid12_theThemeAndTheWindowBackgroundFollowTheChoice() {
        val activity = startActivity()
        assertEquals("The manifest's dark theme", DARK, activity.themeWindowBackground)
        activity.applyWindowTheme(dark = false)
        assertEquals(LIGHT, activity.windowBackground)
        assertEquals("The light theme is set on the activity", LIGHT, activity.themeWindowBackground)
        activity.applyWindowTheme(dark = true)
        assertEquals(DARK, activity.windowBackground)
        assertEquals(DARK, activity.themeWindowBackground)
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
