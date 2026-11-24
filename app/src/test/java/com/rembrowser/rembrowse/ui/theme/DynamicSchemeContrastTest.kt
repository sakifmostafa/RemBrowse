package com.rembrowser.rembrowse.ui.theme

import android.app.Application
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.resolveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every check of [ThemeContrastTest], over the schemes Android builds from the wallpaper (here
 * the platform's default palette), as RemBrowse re-maps them for "Use system colors". Android 12
 * and 12L build them from the accent palettes (system_accent1_*), Android 14 from its own colour
 * roles, so both are checked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 34], application = Application::class)
class DynamicSchemeContrastTest : ThemeContrastTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    override fun schemes(): List<Pair<String, ColorScheme>> = listOf(
        "wallpaper light" to remDynamicScheme(dynamicLightColorScheme(context), dark = false),
        "wallpaper dark" to remDynamicScheme(dynamicDarkColorScheme(context), dark = true)
    )

    @Test
    fun systemColors_replaceTheAccentOnlyWhenOn() {
        val wallpaperDark = remDynamicScheme(dynamicDarkColorScheme(context), dark = true)
        // ColorScheme has no equals of its own; its text lists every role
        assertEquals(
            wallpaperDark.toString(),
            remColorScheme(context, darkTheme = true, accent = AccentColor.TEAL, systemColors = true).toString()
        )
        assertEquals(
            remColorScheme(darkTheme = false, accent = AccentColor.TEAL).toString(),
            remColorScheme(context, darkTheme = false, accent = AccentColor.TEAL, systemColors = false).toString()
        )
        assertNotEquals(
            "The wallpaper's colours, not maroon",
            remColorScheme(darkTheme = true, accent = AccentColor.MAROON).primary,
            wallpaperDark.primary
        )
    }

    @Test
    fun terminalCursor_takesTheWallpaperAccentForDarkSurfaces() {
        assertEquals(dynamicDarkColorScheme(context).primary, terminalCursorColor(context, AccentColor.MAROON, systemColors = true))
        assertEquals(AccentColor.MAROON.tones().darkPrimary, terminalCursorColor(context, AccentColor.MAROON, systemColors = false))
    }

    @Test
    fun resolvedTheme_withSystemColors_takesTheWallpapersColoursForPagesAndScreenshots() {
        val settings = BrowserSettings(themeMode = ThemeMode.SYSTEM, accent = AccentColor.TEAL, useSystemColors = true)
        for (systemIsDark in listOf(false, true)) {
            val theme = resolveTheme(context, settings, systemIsDark)
            val wallpaper = remDynamicScheme(
                if (systemIsDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
                dark = systemIsDark
            )
            assertEquals(systemIsDark, theme.dark)
            assertEquals("device dark=$systemIsDark", wallpaper.toString(), theme.colorScheme.toString())
            assertEquals("device dark=$systemIsDark", wallpaper.surfaceContainerLowest, theme.pageBackground)
        }
        // Turned off, the chosen accent's own theme
        val off = resolveTheme(context, settings.copy(useSystemColors = false), systemIsDark = true)
        assertEquals(remColorScheme(darkTheme = true, accent = AccentColor.TEAL).surfaceContainerLowest, off.pageBackground)
    }
}
