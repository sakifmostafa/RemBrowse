package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.theme.RemAccentCyan
import com.rembrowser.rembrowse.ui.theme.RemBackground
import com.rembrowser.rembrowse.ui.theme.RemBorder
import com.rembrowser.rembrowse.ui.theme.RemSurface
import com.rembrowser.rembrowse.ui.theme.RemSurfaceElevated
import com.rembrowser.rembrowse.ui.theme.RemTextPrimary
import com.rembrowser.rembrowse.ui.theme.RemTextSecondary
import com.rembrowser.rembrowse.util.UrlResolver

@Composable
fun TiledBrowserGrid(
    uiState: BrowserUiState,
    onFocusPane: (Int) -> Unit,
    onUpdateHorizontalSplit: (Float) -> Unit,
    onUpdateVerticalSplit: (Float) -> Unit,
    onResetSplitRatios: () -> Unit,
    onBackPane: (Int) -> Unit,
    onReloadPane: (Int) -> Unit,
    onSwapPanes: (Int, Int) -> Unit,
    onMaximizePane: (Int) -> Unit,
    onCloseTab: (String) -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(RemBackground)
    ) {
        val density = LocalDensity.current
        val totalWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val totalHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val hRatio = uiState.splitRatios.horizontal
        val vRatio = uiState.splitRatios.vertical

        when (uiState.layoutMode) {
            LayoutMode.SINGLE_1X1 -> {
                BrowserPaneFrame(
                    paneIndex = 0,
                    uiState = uiState,
                    showPaneHeader = false,
                    onFocusPane = onFocusPane,
                    onBackPane = onBackPane,
                    onReloadPane = onReloadPane,
                    onSwapPanes = onSwapPanes,
                    onMaximizePane = onMaximizePane,
                    onCloseTab = onCloseTab,
                    paneViewport = paneViewport,
                    modifier = Modifier.fillMaxSize()
                )
            }

            LayoutMode.SPLIT_1X2 -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    BrowserPaneFrame(
                        paneIndex = 0,
                        uiState = uiState,
                        showPaneHeader = true,
                        onFocusPane = onFocusPane,
                        onBackPane = onBackPane,
                        onReloadPane = onReloadPane,
                        onSwapPanes = onSwapPanes,
                        onMaximizePane = onMaximizePane,
                        onCloseTab = onCloseTab,
                        paneViewport = paneViewport,
                        modifier = Modifier
                            .weight(hRatio)
                            .fillMaxHeight()
                    )

                    VerticalSplitDivider(
                        onDragDeltaPx = { dx -> onUpdateHorizontalSplit(dx / totalWidthPx) },
                        onDoubleTap = onResetSplitRatios
                    )

                    BrowserPaneFrame(
                        paneIndex = 1,
                        uiState = uiState,
                        showPaneHeader = true,
                        onFocusPane = onFocusPane,
                        onBackPane = onBackPane,
                        onReloadPane = onReloadPane,
                        onSwapPanes = onSwapPanes,
                        onMaximizePane = onMaximizePane,
                        onCloseTab = onCloseTab,
                        paneViewport = paneViewport,
                        modifier = Modifier
                            .weight(1f - hRatio)
                            .fillMaxHeight()
                    )
                }
            }

            LayoutMode.SPLIT_2X1 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    BrowserPaneFrame(
                        paneIndex = 0,
                        uiState = uiState,
                        showPaneHeader = true,
                        onFocusPane = onFocusPane,
                        onBackPane = onBackPane,
                        onReloadPane = onReloadPane,
                        onSwapPanes = onSwapPanes,
                        onMaximizePane = onMaximizePane,
                        onCloseTab = onCloseTab,
                        paneViewport = paneViewport,
                        modifier = Modifier
                            .weight(vRatio)
                            .fillMaxWidth()
                    )

                    HorizontalSplitDivider(
                        onDragDeltaPx = { dy -> onUpdateVerticalSplit(dy / totalHeightPx) },
                        onDoubleTap = onResetSplitRatios
                    )

                    BrowserPaneFrame(
                        paneIndex = 1,
                        uiState = uiState,
                        showPaneHeader = true,
                        onFocusPane = onFocusPane,
                        onBackPane = onBackPane,
                        onReloadPane = onReloadPane,
                        onSwapPanes = onSwapPanes,
                        onMaximizePane = onMaximizePane,
                        onCloseTab = onCloseTab,
                        paneViewport = paneViewport,
                        modifier = Modifier
                            .weight(1f - vRatio)
                            .fillMaxWidth()
                    )
                }
            }

            LayoutMode.GRID_2X2 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Row (Panes 0 and 1)
                    Row(
                        modifier = Modifier
                            .weight(vRatio)
                            .fillMaxWidth()
                    ) {
                        BrowserPaneFrame(
                            paneIndex = 0,
                            uiState = uiState,
                            showPaneHeader = true,
                            onFocusPane = onFocusPane,
                            onBackPane = onBackPane,
                            onReloadPane = onReloadPane,
                            onSwapPanes = onSwapPanes,
                            onMaximizePane = onMaximizePane,
                            onCloseTab = onCloseTab,
                            paneViewport = paneViewport,
                            modifier = Modifier
                                .weight(hRatio)
                                .fillMaxHeight()
                        )

                        VerticalSplitDivider(
                            onDragDeltaPx = { dx -> onUpdateHorizontalSplit(dx / totalWidthPx) },
                            onDoubleTap = onResetSplitRatios
                        )

                        BrowserPaneFrame(
                            paneIndex = 1,
                            uiState = uiState,
                            showPaneHeader = true,
                            onFocusPane = onFocusPane,
                            onBackPane = onBackPane,
                            onReloadPane = onReloadPane,
                            onSwapPanes = onSwapPanes,
                            onMaximizePane = onMaximizePane,
                            onCloseTab = onCloseTab,
                            paneViewport = paneViewport,
                            modifier = Modifier
                                .weight(1f - hRatio)
                                .fillMaxHeight()
                        )
                    }

                    HorizontalSplitDivider(
                        onDragDeltaPx = { dy -> onUpdateVerticalSplit(dy / totalHeightPx) },
                        onDoubleTap = onResetSplitRatios
                    )

                    // Bottom Row (Panes 2 and 3)
                    Row(
                        modifier = Modifier
                            .weight(1f - vRatio)
                            .fillMaxWidth()
                    ) {
                        BrowserPaneFrame(
                            paneIndex = 2,
                            uiState = uiState,
                            showPaneHeader = true,
                            onFocusPane = onFocusPane,
                            onBackPane = onBackPane,
                            onReloadPane = onReloadPane,
                            onSwapPanes = onSwapPanes,
                            onMaximizePane = onMaximizePane,
                            onCloseTab = onCloseTab,
                            paneViewport = paneViewport,
                            modifier = Modifier
                                .weight(hRatio)
                                .fillMaxHeight()
                        )

                        VerticalSplitDivider(
                            onDragDeltaPx = { dx -> onUpdateHorizontalSplit(dx / totalWidthPx) },
                            onDoubleTap = onResetSplitRatios
                        )

                        BrowserPaneFrame(
                            paneIndex = 3,
                            uiState = uiState,
                            showPaneHeader = true,
                            onFocusPane = onFocusPane,
                            onBackPane = onBackPane,
                            onReloadPane = onReloadPane,
                            onSwapPanes = onSwapPanes,
                            onMaximizePane = onMaximizePane,
                            onCloseTab = onCloseTab,
                            paneViewport = paneViewport,
                            modifier = Modifier
                                .weight(1f - hRatio)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserPaneFrame(
    paneIndex: Int,
    uiState: BrowserUiState,
    showPaneHeader: Boolean,
    onFocusPane: (Int) -> Unit,
    onBackPane: (Int) -> Unit,
    onReloadPane: (Int) -> Unit,
    onSwapPanes: (Int, Int) -> Unit,
    onMaximizePane: (Int) -> Unit,
    onCloseTab: (String) -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val tab = uiState.tabForPane(paneIndex) ?: return
    val isActive = uiState.activePaneIndex == paneIndex
    var showSwapMenu by remember { mutableStateOf(false) }

    val frameShape = if (showPaneHeader) RoundedCornerShape(6.dp) else RoundedCornerShape(0.dp)
    val borderColor = when {
        !showPaneHeader -> RemBackground
        isActive -> RemAccentCyan
        else -> RemBorder
    }

    Column(
        modifier = modifier
            .padding(if (showPaneHeader) 2.dp else 0.dp)
            .clip(frameShape)
            .border(
                width = if (showPaneHeader && isActive) 1.5.dp else if (showPaneHeader) 1.dp else 0.dp,
                color = borderColor,
                shape = frameShape
            )
            .background(RemSurface)
            .testTag("browser_pane_$paneIndex")
    ) {
        if (showPaneHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(if (isActive) RemSurfaceElevated else RemSurface)
                    .clickable { onFocusPane(paneIndex) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isActive) RemAccentCyan else RemBorder)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${paneIndex + 1}",
                        color = if (isActive) RemSurface else RemTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = tab.title.ifBlank { UrlResolver.extractDisplayHost(tab.url) },
                    color = if (isActive) RemTextPrimary else RemTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        onFocusPane(paneIndex)
                        onBackPane(paneIndex)
                    },
                    enabled = tab.canGoBack,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Pane Back",
                        tint = if (tab.canGoBack) RemTextPrimary else RemTextSecondary.copy(alpha = 0.35f),
                        modifier = Modifier.size(13.dp)
                    )
                }

                IconButton(
                    onClick = {
                        onFocusPane(paneIndex)
                        onReloadPane(paneIndex)
                    },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Pane Reload",
                        tint = RemTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // Swap with another pane
                Box {
                    IconButton(
                        onClick = { showSwapMenu = true },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap Pane",
                            tint = RemTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSwapMenu,
                        onDismissRequest = { showSwapMenu = false },
                        modifier = Modifier.background(RemSurfaceElevated)
                    ) {
                        for (other in 0 until uiState.layoutMode.paneCount) {
                            if (other != paneIndex) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Swap with Pane ${other + 1}",
                                            color = RemTextPrimary,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        showSwapMenu = false
                                        onSwapPanes(paneIndex, other)
                                    }
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { onMaximizePane(paneIndex) },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Maximize Pane",
                        tint = RemTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }

                IconButton(
                    onClick = { onCloseTab(tab.id) },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Tab",
                        tint = RemTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            if (tab.isLoading) {
                LinearProgressIndicator(
                    progress = { (tab.progress.coerceIn(5, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = RemAccentCyan,
                    trackColor = RemSurface
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(paneIndex, isActive) {
                    if (!isActive) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    onFocusPane(paneIndex)
                                }
                            }
                        }
                    }
                }
        ) {
            paneViewport(tab, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun VerticalSplitDivider(
    onDragDeltaPx: (Float) -> Unit,
    onDoubleTap: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(10.dp)
            .background(if (isDragging) RemAccentCyan.copy(alpha = 0.25f) else RemBackground)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDeltaPx(dragAmount.x)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isDragging) RemAccentCyan else RemBorder)
        )
    }
}

@Composable
private fun HorizontalSplitDivider(
    onDragDeltaPx: (Float) -> Unit,
    onDoubleTap: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(if (isDragging) RemAccentCyan.copy(alpha = 0.25f) else RemBackground)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDeltaPx(dragAmount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isDragging) RemAccentCyan else RemBorder)
        )
    }
}
