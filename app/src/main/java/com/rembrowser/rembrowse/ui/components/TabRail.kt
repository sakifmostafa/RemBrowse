package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.ui.theme.RemDimens

/** How many bookmarks are pinned to the rail; the rest live in the bookmarks list. */
private const val MAX_PINNED_SITES = 6

/**
 * The vertical strip on the left edge: tab switcher, pinned sites, open tabs, new tab.
 * Shown on medium and large windows; compact windows use the tab-count button instead.
 */
@Composable
fun TabRail(
    uiState: BrowserUiState,
    onOpenTabSwitcher: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseOtherTabs: (String) -> Unit,
    onAssignTabToPane: (paneIndex: Int, tabId: String) -> Unit,
    onNewTab: () -> Unit,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pinned = uiState.bookmarks.take(MAX_PINNED_SITES)

    Column(
        modifier = modifier
            .width(RemDimens.RailWidth)
            .fillMaxHeight()
            .testTag("tab_rail"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.height(RemDimens.ToolbarHeight),
            contentAlignment = Alignment.Center
        ) {
            RemIconButton(
                icon = Icons.Outlined.Tab,
                label = "Tab switcher",
                onClick = onOpenTabSwitcher,
                modifier = Modifier.testTag("tab_switcher_button")
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (pinned.isNotEmpty()) {
                items(pinned, key = { "pin-${it.id}" }) { bookmark ->
                    PinnedSiteItem(
                        bookmark = bookmark,
                        onOpen = { onOpenBookmark(bookmark.url) },
                        onRemove = { onRemoveBookmark(bookmark.id) }
                    )
                }
                item(key = "pin-divider") { RailDivider() }
            }
            items(uiState.tabs, key = { it.id }) { tab ->
                val paneIndex = uiState.paneIndexOf(tab.id)
                RailTabItem(
                    tab = tab,
                    isActive = tab.id == uiState.activeTabId,
                    isVisibleInPane = paneIndex >= 0,
                    paneCount = uiState.layoutMode.paneCount,
                    onSelect = { onSelectTab(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                    onCloseOthers = { onCloseOtherTabs(tab.id) },
                    onShowInPane = { pane -> onAssignTabToPane(pane, tab.id) }
                )
            }
        }

        RailDivider()
        RemIconButton(
            icon = Icons.Outlined.Add,
            label = "New tab",
            onClick = onNewTab,
            modifier = Modifier.testTag("new_tab_button")
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RailTile(
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    role: Role,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            selected -> colors.surfaceContainerHighest
            highlighted -> colors.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "railTileFill"
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        focusable = false
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Box(
                modifier = modifier
                    .padding(horizontal = 4.dp)
                    .minimumInteractiveComponentSize()
                    .size(RemDimens.ControlSize)
                    .clip(MaterialTheme.shapes.medium)
                    .background(fill)
                    .contextMenuTrigger(onOpenMenu)
                    .combinedClickable(
                        role = role,
                        onClick = onClick,
                        onLongClick = onOpenMenu,
                        onLongClickLabel = "More options"
                    )
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center
            ) {
                content()
            }
            if (selected) {
                // Shape as well as colour marks the focused tab
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                )
            }
        }
    }
}

@Composable
private fun RailTabItem(
    tab: BrowserTab,
    isActive: Boolean,
    isVisibleInPane: Boolean,
    paneCount: Int,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onShowInPane: (Int) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        RailTile(
            label = tab.displayTitle,
            selected = isActive,
            highlighted = isVisibleInPane,
            role = Role.Tab,
            onClick = onSelect,
            onOpenMenu = { showMenu = true },
            modifier = Modifier.testTag("tab_bar_item_${tab.id}")
        ) {
            if (tab.isLoading && !tab.isNewTabPage) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                SiteIcon(
                    url = tab.url,
                    title = tab.displayTitle,
                    isNewTab = tab.isNewTabPage,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Close tab") },
                trailingIcon = { ShortcutHint("Ctrl+W") },
                onClick = {
                    showMenu = false
                    onClose()
                },
                modifier = Modifier.testTag("tab_bar_close_${tab.id}")
            )
            DropdownMenuItem(
                text = { Text("Close other tabs") },
                onClick = {
                    showMenu = false
                    onCloseOthers()
                }
            )
            if (paneCount > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                for (pane in 0 until paneCount) {
                    DropdownMenuItem(
                        text = { Text("Show in pane ${pane + 1}") },
                        onClick = {
                            showMenu = false
                            onShowInPane(pane)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedSiteItem(
    bookmark: BookmarkItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        RailTile(
            label = bookmark.title,
            selected = false,
            highlighted = false,
            role = Role.Button,
            onClick = onOpen,
            onOpenMenu = { showMenu = true }
        ) {
            SiteIcon(url = bookmark.url, title = bookmark.title)
        }
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Open") },
                onClick = {
                    showMenu = false
                    onOpen()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove bookmark") },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}
