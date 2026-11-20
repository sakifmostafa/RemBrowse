package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.PaneDropTarget
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.ui.theme.RemDimens
import kotlin.math.abs
import kotlin.math.sign

/**
 * The content area: one to four panes, arranged by the layout tree, with a draggable divider in
 * every split.
 *
 * Panes are laid out side by side in one flat box and keyed by the tab they show, so whatever
 * happens to the layout (a split, a closed pane, a tab moved to another pane) the web view of a
 * tab that stays on screen is only moved and resized, never destroyed and recreated.
 *
 * While a page is fullscreen its pane covers the whole area. The other panes stay where they
 * are underneath it, composed but out of reach, so leaving fullscreen shows them at once.
 *
 * @param onResizeSplit the divider of the split at `path` is being dragged to `ratio`, which may
 * be outside the allowed range.
 * @param dragState a tab being dragged from the rail; the grid shows where it would land.
 */
@Composable
fun TiledBrowserGrid(
    uiState: BrowserUiState,
    compact: Boolean,
    onFocusPane: (Int) -> Unit,
    onResizeSplit: (path: String, ratio: Float) -> Unit,
    onCommitSplit: () -> Unit,
    onResetSplit: (path: String) -> Unit,
    onReloadPane: (Int) -> Unit,
    contentFocusRequested: Boolean,
    onContentFocusHandled: () -> Unit,
    onFocusWebContent: (tabId: String) -> Unit,
    internalPage: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    dragState: TabDragState? = null
) {
    val isSplit = uiState.isSplit
    val fullscreenTabId = uiState.fullscreenTabId?.takeIf { it in uiState.paneTabIds }
    val fullscreen = fullscreenTabId != null
    // A lone pane on a small window uses every pixel; otherwise panes float on the chrome.
    val framed = !fullscreen && (!compact || isSplit)

    // The resize cursor of the divider being dragged, held for the whole area: the pointer
    // leaves the divider's own bounds whenever it runs ahead of it or past a limit.
    var resizeCursor by remember { mutableStateOf<PointerIcon?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Must stay on this box, which does not move. Compose only delivers a pointer move
            // that changed position for some node on its path, measured in the node's own
            // coordinates; a divider that keeps up with the pointer sees none, and without a
            // still node above it those moves (and their share of the drag) would be dropped.
            .pointerHoverIcon(resizeCursor ?: PointerIcon.Default, overrideDescendants = resizeCursor != null)
            .padding(
                start = if (framed && compact) 6.dp else 0.dp,
                end = if (framed) 6.dp else 0.dp,
                bottom = if (framed) 6.dp else 0.dp
            )
    ) {
        val density = LocalDensity.current
        val gap = RemDimens.PaneGap
        val totalWidth = maxWidth
        val totalHeight = maxHeight
        // In dp, like everything else here
        val geometry = remember(uiState.layout, totalWidth, totalHeight) {
            uiState.layout.geometry(totalWidth.value, totalHeight.value, gap.value)
        }

        var originInRoot by remember { mutableStateOf(Offset.Zero) }
        Box(modifier = Modifier.matchParentSize().onGloballyPositioned { originInRoot = it.positionInRoot() })
        if (dragState != null) {
            val hitTest by rememberUpdatedState { position: Offset, tabId: String ->
                if (fullscreen) {
                    null
                } else {
                    geometry.dropTargetAt(
                        x = (position.x - originInRoot.x) / density.density,
                        y = (position.y - originInRoot.y) / density.density,
                        draggedTabId = tabId,
                        slack = gap.value
                    )
                }
            }
            DisposableEffect(dragState) {
                dragState.hitTest = { position, tabId -> hitTest(position, tabId) }
                onDispose { dragState.hitTest = null }
            }
        }

        geometry.panes.forEachIndexed { paneIndex, placement ->
            key(placement.tabId) {
                val fillsWindow = placement.tabId == fullscreenTabId
                val rect = placement.rect
                BrowserPane(
                    paneIndex = paneIndex,
                    uiState = uiState,
                    framed = framed,
                    isSplit = isSplit && !fullscreen,
                    hidden = fullscreen && !fillsWindow,
                    onFocusPane = onFocusPane,
                    onReloadPane = onReloadPane,
                    contentFocusRequested = contentFocusRequested,
                    onContentFocusHandled = onContentFocusHandled,
                    onFocusWebContent = onFocusWebContent,
                    internalPage = internalPage,
                    paneViewport = paneViewport,
                    modifier = if (fillsWindow) {
                        Modifier.zIndex(1f).size(totalWidth, totalHeight)
                    } else {
                        Modifier
                            .offset(x = rect.left.dp, y = rect.top.dp)
                            .size(width = rect.width.dp, height = rect.height.dp)
                    }
                )
            }
        }

        if (!fullscreen) {
            val ratios = remember(uiState.layout) { uiState.layout.splits().associate { it.path to it.ratio } }
            geometry.dividers.forEach { divider ->
                // A split that turns (side by side to stacked) gets a new divider, not the old
                // one with a gesture still set up for the other axis
                key(divider.path, divider.orientation) {
                    val vertical = divider.orientation == SplitOrientation.ROW
                    val extentPx = with(density) { divider.extent.dp.toPx().coerceAtLeast(1f) }
                    val rect = divider.rect
                    val ratio = ratios[divider.path] ?: 0.5f
                    // Where the split was when the drag began; the pointer's travel is added to it
                    val ratioAtDragStart = remember { floatArrayOf(ratio) }
                    SplitDivider(
                        vertical = vertical,
                        onDragStart = { ratioAtDragStart[0] = ratio },
                        onDragPx = { travel -> onResizeSplit(divider.path, ratioAtDragStart[0] + travel / extentPx) },
                        onDragEnd = onCommitSplit,
                        onReset = { onResetSplit(divider.path) },
                        onHeldChange = { cursor -> resizeCursor = cursor },
                        modifier = if (vertical) {
                            Modifier
                                .offset(x = rect.left.dp - DividerGrabMargin, y = rect.top.dp)
                                .size(width = gap + DividerGrabMargin * 2, height = rect.height.dp)
                        } else {
                            Modifier
                                .offset(x = rect.left.dp, y = rect.top.dp - DividerGrabMargin)
                                .size(width = rect.width.dp, height = gap + DividerGrabMargin * 2)
                        }
                    )
                }
            }
        }

        if (dragState != null) DropHighlight(dragState)
    }
}

/**
 * Where a dragged tab will land: half a pane for a split, the whole pane to take it over.
 * Reads the pointer position in a scope of its own, so a drag does not recompose the panes.
 */
@Composable
private fun DropHighlight(dragState: TabDragState) {
    val target = dragState.target ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(RemDimens.PaneRadius)
    val label = when (target.zone) {
        DropZone.CENTER -> "Show here"
        DropZone.LEFT -> "Split left"
        DropZone.RIGHT -> "Split right"
        DropZone.TOP -> "Split above"
        DropZone.BOTTOM -> "Split below"
    }
    val rect = target.highlight
    Box(
        modifier = Modifier
            .zIndex(2f)
            .offset(x = rect.left.dp, y = rect.top.dp)
            .size(width = rect.width.dp, height = rect.height.dp)
            .clip(shape)
            .background(colors.primary.copy(alpha = 0.28f))
            .border(2.dp, colors.primary, shape)
            .testTag("pane_drop_highlight")
            .semantics { contentDescription = "$label, pane ${target.paneIndex + 1}" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.primary)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun BrowserPane(
    paneIndex: Int,
    uiState: BrowserUiState,
    framed: Boolean,
    isSplit: Boolean,
    /** Covered by a fullscreen page: kept alive, but not there for pointer, keyboard or reader. */
    hidden: Boolean,
    onFocusPane: (Int) -> Unit,
    onReloadPane: (Int) -> Unit,
    contentFocusRequested: Boolean,
    onContentFocusHandled: () -> Unit,
    onFocusWebContent: (tabId: String) -> Unit,
    internalPage: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val tab = uiState.tabForPane(paneIndex) ?: return
    val colors = MaterialTheme.colorScheme
    val isActive = uiState.activePaneIndex == paneIndex
    val shape = if (framed) RoundedCornerShape(RemDimens.PaneRadius) else RoundedCornerShape(0.dp)
    val showFocusRing = isSplit && isActive
    val borderColor by animateColorAsState(
        targetValue = if (showFocusRing) colors.primary else colors.outlineVariant,
        label = "paneBorder"
    )
    val latestOnFocusPane by rememberUpdatedState(onFocusPane)

    // The pane is the keyboard-focus target for "the page". The pane itself takes focus first
    // (so the text field that had it lets go), then a web page's own view is focused inside it.
    val paneFocus = remember { FocusRequester() }
    val showsWebContent = tab.isWebPage && !tab.crashed
    LaunchedEffect(contentFocusRequested, isActive, hidden) {
        if (contentFocusRequested && isActive && !hidden) {
            runCatching { paneFocus.requestFocus() }
            if (showsWebContent) onFocusWebContent(tab.id)
            onContentFocusHandled()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerLowest)
            .focusRequester(paneFocus)
            .focusProperties { canFocus = !hidden }
            .focusable()
            .then(
                if (framed) {
                    Modifier.border(if (showFocusRing) 1.5.dp else 1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .pointerInput(paneIndex, isActive, hidden) {
                if (!isActive && !hidden) {
                    // Observe the press without consuming it, so the page still receives it
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                latestOnFocusPane(paneIndex)
                            }
                        }
                    }
                }
            }
            .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier.testTag("browser_pane_$paneIndex"))
    ) {
        when {
            tab.isNewTabPage -> NewTabPage(modifier = Modifier.fillMaxSize())
            // RemBrowse's own pages (Settings) are native too; no web view is created for them
            tab.isInternalPage -> internalPage(tab, Modifier.fillMaxSize())
            tab.crashed -> CrashedPane(
                onReload = { onReloadPane(paneIndex) },
                modifier = Modifier.fillMaxSize()
            )
            else -> paneViewport(tab, Modifier.fillMaxSize())
        }

        if (tab.isLoading && tab.isWebPage) {
            LinearProgressIndicator(
                progress = { tab.progress.coerceIn(5, 100) / 100f },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp),
                color = colors.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

/** Shown when a tab's content process crashed repeatedly and needs a manual reload. */
@Composable
private fun CrashedPane(onReload: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This tab stopped working",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Reload to pick up where you left off.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onReload) {
            Text("Reload")
        }
    }
}

/**
 * The gap between panes. Drag to resize, double-tap or double-click to reset to an even split.
 * Assistive technology gets the reset as its action, since dragging is not available to it.
 *
 * @param onDragPx how far along the split's axis the pointer is from where the drag began.
 * @param onHeldChange the resize cursor while the divider is pressed, null once it is let go.
 */
@Composable
private fun SplitDivider(
    vertical: Boolean,
    onDragStart: () -> Unit,
    onDragPx: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    onHeldChange: (PointerIcon?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var isHeld by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val highlighted = isHeld || isHovered

    // The gesture below outlives recompositions, so it must always reach the latest lambdas;
    // otherwise a resized window would keep dividing by the old width.
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDragPx)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnReset by rememberUpdatedState(onReset)
    val latestOnHeldChange by rememberUpdatedState(onHeldChange)

    val handleColor by animateColorAsState(
        targetValue = if (highlighted) colors.primary else colors.outlineVariant,
        label = "dividerHandle"
    )
    val resizeIcon = remember(vertical) {
        PointerIcon(
            if (vertical) {
                android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            } else {
                android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            }
        )
    }

    // The grab area reaches over the edges of the pages beside it, and there the page's view
    // would otherwise show its own cursor
    val cursorClaim = LocalChromePointerIconClaim.current
    val claimant = remember { Any() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(cursorClaim) {
        onDispose {
            cursorClaim.set(claimant, false)
            if (isHeld) latestOnHeldChange(null)
        }
    }

    Box(
        modifier = modifier
            .onPlaced { coordinates[0] = it }
            .pointerHoverIcon(resizeIcon)
            .semantics {
                contentDescription = "Pane divider. Drag to resize"
                onClick(label = "Reset to an even split") {
                    latestOnReset()
                    true
                }
            }
            .pointerInput(vertical, cursorClaim) {
                detectDividerGestures(
                    vertical = vertical,
                    // The divider moves under the pointer as it is dragged, so the drag is
                    // measured where nothing moves
                    positionInRoot = { local ->
                        coordinates[0]?.takeIf { it.isAttached }?.localToRoot(local) ?: local
                    },
                    onPointerOver = { hovered, held ->
                        // Told at once, not on the next frame: the cursor is chosen right after
                        // the event that put the pointer here
                        cursorClaim.set(claimant, hovered || held)
                        isHovered = hovered
                        if (held != isHeld) {
                            isHeld = held
                            latestOnHeldChange(if (held) resizeIcon else null)
                        }
                    },
                    onDragStart = { latestOnDragStart() },
                    onDrag = { latestOnDrag(it) },
                    onDragEnd = { latestOnDragEnd() },
                    onDoubleClick = { latestOnReset() }
                )
            }
            .testTag(if (vertical) "split_divider_vertical" else "split_divider_horizontal"),
        contentAlignment = Alignment.Center
    ) {
        DividerHandle(vertical = vertical, color = handleColor, thick = highlighted)
    }
}

/**
 * The divider's whole pointer handling, for finger and mouse alike.
 *
 * A drag reports the pointer's travel from the press, not step by step: the divider then stays
 * under the pointer however the steps arrive, and after waiting at a limit it moves again only
 * when the pointer comes back to it. A press let go without travelling is a click, and two in
 * quick succession are a double click, even if the mouse slipped a pixel during either: a mouse
 * starts dragging on the slightest move, which must not cost it the double click.
 *
 * @param positionInRoot converts a position on the divider to one in the window.
 * @param onPointerOver whether a mouse pointer is over the divider, and whether it is pressed.
 */
private suspend fun PointerInputScope.detectDividerGestures(
    vertical: Boolean,
    positionInRoot: (Offset) -> Offset,
    onPointerOver: (hovered: Boolean, held: Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (travelPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val clickSlop = viewConfiguration.touchSlop
    val doubleClickMillis = viewConfiguration.doubleTapTimeoutMillis
    var hovered = false

    // A pressed pointer still gets Enter and Exit as it crosses the divider's edge
    fun noteHover(event: PointerEvent) {
        when (event.type) {
            PointerEventType.Enter -> hovered = true
            PointerEventType.Exit -> hovered = false
        }
    }

    try {
        awaitPointerEventScope {
            var lastClickAt: Long? = null
            while (true) {
                val event = awaitPointerEvent()
                noteHover(event)
                onPointerOver(hovered, false)
                val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                val isMouse = down.type == PointerType.Mouse
                // The other mouse buttons are not for dragging
                if (isMouse && !event.buttons.isPrimaryPressed) continue
                down.consume()
                onPointerOver(hovered, true)

                val secondClick = lastClickAt?.let { down.uptimeMillis - it <= doubleClickMillis } ?: false
                lastClickAt = null
                val dragSlop = if (isMouse) clickSlop * MouseSlopFraction else clickSlop
                val start = positionInRoot(down.position)
                var dragOrigin = 0f
                var dragging = false
                var strayed = false
                var clickedAt: Long? = null
                try {
                    while (true) {
                        val next = awaitPointerEvent()
                        noteHover(next)
                        val change = next.changes.firstOrNull { it.id == down.id } ?: break
                        val offset = positionInRoot(change.position) - start
                        val travel = if (vertical) offset.x else offset.y
                        if (offset.getDistance() > clickSlop) strayed = true
                        if (!dragging && abs(travel) > dragSlop) {
                            dragging = true
                            // A finger's first few pixels are not counted, so the pane does not jump
                            dragOrigin = sign(travel) * dragSlop
                            onDragStart()
                        }
                        if (dragging) onDrag(travel - dragOrigin)
                        if (!change.pressed) {
                            // Consumed already: the gesture was taken away, not finished
                            if (!change.isConsumed && !strayed) clickedAt = change.uptimeMillis
                            change.consume()
                            break
                        }
                        change.consume()
                    }
                } finally {
                    onPointerOver(hovered, false)
                    if (dragging) onDragEnd()
                }
                if (clickedAt != null && secondClick) onDoubleClick() else lastClickAt = clickedAt
            }
        }
    } finally {
        onPointerOver(false, false)
    }
}

/**
 * A mouse starts dragging almost at once, a finger only after the touch slop. The same ratio
 * Compose's own drag detection uses.
 */
private const val MouseSlopFraction = 0.125f / 18f

/** How far the divider's grab area reaches over the edge of each neighbouring pane. */
private val DividerGrabMargin = 6.dp

/** A grip: an accent pill with two columns of dots, wider than the gap it sits in. */
@Composable
private fun DividerHandle(vertical: Boolean, color: Color, thick: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dot = colors.onPrimaryContainer
    val across = if (thick) 14.dp else 12.dp
    val along = 44.dp
    Canvas(
        modifier = Modifier
            .requiredSize(width = if (vertical) across else along, height = if (vertical) along else across)
            .clip(CircleShape)
            .background(colors.primaryContainer)
            .border(1.dp, color, CircleShape)
    ) {
        val radius = 1.25.dp.toPx()
        val step = 6.dp.toPx()
        val side = 2.5.dp.toPx()
        for (i in -1..1) {
            for (j in listOf(-1f, 1f)) {
                val centre = if (vertical) {
                    Offset(size.width / 2f + j * side, size.height / 2f + i * step)
                } else {
                    Offset(size.width / 2f + i * step, size.height / 2f + j * side)
                }
                drawCircle(color = dot, radius = radius, center = centre)
            }
        }
    }
}
