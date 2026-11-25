package com.rembrowser.rembrowse

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
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
 * The launch screen's and the window's colours, from the resources: they follow the mode the app
 * is started in (night or not). While the app runs, MainActivity brings the window to the theme
 * on screen with applyWindowTheme, checked here on a plain activity.
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

    /** The style's isLightTheme, which the text selection toolbar reads. */
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
        assertEquals("$label isLightTheme", light, isLight(style))
    }

    @Test
    @Config(sdk = [33, 34], qualifiers = "notnight")
    fun startedLight_launchScreenAndWindowAreLight() {
        assertEquals(LIGHT, context.getColor(R.color.rem_chrome))
        assertEquals(LIGHT, launchBackground)
        assertWindow("Theme.RemBrowse", R.style.Theme_RemBrowse, LIGHT, light = true)
    }

    @Test
    @Config(sdk = [33, 34], qualifiers = "night")
    fun startedDark_launchScreenAndWindowAreDark() {
        assertEquals(DARK, context.getColor(R.color.rem_chrome))
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

    @Test
    @Config(sdk = [33, 34], qualifiers = "notnight")
    fun aThemeChangeWhileRunning_repaintsTheWindowBackground() {
        // MainActivity handles uiMode changes itself, so its window isn't made again
        val activity = startActivity()
        assertEquals("Started light", LIGHT, activity.windowBackground)
        activity.applyWindowTheme(dark = true)
        assertEquals(DARK, activity.windowBackground)
        activity.applyWindowTheme(dark = false)
        assertEquals(LIGHT, activity.windowBackground)
    }

    private companion object {
        const val DARK = 0xFF14161B.toInt()
        const val LIGHT = 0xFFF4F2F3.toInt()
    }
}
