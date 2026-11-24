package com.rembrowser.rembrowse

import android.app.UiModeManager
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.ui.theme.remColorScheme
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * The theme on screen, as the settings and the device's own light or dark setting resolve it,
 * with what has to agree with it: the chrome's colours, the colour scheme web pages are told
 * (prefers-color-scheme) and the colour a screenshot puts where a page couldn't be captured.
 */
class ResolvedTheme(
    val dark: Boolean,
    /** The chrome's colours: the fixed theme for the accent, or the wallpaper's. */
    val colorScheme: ColorScheme
) {
    /**
     * What a pane shows before its page paints. A screenshot fills a page it couldn't capture
     * with it too, so it matches what was on screen.
     */
    val pageBackground: Color get() = colorScheme.surfaceContainerLowest

    /** What web pages are told for prefers-color-scheme. */
    val pageColorScheme: Int
        get() = if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
}

/**
 * [mode] resolved against the device ([systemIsDark]: Android's dark theme is on), with the
 * colours [schemeFor] gives for that (light or dark).
 */
fun resolveTheme(mode: ThemeMode, systemIsDark: Boolean, schemeFor: (dark: Boolean) -> ColorScheme): ResolvedTheme {
    val dark = mode.isDark(systemIsDark)
    return ResolvedTheme(dark, schemeFor(dark))
}

/** [resolveTheme] for the browser's [settings]: their accent, or the wallpaper's colours when chosen. */
fun resolveTheme(context: Context, settings: BrowserSettings, systemIsDark: Boolean): ResolvedTheme =
    resolveTheme(settings.themeMode, systemIsDark) { dark ->
        remColorScheme(context, dark, settings.accent, settings.useSystemColors)
    }

/**
 * The night mode Android 12+ is given for the app (UiModeManager.setApplicationNightMode), so
 * its launch screen, window and the platform's own pieces start in this mode.
 */
val ThemeMode.applicationNightMode: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    }
