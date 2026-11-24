package com.rembrowser.rembrowse

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.StyleRes
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

/**
 * The activity's window theme before Android 12, where the window can't follow the app's mode by
 * itself (see values/themes.xml): dark is the manifest's own, light is set over it at start.
 */
@StyleRes
fun windowThemeBeforeAndroid12(dark: Boolean): Int =
    if (dark) R.style.Theme_RemBrowse else R.style.Theme_RemBrowse_Light

/**
 * Brings the activity's window to the theme on screen, light or dark ([dark]), at start and on
 * every change while it runs.
 *
 * The window's background, which shows where nothing is drawn yet (a desktop window being made
 * larger, the moment before the first frame), is set on every Android version. MainActivity
 * handles uiMode changes itself, so on Android 12+ its window would otherwise keep the colour it
 * was made with until the next start.
 *
 * Android 8-11 only: the activity's theme. There Android can't be told the app's mode before it
 * starts (Android 12+ is, by UiModeManager.setApplicationNightMode, and picks the theme from the
 * resources), so the launch screen and the manifest's window theme are dark, as in 0.14.0 and
 * earlier. For the light theme the activity's theme is set here: at start, before the window is
 * made, so the window and the pieces Android draws over it (the text selection toolbar over a
 * page) are light; and again on every change, for the pieces made from then on. The launch
 * screen itself stays dark. The theme is applied over the current one, and does nothing when it
 * is already the one set.
 */
fun Activity.applyWindowTheme(dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) setTheme(windowThemeBeforeAndroid12(dark))
    window.setBackgroundDrawableResource(if (dark) R.color.rem_chrome_dark else R.color.rem_chrome_light)
}
