package com.rembrowser.rembrowse.engine

import android.content.Context
import android.view.WindowManager

/**
 * The size of the screen the app is on, as opposed to the size of its window. What kind of device
 * this is ([EngineConfig.isPhone]) is asked of the screen: a tablet in split screen, or a laptop
 * whose window has been made narrow, is still a tablet or a laptop.
 */
internal object DeviceScreen {

    /**
     * The shorter side of the whole screen, in dp, whichever way the device is turned and however
     * small the app's window is. Configuration.smallestScreenWidthDp is not that: in split screen
     * or a freeform window it is the window's shorter side, even on the application's resources.
     *
     * A foldable is measured on the screen it is using right now, so it is a phone while folded
     * and may be a tablet once opened. Android has no public way to ask a folded device how large
     * its inner screen is, and the hinge sensor can't stand in for one: flip phones have it too,
     * and so do some laptops. What is decided once from this (mobile sites by default) suits a
     * foldable either way: its outer screen is a phone's, and other browsers ask for mobile
     * sites on the inner one as well.
     *
     * Falls back to the configuration's value if the system can't be asked.
     */
    fun smallestWidthDp(context: Context): Int = runCatching {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        smallestWidthDp(bounds.width(), bounds.height(), context.resources.configuration.densityDpi)
    }.getOrNull()?.takeIf { it > 0 } ?: context.resources.configuration.smallestScreenWidthDp

    /** The shorter side of a screen of [widthPx] by [heightPx] pixels at [densityDpi], in dp; 0 when unknown. */
    fun smallestWidthDp(widthPx: Int, heightPx: Int, densityDpi: Int): Int =
        if (densityDpi <= 0) 0 else minOf(widthPx, heightPx) * DENSITY_DEFAULT / densityDpi

    /** The density at which one dp is one pixel (DisplayMetrics.DENSITY_DEFAULT). */
    private const val DENSITY_DEFAULT = 160
}
