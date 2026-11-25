package com.rembrowser.rembrowse.engine

import android.util.Log
import org.mozilla.gecko.util.DebugConfig
import java.io.File

/**
 * Engine preferences that GeckoView has no setter for. They are handed over in the startup
 * configuration file GeckoView reads (`GeckoRuntimeSettings.Builder.configFilePath`), whose
 * `prefs` become default preference values before the engine starts.
 *
 * Five preferences:
 *
 * **Translations off** ([TRANSLATIONS_PREF]), on every device. RemBrowse offers no page
 * translation, but GeckoView turns the machinery on, and it runs a language-detection worker
 * in every web content process: measured on a Googlebook with `about:memory`, about 17 MB per
 * process (`resource://gre/modules/translations/cld-worker.js`), for nothing.
 *
 * **Back goes to the previous page** ([REQUIRE_INTERACTION_PREF] off), on every device. Gecko's
 * Back skips entries it didn't see the user interact with, and on single-page sites it misses
 * some the user did use (Back from a post on X left the site instead of showing the feed).
 *
 * **Background tabs keep running** ([SUSPEND_INACTIVE_PREF] off), on every device. Gecko on
 * Android (only there; desktop Firefox has it off) suspends a background tab's page outright:
 * its timers stop, and its own workers' replies are held until it is shown again
 * (`nsGlobalWindowInner::Suspend`). Sites that share one connection between their tabs depend
 * on the background tab answering: Telegram Web A keeps its connection, in a worker, in the
 * first tab opened, and the other tabs ask that tab for everything over a `BroadcastChannel`.
 * With that tab in the background, the tab on screen waited forever (a chat that never
 * loaded). Measured on an emulator with a page that does the same: suspended, none of 54
 * requests from the tab on screen were answered; off, all 75 were, within 3 ms. Off, a
 * background page's own timers still fire at most once a second (`TimeoutManager`), but its
 * workers and network are not slowed. Playing media was never suspended. The process
 * priority of a background tab, and so when Android may kill it, is unchanged. With RemBrowse
 * minimised, its pages keep running the same way while it is the last app used; once other apps
 * are used Android caches it and freezes all its processes (seen on the emulator: Android 16,
 * `use_freezer=true`).
 *
 * **Desktop viewport width** ([DESKTOP_WIDTH_PREF]), on large screens only. A tab that asks
 * for desktop sites gets Gecko's "desktop mode" viewport, whose width is
 * `max(width of the view, browser.viewport.desktopWidth)`; the page is then scaled to fit the
 * view. With Gecko's default of 980 CSS px that is right for a phone, but in a split-view pane
 * narrower than 980 px every page is laid out 980 px wide and shrunk, so its text becomes
 * small. Lowering the preference makes the viewport follow the pane's real width, which is
 * what a desktop browser does when its window is narrow: the page reflows at normal size.
 * Panes wider than 980 px already used their own width and are unaffected.
 *
 * **A fine pointer that can hover** ([POINTER_PREF]), when a mouse or touchpad is connected
 * (see [PointerDevices]: a device that is also a touchscreen or a stylus doesn't count).
 * Gecko on Android reports the primary pointer as coarse whenever a touchscreen exists, even
 * with a touchpad (`nsLookAndFeel.cpp`: any coarse device makes the primary pointer coarse), so a
 * Googlebook's pages saw `(pointer: coarse)` and `(hover: none)` where Chrome on the same device
 * reports fine and hover. Sites take that as a phone or tablet, and some change behaviour (a
 * chat box whose Enter starts a new line instead of sending). Only these media queries change:
 * touch events, `ontouchstart` and `maxTouchPoints` stay as they are, so the touchscreen works.
 * Read at startup, so a mouse connected later counts from the next start.
 *
 * (Checked against `Document::GetViewportInfo`, `nsXPLookAndFeel.cpp`, `nsLookAndFeel.cpp` and
 * `Preferences.cpp` in Firefox 161.)
 */
internal object EngineConfig {
    private const val TAG = "EngineConfig"

    const val DESKTOP_WIDTH_PREF = "browser.viewport.desktopWidth"

    /** Overrides the primary pointer's capabilities for the `pointer` and `hover` media queries. */
    const val POINTER_PREF = "ui.primaryPointerCapabilities"

    /** Gecko's pointer capability bits (LookAndFeel.h): fine, and able to hover. */
    const val FINE_POINTER_WITH_HOVER = 2 or 4

    /** Gecko's page translation, which RemBrowse has no use for (see above). */
    const val TRANSLATIONS_PREF = "browser.translations.enable"

    /**
     * Gecko's Back skips history entries it hasn't seen the user interact with (on by default).
     * It notes one interaction per entry from mouse, touch and key events, and on single-page
     * sites that change address without reloading (X) it misses entries the user did use: Back
     * from a post skipped X's home feed and left the site. Off, Back and the Back arrow go to
     * the previous entry, whatever it is; a site that piles up entries can take an extra Back.
     */
    const val REQUIRE_INTERACTION_PREF = "browser.navigation.requireUserInteraction"

    /** Gecko on Android suspends every background tab outright (on by default; see above). */
    const val SUSPEND_INACTIVE_PREF = "dom.suspend_inactive.enabled"

    /** Gecko's own default: a desktop layout, shrunk to fit whatever is narrower. */
    const val GECKO_DESKTOP_WIDTH = 980

    /**
     * The narrowest a page is laid out on a large screen. Any pane wider than this shows pages
     * at their natural size; only a pane narrower still falls back to shrinking.
     */
    const val MIN_PANE_LAYOUT_WIDTH = 320

    /** Smallest screen width, in dp, from which a device is treated as a tablet or laptop. */
    const val LARGE_SCREEN_DP = 600

    private const val FILE_NAME = "geckoview-config.yaml"

    /**
     * The desktop viewport width to use on a device whose smallest screen side is
     * [smallestScreenWidthDp]. Phones keep Gecko's default: there, asking for the desktop site
     * means wanting the wide layout fitted to the screen.
     */
    fun desktopViewportWidth(smallestScreenWidthDp: Int): Int =
        if (isPhone(smallestScreenWidthDp)) GECKO_DESKTOP_WIDTH else MIN_PANE_LAYOUT_WIDTH

    /** Whether a device whose smallest screen side is [smallestScreenWidthDp] is a phone (below [LARGE_SCREEN_DP]). */
    fun isPhone(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp < LARGE_SCREEN_DP

    /**
     * What RemBrowse sets on a device whose smallest screen side is [smallestScreenWidthDp], with
     * or without a mouse or touchpad connected ([hasMouseOrTouchpad]).
     */
    fun prefsFor(smallestScreenWidthDp: Int, hasMouseOrTouchpad: Boolean = false): Map<String, Any> = buildMap {
        put(TRANSLATIONS_PREF, false)
        put(REQUIRE_INTERACTION_PREF, false)
        put(SUSPEND_INACTIVE_PREF, false)
        val width = desktopViewportWidth(smallestScreenWidthDp)
        if (width != GECKO_DESKTOP_WIDTH) put(DESKTOP_WIDTH_PREF, width)
        if (hasMouseOrTouchpad) put(POINTER_PREF, FINE_POINTER_WITH_HOVER)
    }

    /** The configuration file's text for [prefs], whose values are numbers or true/false. */
    fun yaml(prefs: Map<String, Any>): String = buildString {
        append("# Written by RemBrowse at every start. Edits are overwritten.\n")
        append("prefs:\n")
        prefs.forEach { (name, value) ->
            require(value is Int || value is Boolean) { "$name: only numbers and true/false are written" }
            append("  ").append(name).append(": ").append(value).append('\n')
        }
    }

    /**
     * Writes the configuration file into [directory] and returns its path for
     * `configFilePath`, or null when the engine should start without one because the file
     * could not be written and read back.
     *
     * The file is read back with [readPrefs], the engine's own parser, before its path is
     * returned. If that fails for any reason the engine is started without the file, which
     * only costs the override; it is never given a file it would choke on at startup.
     */
    fun prepare(
        directory: File,
        smallestScreenWidthDp: Int,
        hasMouseOrTouchpad: Boolean = false,
        readPrefs: (File) -> Map<String, Any?>? = ::readWithEngineParser
    ): String? {
        val file = File(directory, FILE_NAME)
        val prefs = prefsFor(smallestScreenWidthDp, hasMouseOrTouchpad)
        return try {
            directory.mkdirs()
            file.writeText(yaml(prefs))
            // Any failure here, including an Error from the parser, is caught below
            val readBack = readPrefs(file)
            if (readBack != null && prefs.all { (name, value) -> readBack[name] == value }) {
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
