package com.rembrowser.rembrowse.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Says when the browser's own chrome, not the page under the pointer, chooses the mouse cursor.
 *
 * Compose sets its cursor on the one view that hosts the whole screen, but Android asks the
 * innermost view under the pointer first, and a web view answers with whatever cursor its page
 * last asked for. So chrome that lies over the edge of a page (a pane divider's grab area), or
 * that follows the pointer across a page (a divider being dragged), claims the cursor here and
 * the web views stand down for as long.
 *
 * Used on the main thread only.
 */
class ChromePointerIconClaim {
    private val claimants = HashSet<Any>()

    val isClaimed: Boolean get() = claimants.isNotEmpty()

    /** [claimant] takes the cursor ([claimed]) or gives it back. */
    fun set(claimant: Any, claimed: Boolean) {
        if (claimed) claimants.add(claimant) else claimants.remove(claimant)
    }
}

/** The claim the pane web views obey. On its own (previews, tests) nothing listens to it. */
val LocalChromePointerIconClaim = staticCompositionLocalOf { ChromePointerIconClaim() }
