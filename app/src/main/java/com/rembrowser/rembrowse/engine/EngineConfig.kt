package com.rembrowser.rembrowse.engine

import android.util.Log
import org.mozilla.gecko.util.DebugConfig
import java.io.File

/**
 * Engine preferences that GeckoView has no setter for. They are handed over in the startup
 * configuration file GeckoView reads (`GeckoRuntimeSettings.Builder.configFilePath`), whose
 * `prefs` become default preference values before the engine starts.
 *
 * Today that is one preference, the desktop viewport width.
 *
 * A tab that asks for desktop sites gets Gecko's "desktop mode" viewport, whose width is
 * `max(width of the view, browser.viewport.desktopWidth)`; the page is then scaled to fit the
 * view. With Gecko's default of 980 CSS px that is right for a phone, but in a split-view pane
 * narrower than 980 px every page is laid out 980 px wide and shrunk, so its text becomes
 * small. Lowering the preference makes the viewport follow the pane's real width, which is
 * what a desktop browser does when its window is narrow: the page reflows at normal size.
 * Panes wider than 980 px already used their own width and are unaffected.
 *
 * (Checked against `Document::GetViewportInfo` and `Preferences.cpp` in Firefox 138.)
 */
internal object EngineConfig {
    private const val TAG = "EngineConfig"

    const val DESKTOP_WIDTH_PREF = "browser.viewport.desktopWidth"

    /** Gecko's own default: a desktop layout, shrunk to fit whatever is narrower. */
    const val GECKO_DESKTOP_WIDTH = 980

    /**
     * The narrowest a page is laid out on a large screen. Any pane wider than this shows pages
     * at their natural size; only a pane narrower still falls back to shrinking.
     */
    const val MIN_PANE_LAYOUT_WIDTH = 320

    /** Smallest screen width, in dp, from which a device is treated as a tablet or laptop. */
    private const val LARGE_SCREEN_DP = 600

    private const val FILE_NAME = "geckoview-config.yaml"

    /**
     * The desktop viewport width to use on a device whose smallest screen side is
     * [smallestScreenWidthDp]. Phones keep Gecko's default: there, asking for the desktop site
     * means wanting the wide layout fitted to the screen.
     */
    fun desktopViewportWidth(smallestScreenWidthDp: Int): Int =
        if (smallestScreenWidthDp >= LARGE_SCREEN_DP) MIN_PANE_LAYOUT_WIDTH else GECKO_DESKTOP_WIDTH

    /** The configuration file's text for [prefs]. */
    fun yaml(prefs: Map<String, Int>): String = buildString {
        append("# Written by RemBrowse at every start. Edits are overwritten.\n")
        append("prefs:\n")
        prefs.forEach { (name, value) -> append("  ").append(name).append(": ").append(value).append('\n') }
    }

    /**
     * Writes the configuration file into [directory] and returns its path for
     * `configFilePath`, or null when the engine should start without one: nothing differs
     * from Gecko's defaults, or the file could not be written and read back.
     *
     * The file is read back with [readPrefs], the engine's own parser, before its path is
     * returned. If that fails for any reason the engine is started without the file, which
     * only costs the override; it is never given a file it would choke on at startup.
     */
    fun prepare(
        directory: File,
        smallestScreenWidthDp: Int,
        readPrefs: (File) -> Map<String, Any?>? = ::readWithEngineParser
    ): String? {
        val file = File(directory, FILE_NAME)
        val width = desktopViewportWidth(smallestScreenWidthDp)
        if (width == GECKO_DESKTOP_WIDTH) {
            file.delete()
            return null
        }
        return try {
            directory.mkdirs()
            file.writeText(yaml(mapOf(DESKTOP_WIDTH_PREF to width)))
            // Any failure here, including an Error from the parser, is caught below
            val readBack = readPrefs(file)?.get(DESKTOP_WIDTH_PREF)
            if (readBack == width) {
                file.absolutePath
            } else {
                Log.w(TAG, "Engine configuration did not read back as written; starting without it")
                file.delete()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't prepare the engine configuration; starting without it", t)
            runCatching { file.delete() }
            null
        }
    }

    /** Parses [file] exactly as GeckoView will, and returns the preferences it found. */
    fun readWithEngineParser(file: File): Map<String, Any?>? =
        DebugConfig.fromFile(file).mergeIntoPrefs(null)
}
