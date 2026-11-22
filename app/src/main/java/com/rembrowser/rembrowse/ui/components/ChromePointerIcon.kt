package com.rembrowser.rembrowse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Says when the browser's own chrome, not the page under the pointer, chooses the mouse cursor.
 *
 * Compose sets its cursor on the one view that hosts the whole screen, but Android asks the
 * innermost view under the pointer first, and a web view answers with whatever cursor its page
 * last asked for, and a terminal with its text cursor. So chrome that lies over the edge of a
 * page (a pane divider's grab area), that follows the pointer across a page (a divider being
 * dragged), or that is drawn on top of one (a card or banner over a terminal), claims the cursor
 * here and the views underneath stand down for as long.
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

/** The claim the pane web views and terminals obey. On its own (previews, tests) nothing listens to it. */
val LocalChromePointerIconClaim = staticCompositionLocalOf { ChromePointerIconClaim() }

/**
 * Claims the cursor for [claimant] while a hovering pointer is over this element: for chrome drawn
 * on top of a page or terminal. Seen in the first pass, so the claim is in place before the cursor
 * is chosen for the same event. Pair it with [rememberPointerIconClaimant], which gives the cursor
 * back when the element leaves the screen with the pointer still on it.
 *
 * Only a pointer arriving with nothing pressed claims it, and only leaving gives it back. A
 * pressed pointer that is dragged off still sends this element its moves, and a finger sends no
 * Exit at all, so counting moves or presses would leave the claim on with the pointer elsewhere,
 * and every page and terminal without its own cursor. The pointer type isn't checked: Android
 * reports a touchpad's pointer as a finger.
 */
fun Modifier.claimsPointerIcon(claim: ChromePointerIconClaim, claimant: Any): Modifier =
    pointerInput(claim, claimant) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> if (event.changes.none { it.pressed }) claim.set(claimant, true)
                    PointerEventType.Exit -> claim.set(claimant, false)
                    else -> Unit
                }
            }
        }
    }

/** A claimant for [claimsPointerIcon] that lets go of the cursor when it leaves the composition. */
@Composable
fun rememberPointerIconClaimant(claim: ChromePointerIconClaim): Any {
    val claimant = remember { Any() }
    DisposableEffect(claim, claimant) { onDispose { claim.set(claimant, false) } }
    return claimant
}
