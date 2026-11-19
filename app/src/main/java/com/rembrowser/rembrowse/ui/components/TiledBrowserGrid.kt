package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.ui.theme.RemDimens

/**
 * The content area: one to four panes with draggable dividers between them.
 *
 * Every pane keeps the same position in the composition whatever the layout, so switching
 * between layouts resizes the existing web views instead of destroying and recreating them.
 */
@Composable
fun TiledBrowserGrid(
    uiState: BrowserUiState,
    compact: Boolean,
    onFocusPane: (Int) -> Unit,
    onUpdateHorizontalSplit: (Float) -> Unit,
    onUpdateVerticalSplit: (Float) -> Unit,
    onCommitSplit: () -> Unit,
    onResetSplitRatios: () -> Unit,
    onReloadPane: (Int) -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = uiState.layoutMode
    val isSplit = mode.paneCount > 1
    // A lone pane on a small window uses every pixel; otherwise panes float on the chrome.
    val framed = !compact || isSplit

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
        val columns = mode.columns
        val rows = mode.rows
        val totalWidth = maxWidth
        val totalHeight = maxHeight
        val firstColumnWidth = if (columns == 2) (totalWidth - gap) * uiState.splitRatios.horizontal else totalWidth
        val firstRowHeight = if (rows == 2) (totalHeight - gap) * uiState.splitRatios.vertical else totalHeight
        val draggableWidthPx = with(density) { (totalWidth - gap).toPx().coerceAtLeast(1f) }
        val draggableHeightPx = with(density) { (totalHeight - gap).toPx().coerceAtLeast(1f) }

        for (paneIndex in 0 until mode.paneCount) {
            key(paneIndex) {
                val column = paneIndex % columns
                val row = paneIndex / columns
                val x = if (column == 0) 0.dp else firstColumnWidth + gap
                val y = if (row == 0) 0.dp else firstRowHeight + gap
                val width = when {
                    columns == 1 -> totalWidth
                    column == 0 -> firstColumnWidth
                    else -> totalWidth - firstColumnWidth - gap
                }
                val height = when {
                    rows == 1 -> totalHeight
                    row == 0 -> firstRowHeight
                    else -> totalHeight - firstRowHeight - gap
                }
                BrowserPane(
                    paneIndex = paneIndex,
                    uiState = uiState,
                    framed = framed,
                    isSplit = isSplit,
                    onFocusPane = onFocusPane,
                    onReloadPane = onReloadPane,
                    paneViewport = paneViewport,
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .size(width = width.coerceAtLeast(0.dp), height = height.coerceAtLeast(0.dp))
                )
            }
        }

        if (columns == 2) {
            SplitDivider(
                vertical = true,
                onDragDeltaPx = { delta -> onUpdateHorizontalSplit(delta / draggableWidthPx) },
                onDragEnd = onCommitSplit,
                onReset = onResetSplitRatios,
                modifier = Modifier
                    .offset(x = firstColumnWidth)
                    .size(width = gap, height = totalHeight)
            )
        }
        if (rows == 2) {
            SplitDivider(
                vertical = false,
                onDragDeltaPx = { delta -> onUpdateVerticalSplit(delta / draggableHeightPx) },
                onDragEnd = onCommitSplit,
                onReset = onResetSplitRatios,
                modifier = Modifier
                    .offset(y = firstRowHeight)
                    .size(width = totalWidth, height = gap)
            )
        }
    }
}

@Composable
private fun BrowserPane(
    paneIndex: Int,
    uiState: BrowserUiState,
    framed: Boolean,
    isSplit: Boolean,
    onFocusPane: (Int) -> Unit,
    onReloadPane: (Int) -> Unit,
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

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerLowest)
            .then(
                if (framed) {
                    Modifier.border(if (showFocusRing) 1.5.dp else 1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .pointerInput(paneIndex, isActive) {
                if (!isActive) {
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
            .testTag("browser_pane_$paneIndex")
    ) {
        when {
            tab.isNewTabPage -> NewTabPage(modifier = Modifier.fillMaxSize())
            tab.crashed -> CrashedPane(
                onReload = { onReloadPane(paneIndex) },
                modifier = Modifier.fillMaxSize()
            )
            else -> paneViewport(tab, Modifier.fillMaxSize())
        }

        if (tab.isLoading && !tab.isNewTabPage) {
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
        targetValue = if (highlighted) colors.primary else colors.outline,
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

@Composable
private fun DividerHandle(vertical: Boolean, color: Color, thick: Boolean) {
    val thickness: Dp = if (thick) 4.dp else 3.dp
    Box(
        modifier = Modifier
            .then(
                if (vertical) {
                    Modifier.width(thickness).height(40.dp)
                } else {
                    Modifier.height(thickness).width(40.dp)
                }
            )
            .clip(CircleShape)
            .background(color)
    )
}
