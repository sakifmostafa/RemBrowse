package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.PaneDropTarget
import kotlin.math.roundToInt

/**
 * A tab being dragged from the rail onto the panes. The rail's tiles feed it the pointer, the
 * pane grid tells it what lies under the pointer and draws the landing spot.
 */
@Stable
class TabDragState {
    /** The tab in the air, or null when nothing is being dragged. */
    var draggedTabId by mutableStateOf<String?>(null)
        private set

    /** Where the pointer is, in the coordinates of the Compose root. */
    var position by mutableStateOf(Offset.Zero)
        private set

    /** Set by the pane grid: what dropping a given tab at a root position would do. */
    internal var hitTest by mutableStateOf<((position: Offset, tabId: String) -> PaneDropTarget?)?>(null)

    /** Where the dragged tab would land if it were let go now. */
    val target: PaneDropTarget?
        get() {
            val tabId = draggedTabId ?: return null
            return hitTest?.invoke(position, tabId)
        }

    fun start(tabId: String, position: Offset) {
        draggedTabId = tabId
        this.position = position
    }

    fun moveTo(position: Offset) {
        this.position = position
    }

    /** Ends the drag and returns where the tab was dropped, if that was on a pane. */
    fun finish(): PaneDropTarget? {
        val dropped = target
        draggedTabId = null
        return dropped
    }

    fun cancel() {
        draggedTabId = null
    }
}

/**
 * Makes a rail tile the start of a tab drag, without taking its click or its menu away.
 *
 * With a mouse, pressing and moving starts the drag. With a finger the tile sits in a list that
 * scrolls, so a drag only starts once the finger has rested for a long press: the tile's menu
 * opens as always, and moving on from there picks the tab up ([onDragStart] closes the menu).
 * Until a drag starts nothing is consumed, so taps, long presses and scrolling work as before.
 */
fun Modifier.tabDragSource(
    tabId: String,
    dragState: TabDragState,
    onDragStart: () -> Unit,
    onDrop: (PaneDropTarget) -> Unit
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrop by rememberUpdatedState(onDrop)

    onGloballyPositioned { coordinates = it }
        .pointerInput(tabId, dragState) {
            awaitEachGesture {
                // The Initial pass comes before the tile's own click handling, so a drag can
                // take the gesture over by consuming it.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val isMouse = down.type == PointerType.Mouse
                // Only the primary button drags; a touchpad's right click comes as a finger
                val buttons = currentEvent.buttons
                if (!buttons.isPrimaryPressed && (isMouse || buttons.hasOtherThanPrimary())) return@awaitEachGesture

                fun toRoot(local: Offset): Offset =
                    coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local

                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                change.consume()
                                dragging = false
                                dragState.moveTo(toRoot(change.position))
                                dragState.finish()?.let { latestOnDrop(it) }
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            val heldMillis = change.uptimeMillis - down.uptimeMillis
                            // A finger that moves straight away is scrolling the rail
                            if (!isMouse && heldMillis < viewConfiguration.longPressTimeoutMillis) break
                            dragging = true
                            dragState.start(tabId, toRoot(change.position))
                            latestOnDragStart()
                        }
                        if (dragging) {
                            dragState.moveTo(toRoot(change.position))
                            change.consume()
                        }
                    }
                } finally {
                    // The gesture was taken away (or the tile left the screen) mid-drag
                    if (dragging && dragState.draggedTabId == tabId) dragState.cancel()
                }
            }
        }
}

/** A small label that follows the pointer while a tab is dragged, naming the tab in the air. */
@Composable
fun BoxScope.TabDragGhost(dragState: TabDragState, tabs: List<BrowserTab>) {
    val tabId = dragState.draggedTabId ?: return
    val tab = tabs.firstOrNull { it.id == tabId } ?: return
    val colors = MaterialTheme.colorScheme
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .matchParentSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Decoration only: the rail tile already says what is being dragged
            .clearAndSetSemantics { }
    ) {
        Row(
            modifier = Modifier
                .offset {
                    val nudge = 14.dp.roundToPx()
                    IntOffset(
                        (dragState.position.x - origin.x).roundToInt() + nudge,
                        (dragState.position.y - origin.y).roundToInt() + nudge
                    )
                }
                .shadow(6.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.surface)
                .border(1.dp, colors.primary, MaterialTheme.shapes.medium)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("tab_drag_ghost"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SiteIcon(url = tab.url, title = tab.displayTitle, isNewTab = tab.isNewTabPage)
            Text(
                text = tab.displayTitle,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
    }
}
