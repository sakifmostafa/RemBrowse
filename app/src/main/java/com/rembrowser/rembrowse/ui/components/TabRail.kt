package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.PaneDropTarget
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PageAddress
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.ui.theme.RemMotion

/** How many bookmarks are pinned to the rail; the rest live in the bookmarks list. */
private const val MAX_PINNED_SITES = 6

/** The pinned sites' shelf: the rail's width less a 4dp inset each side. */
private val PinnedShelfWidth = 48.dp

/** Pinned sites' icons, a little smaller than a tab's ([RemDimens.IconSize]). */
private val PinnedIconSize = 18.dp

/** Room under the shelf, so the first tab starts a group of its own. */
private val PinnedShelfGap = 10.dp

/**
 * The colours of the pinned sites' shelf, from the theme's roles only: the same tonal surface and
 * hairline as the find bar and the permission card, the secondary content colour for the icons,
 * and the accent for the dot of a site with a tab open.
 */
internal data class PinnedShelfColors(
    val container: Color,
    val border: Color,
    val icon: Color,
    val openDot: Color
)

internal fun pinnedShelfColors(scheme: ColorScheme) = PinnedShelfColors(
    container = scheme.surfaceContainerHigh,
    border = scheme.outlineVariant,
    icon = scheme.onSurfaceVariant,
    openDot = scheme.primary
)

/** What a pinned site is called to a screen reader and in its tooltip. */
internal fun pinnedSiteLabel(title: String, open: Boolean): String =
    if (open) "$title, pinned, open" else "$title, pinned"

/**
 * The ids of the [bookmarks] with a tab open on them: a web page in [tabs] at the bookmark's page
 * or below it ([PageAddress.isUnder]), the same rule that decides which tab a click shows.
 */
internal fun openBookmarkIds(bookmarks: List<BookmarkItem>, tabs: List<BrowserTab>): Set<String> {
    val pages = tabs.mapNotNull { tab -> if (tab.isWebPage) PageAddress.of(tab.url) else null }
    return bookmarks.filter { bookmark ->
        val address = PageAddress.of(bookmark.url)
        address != null && pages.any { it.isUnder(address) }
    }.mapTo(HashSet()) { it.id }
}

/**
 * How many extension buttons sit at the bottom of the rail. With more extensions than this,
 * the last place goes to a button listing the rest, so the tabs keep their room.
 */
internal const val MAX_RAIL_EXTENSIONS = 4

/** One rail button (its 48dp touch target) and the 2dp gap that follows it. */
private val RailSlot = 50.dp

/** The extension section's divider (13dp) and its bottom margin (6dp). */
private val RailExtensionsChrome = 19.dp

/** Room the tabs always keep: two tabs, the divider and the new-tab button. */
private val MinTabArea = RailSlot * 2 + 13.dp + 48.dp

/**
 * How many extension buttons, the overflow button included, fit at the foot of a rail
 * [railHeight] tall without crowding out the tabs. On a short window (a phone in landscape,
 * or with the keyboard up) that can be a single button listing every extension, which keeps
 * them all reachable.
 */
internal fun railExtensionSlots(railHeight: Dp): Int {
    val room = railHeight - RemDimens.ToolbarHeight - MinTabArea - RailExtensionsChrome
    return (room / RailSlot).toInt().coerceIn(1, MAX_RAIL_EXTENSIONS)
}

/**
 * The vertical strip on the left edge: tab switcher, the shelf of pinned sites, open tabs, new
 * tab, and the toolbar buttons of extensions at the bottom.
 * Shown on medium and large windows; compact windows use the tab-count button instead, and
 * their extension buttons live in the More menu.
 *
 * @param extensions extensions with a toolbar button, in the order they are shown.
 * @param openPopupExtensionId the extension whose popup is open, marked like a selected tab.
 * @param dragState when given, tab tiles can be dragged onto the panes; [onDropTab] gets the drop.
 * @param onOpenBookmark a pinned site was clicked: show its open tab, or open it.
 * @param onOpenBookmarkInNewTab "Open in new tab" from a pinned site's menu.
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
    onOpenBookmarkInNewTab: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClosePane: (paneIndex: Int) -> Unit = {},
    dragState: TabDragState? = null,
    onDropTab: (tabId: String, paneIndex: Int, zone: DropZone) -> Unit = { _, _, _ -> },
    extensions: List<InstalledExtension> = emptyList(),
    openPopupExtensionId: String? = null,
    onTriggerExtensionAction: (String) -> Unit = {},
    onManageExtensions: () -> Unit = {}
) {
    val pinned = uiState.bookmarks.take(MAX_PINNED_SITES)
    val openPins = remember(pinned, uiState.tabs) { openBookmarkIds(pinned, uiState.tabs) }
    val reduceMotion = rememberReduceMotion()

    BoxWithConstraints(
        modifier = modifier
            .width(RemDimens.RailWidth)
            .fillMaxHeight()
            .testTag("tab_rail")
    ) {
        val extensionSlots = railExtensionSlots(maxHeight)
        Column(
            modifier = Modifier.fillMaxSize(),
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

            // Tabs and the new-tab button take the room the extensions leave; the new-tab button
            // follows the last tab until the list has to scroll.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-shelf") {
                            PinnedShelf(
                                pinned = pinned,
                                openPins = openPins,
                                reduceMotion = reduceMotion,
                                onOpen = onOpenBookmark,
                                onOpenInNewTab = onOpenBookmarkInNewTab,
                                onRemove = onRemoveBookmark
                            )
                        }
                    }
                    items(uiState.tabs, key = { it.id }) { tab ->
                        val paneIndex = uiState.paneIndexOf(tab.id)
                        RailTabItem(
                            tab = tab,
                            isActive = tab.id == uiState.activeTabId,
                            isVisibleInPane = paneIndex >= 0,
                            paneCount = uiState.paneCount,
                            onSelect = { onSelectTab(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                            onCloseOthers = { onCloseOtherTabs(tab.id) },
                            onShowInPane = { pane -> onAssignTabToPane(pane, tab.id) },
                            onClosePane = { onClosePane(paneIndex) },
                            dragState = dragState,
                            onDrop = { target -> onDropTab(tab.id, target.paneIndex, target.zone) }
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

            if (extensions.isNotEmpty()) {
                RailExtensions(
                    extensions = extensions,
                    slots = extensionSlots,
                    openPopupExtensionId = openPopupExtensionId,
                    onTrigger = onTriggerExtensionAction,
                    onManage = onManageExtensions
                )
            }
        }
    }
}

/**
 * Extension buttons pinned to the bottom of the rail, like a desktop browser's toolbar. When
 * there are more extensions than [slots], the last slot lists the rest.
 */
@Composable
private fun RailExtensions(
    extensions: List<InstalledExtension>,
    slots: Int,
    openPopupExtensionId: String?,
    onTrigger: (String) -> Unit,
    onManage: () -> Unit
) {
    val shown = if (extensions.size > slots) extensions.take(slots - 1) else extensions
    val overflow = extensions.drop(shown.size)
    Column(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .testTag("rail_extensions"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        RailDivider()
        shown.forEach { extension ->
            RailExtensionItem(
                extension = extension,
                popupOpen = extension.id == openPopupExtensionId,
                onClick = { onTrigger(extension.id) },
                onManage = onManage
            )
        }
        if (overflow.isNotEmpty()) {
            RailExtensionsOverflow(
                extensions = overflow,
                showsAll = shown.isEmpty(),
                popupOpen = overflow.any { it.id == openPopupExtensionId },
                onTrigger = onTrigger,
                onManage = onManage
            )
        }
    }
}

@Composable
private fun RailExtensionItem(
    extension: InstalledExtension,
    popupOpen: Boolean,
    onClick: () -> Unit,
    onManage: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val badge = extension.badgeText?.takeIf { it.isNotBlank() }
    Box {
        RailTile(
            // The badge is part of the name, so a screen reader hears the count too
            label = if (badge != null) "${extension.name} ($badge)" else extension.name,
            selected = popupOpen,
            highlighted = false,
            role = Role.Button,
            onClick = onClick,
            onOpenMenu = { showMenu = true },
            modifier = Modifier.testTag("rail_extension_${extension.id}")
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ExtensionIcon(
                    icon = extension.icon,
                    modifier = Modifier.align(Alignment.Center)
                )
                if (badge != null) {
                    ExtensionBadge(
                        text = badge,
                        backgroundColor = extension.badgeBackgroundColor,
                        textColor = extension.badgeTextColor,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 1.dp)
                    )
                }
            }
        }
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Open ${extension.name}") },
                onClick = {
                    showMenu = false
                    onClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Manage extensions") },
                onClick = {
                    showMenu = false
                    onManage()
                }
            )
        }
    }
}

/** The last place on the rail when there are too many extensions: a menu of the rest. */
@Composable
private fun RailExtensionsOverflow(
    extensions: List<InstalledExtension>,
    showsAll: Boolean,
    popupOpen: Boolean,
    onTrigger: (String) -> Unit,
    onManage: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        RailTile(
            label = if (showsAll) "Extensions" else "More extensions",
            selected = popupOpen,
            highlighted = showMenu,
            role = Role.Button,
            onClick = { showMenu = true },
            onOpenMenu = { showMenu = true },
            modifier = Modifier.testTag("rail_extensions_more")
        ) {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(RemDimens.IconSize)
            )
        }
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            extensions.forEach { extension ->
                ExtensionMenuItem(
                    extension = extension,
                    onClick = {
                        showMenu = false
                        onTrigger(extension.id)
                    }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Manage extensions") },
                onClick = {
                    showMenu = false
                    onManage()
                }
            )
        }
    }
}

/** How a [RailTile] looks when it is neither selected nor highlighted. */
private enum class RailTileStyle {
    /** A bare button, such as an extension's. */
    Button,

    /** An open tab: a subtle tile of its own, a bordered card like [ListCard]. */
    Tab,

    /** A pinned site: a bare icon on the shelf, with a round hover and press area. */
    Pinned
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
    style: RailTileStyle = RailTileStyle.Button,
    /** What the tooltip shows, when it says less than [label], the accessible name. */
    tooltip: String = label,
    /**
     * A middle click (a mouse's middle button, or a touchpad's three-finger tap): closes a tab,
     * opens a pinned site in a new tab.
     */
    onMiddleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            selected -> colors.surfaceContainerHighest
            highlighted -> colors.surfaceContainerHigh
            style == RailTileStyle.Tab -> colors.surfaceContainerLow
            else -> Color.Transparent
        },
        label = "railTileFill"
    )
    val shape: Shape = if (style == RailTileStyle.Pinned) CircleShape else MaterialTheme.shapes.medium
    TooltipBox(
        // Beside the rail, as on desktop, so it never covers the tile above
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
        focusable = false
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Box(
                modifier = modifier
                    // On the shelf the 48dp touch target fills the shelf's width
                    .padding(horizontal = if (style == RailTileStyle.Pinned) 0.dp else 4.dp)
                    .minimumInteractiveComponentSize()
                    .size(RemDimens.ControlSize)
                    .keyboardFocusRing(shape)
                    .clip(shape)
                    .background(fill)
                    .then(
                        if (style == RailTileStyle.Tab) {
                            Modifier.border(1.dp, colors.outlineVariant, shape)
                        } else {
                            Modifier
                        }
                    )
                    .contextMenuTrigger(onOpenMenu)
                    .then(if (onMiddleClick != null) Modifier.middleClickTrigger(onMiddleClick) else Modifier)
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
    onShowInPane: (Int) -> Unit,
    onClosePane: () -> Unit,
    dragState: TabDragState?,
    onDrop: (PaneDropTarget) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        RailTile(
            label = tab.displayTitle,
            selected = isActive,
            highlighted = isVisibleInPane || dragState?.draggedTabId == tab.id,
            role = Role.Tab,
            onClick = onSelect,
            onOpenMenu = { showMenu = true },
            onMiddleClick = onClose,
            style = RailTileStyle.Tab,
            modifier = Modifier
                .testTag("tab_bar_item_${tab.id}")
                .then(
                    if (dragState != null) {
                        Modifier.tabDragSource(
                            tabId = tab.id,
                            dragState = dragState,
                            // A long press opened the menu before the finger moved on
                            onDragStart = { showMenu = false },
                            onDrop = onDrop
                        )
                    } else {
                        Modifier
                    }
                )
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
                if (isVisibleInPane) {
                    // The pane goes, the tab stays open here in the rail
                    DropdownMenuItem(
                        text = { Text("Close pane") },
                        onClick = {
                            showMenu = false
                            onClosePane()
                        },
                        modifier = Modifier.testTag("tab_bar_close_pane_${tab.id}")
                    )
                }
            }
        }
    }
}

/**
 * The pinned sites, together on one tonal capsule at the top of the rail, so they never read as
 * open tabs. A dot on a site's icon says a tab is open at its page or below it ([openPins]).
 */
@Composable
private fun PinnedShelf(
    pinned: List<BookmarkItem>,
    openPins: Set<String>,
    reduceMotion: Boolean,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val shelf = pinnedShelfColors(MaterialTheme.colorScheme)
    Surface(
        modifier = Modifier
            .padding(top = 2.dp, bottom = PinnedShelfGap)
            .width(PinnedShelfWidth)
            .testTag("pinned_shelf"),
        // Half the shelf's width, so its ends are round and each icon's hover circle sits in them
        shape = MaterialTheme.shapes.extraLarge,
        color = shelf.container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, shelf.border)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            pinned.forEach { bookmark ->
                PinnedSiteItem(
                    bookmark = bookmark,
                    isOpen = bookmark.id in openPins,
                    colors = shelf,
                    reduceMotion = reduceMotion,
                    onOpen = { onOpen(bookmark.url) },
                    onOpenInNewTab = { onOpenInNewTab(bookmark.url) },
                    onRemove = { onRemove(bookmark.id) }
                )
            }
        }
    }
}

@Composable
private fun PinnedSiteItem(
    bookmark: BookmarkItem,
    isOpen: Boolean,
    colors: PinnedShelfColors,
    reduceMotion: Boolean,
    onOpen: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        RailTile(
            // Said in words as well as shown by the dot, which is never the only sign; the
            // tooltip, beside the dot, needs only the name
            label = pinnedSiteLabel(bookmark.title, isOpen),
            tooltip = bookmark.title,
            selected = false,
            highlighted = false,
            role = Role.Button,
            onClick = onOpen,
            onOpenMenu = { showMenu = true },
            onMiddleClick = onOpenInNewTab,
            style = RailTileStyle.Pinned,
            modifier = Modifier.testTag("pinned_site_${bookmark.id}")
        ) {
            Box(modifier = Modifier.size(PinnedIconSize + 8.dp)) {
                SiteIcon(
                    url = bookmark.url,
                    title = bookmark.title,
                    size = PinnedIconSize,
                    tint = colors.icon,
                    modifier = Modifier.align(Alignment.Center)
                )
                OpenSiteDot(
                    visible = isOpen,
                    colors = colors,
                    reduceMotion = reduceMotion,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .testTag("pinned_open_dot_${bookmark.id}")
                )
            }
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
                text = { Text("Open in new tab") },
                onClick = {
                    showMenu = false
                    onOpenInNewTab()
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

/**
 * The accent dot on a pinned site with a tab open, ringed in the shelf's colour so it stands
 * clear of any favicon. It fades in and out, or just appears with Android's animations off.
 */
@Composable
private fun OpenSiteDot(
    visible: Boolean,
    colors: PinnedShelfColors,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(RemMotion.defaultEffects()),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(RemMotion.fastEffects()),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(colors.container, CircleShape)
                .padding(2.dp)
                .background(colors.openDot, CircleShape)
        )
    }
}
