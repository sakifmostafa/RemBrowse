package com.rembrowser.rembrowse

import android.app.UiModeManager
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.ui.theme.remColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * How MainActivity works out the theme on screen ([resolveTheme]): light or dark from the setting
 * and the device, and what follows from it for pages, screenshots and Android's night mode.
 */
class ThemeResolutionTest {

    private fun resolve(mode: ThemeMode, systemIsDark: Boolean, accent: AccentColor = AccentColor.MAROON) =
        resolveTheme(mode, systemIsDark) { dark -> remColorScheme(dark, accent) }

    @Test
    fun lightOrDark_isTheSettingsChoice_orTheDevicesForSystemDefault() {
        for (systemIsDark in listOf(false, true)) {
            assertEquals("System default, device dark=$systemIsDark", systemIsDark, resolve(ThemeMode.SYSTEM, systemIsDark).dark)
            assertEquals("Light, device dark=$systemIsDark", false, resolve(ThemeMode.LIGHT, systemIsDark).dark)
            assertEquals("Dark, device dark=$systemIsDark", true, resolve(ThemeMode.DARK, systemIsDark).dark)
        }
    }

    @Test
    fun theDeviceCountsAsDark_whenItsDarkThemeIsOn_andAlwaysOnAndroid8And9() {
        // Android 8 and 9 have no dark theme, so their night mode always reads off
        for (sdk in listOf(26, 27, 28)) {
            assertEquals("SDK $sdk", true, deviceThemeIsDark(nightModeOn = false, sdkInt = sdk))
            assertEquals("SDK $sdk", true, deviceThemeIsDark(nightModeOn = true, sdkInt = sdk))
        }
        for (sdk in listOf(29, 30, 31, 34, 36)) {
            assertEquals("SDK $sdk, light", false, deviceThemeIsDark(nightModeOn = false, sdkInt = sdk))
            assertEquals("SDK $sdk, dark", true, deviceThemeIsDark(nightModeOn = true, sdkInt = sdk))
        }
    }

    @Test
    fun onAndroid8And9_systemDefaultIsDark_asIn0140_andLightStaysLight() {
        for (sdk in listOf(26, 28)) {
            val device = deviceThemeIsDark(nightModeOn = false, sdkInt = sdk)
            assertEquals("System default, SDK $sdk", true, resolve(ThemeMode.SYSTEM, device).dark)
            assertEquals(GeckoRuntimeSettings.COLOR_SCHEME_DARK, resolve(ThemeMode.SYSTEM, device).pageColorScheme)
            assertEquals("Light, SDK $sdk", false, resolve(ThemeMode.LIGHT, device).dark)
            assertEquals("Dark, SDK $sdk", true, resolve(ThemeMode.DARK, device).dark)
        }
        // From Android 10, System follows the device
        assertEquals(false, resolve(ThemeMode.SYSTEM, deviceThemeIsDark(nightModeOn = false, sdkInt = 29)).dark)
    }

    @Test
    fun colours_areThoseOfTheResolvedSide() {
        for (mode in ThemeMode.entries) {
            for (systemIsDark in listOf(false, true)) {
                val theme = resolve(mode, systemIsDark, AccentColor.TEAL)
                // ColorScheme has no equals of its own; its text lists every role
                assertEquals(
                    "$mode, device dark=$systemIsDark",
                    remColorScheme(theme.dark, AccentColor.TEAL).toString(),
                    theme.colorScheme.toString()
                )
            }
        }
    }

    @Test
    fun screenshotBackground_isThePageColourOfTheThemeOnScreen() {
        for (mode in ThemeMode.entries) {
            for (systemIsDark in listOf(false, true)) {
                val theme = resolve(mode, systemIsDark)
                assertEquals(
                    "$mode, device dark=$systemIsDark",
                    remColorScheme(mode.isDark(systemIsDark), AccentColor.MAROON).surfaceContainerLowest,
                    theme.pageBackground
                )
            }
        }
        // System default on a light device: the light page colour, not the dark one
        assertNotEquals(
            remColorScheme(darkTheme = true, accent = AccentColor.MAROON).surfaceContainerLowest,
            resolve(ThemeMode.SYSTEM, systemIsDark = false).pageBackground
        )
    }

    @Test
    fun pages_areToldTheResolvedColourScheme() {
        assertEquals(GeckoRuntimeSettings.COLOR_SCHEME_DARK, resolve(ThemeMode.DARK, systemIsDark = false).pageColorScheme)
        assertEquals(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT, resolve(ThemeMode.LIGHT, systemIsDark = true).pageColorScheme)
        assertEquals(GeckoRuntimeSettings.COLOR_SCHEME_DARK, resolve(ThemeMode.SYSTEM, systemIsDark = true).pageColorScheme)
        assertEquals(GeckoRuntimeSettings.COLOR_SCHEME_LIGHT, resolve(ThemeMode.SYSTEM, systemIsDark = false).pageColorScheme)
    }

    @Test
    fun android12NightMode_followsTheSetting() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.applicationNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.applicationNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.applicationNightMode)
    }
}
