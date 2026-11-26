package com.rembrowser.rembrowse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.toSize

/**
 * Where the rail's extension buttons are, so that a click on one still reaches it while an
 * extension's popup is open beside the rail.
 *
 * That popup's dialog window covers the whole screen, the rail included (see
 * [ExtensionPopupDialog]), so a press on a button under it lands on the dialog, never on the
 * button. A click on another extension's button only closed the popup that was open, and its
 * own popup took a second click. The buttons note themselves here ([extensionButtonTarget]),
 * and the dialog passes a press it finds on one ([at]) on to that button.
 *
 * Main thread only, as composition and input are.
 */
@Stable
class ExtensionButtonTargets {

    /** One button: whose it is (null for the menu of the other extensions) and its click. */
    class Target internal constructor(val extensionId: String?, private val onClick: () -> Unit) {
        internal var coordinates: LayoutCoordinates? = null

        /** Does what clicking the button itself does. */
        fun click() = onClick()

        internal fun boundsOnScreen(): Rect? {
            val placed = coordinates?.takeIf { it.isAttached } ?: return null
            return Rect(placed.localToScreen(Offset.Zero), placed.size.toSize())
        }
    }

    private val targets = mutableListOf<Target>()

    internal fun add(target: Target) {
        targets.add(target)
    }

    internal fun remove(target: Target) {
        targets.remove(target)
    }

    /** The button at [screenPosition], a point on the screen in pixels, if there is one. */
    fun at(screenPosition: Offset): Target? =
        targets.firstOrNull { it.boundsOnScreen()?.contains(screenPosition) == true }
}

/**
 * Notes this button in [targets], for [extensionId] (null for the menu of the others), while it
 * is on screen. A press on it under an open popup does what [onClick] does.
 */
@Composable
internal fun Modifier.extensionButtonTarget(
    targets: ExtensionButtonTargets?,
    extensionId: String?,
    onClick: () -> Unit
): Modifier {
    if (targets == null) return this
    val latest by rememberUpdatedState(onClick)
    val target = remember(extensionId) { ExtensionButtonTargets.Target(extensionId) { latest() } }
    DisposableEffect(targets, target) {
        targets.add(target)
        onDispose { targets.remove(target) }
    }
    return onPlaced { target.coordinates = it }
}
