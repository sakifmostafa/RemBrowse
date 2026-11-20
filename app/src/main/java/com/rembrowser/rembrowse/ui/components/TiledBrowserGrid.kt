package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
 * @param dragState a tab being dragged from the rail; the grid shows where it would land.
 */
@Composable
fun TiledBrowserGrid(
    uiState: BrowserUiState,
    compact: Boolean,
    onFocusPane: (Int) -> Unit,
    onResizeSplit: (path: String, deltaFraction: Float) -> Unit,
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
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
            geometry.dividers.forEach { divider ->
                key(divider.path) {
                    val vertical = divider.orientation == SplitOrientation.ROW
                    val extentPx = with(density) { divider.extent.dp.toPx().coerceAtLeast(1f) }
                    val rect = divider.rect
                    SplitDivider(
                        vertical = vertical,
                        onDragDeltaPx = { delta -> onResizeSplit(divider.path, delta / extentPx) },
                        onDragEnd = onCommitSplit,
                        onReset = { onResetSplit(divider.path) },
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
 * The gap between panes. Drag to resize, double-tap to reset to an even split. Assistive
 * technology gets the reset as its action, since dragging is not available to it.
 */
@Composable
private fun SplitDivider(
    vertical: Boolean,
    onDragDeltaPx: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var isDragging by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isDragging || isHovered

    // pointerInput(Unit) below never restarts, so it must always reach the latest lambdas;
    // otherwise a resized window would keep dividing by the old width.
    val latestOnDrag by rememberUpdatedState(onDragDeltaPx)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnReset by rememberUpdatedState(onReset)

    val handleColor by animateColorAsState(
        targetValue = if (highlighted) colors.primary else colors.outlineVariant,
        label = "dividerHandle"
    )
    val resizeIcon = PointerIcon(
        if (vertical) {
            android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        } else {
            android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        }
    )

    Box(
        modifier = modifier
            .hoverable(interactionSource)
            .pointerHoverIcon(resizeIcon)
            .semantics {
                contentDescription = "Pane divider. Drag to resize"
                onClick(label = "Reset to an even split") {
                    latestOnReset()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { latestOnReset() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        latestOnDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        latestOnDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        latestOnDrag(if (vertical) dragAmount.x else dragAmount.y)
                    }
                )
            }
            .testTag(if (vertical) "split_divider_vertical" else "split_divider_horizontal"),
        contentAlignment = Alignment.Center
    ) {
        DividerHandle(vertical = vertical, color = handleColor, thick = highlighted)
    }
}

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
