package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.ColorScheme
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** How many bookmarks are pinned to the rail; the rest live in the bookmarks list. */
private const val MAX_PINNED_SITES = 6

/**
 * How far a tile sits in from the rail's edges, so a 40dp tile is in the middle of the 56dp
 * rail. An expanded rail's tiles keep the same edge, so their icons stay where they were.
 */
private val RailTileInset = 8.dp

/**
 * How far the pinned sites' shelf sits in from the rail's edges, and its pins from the shelf's:
 * the collapsed shelf is the rail's width less 4dp each side, 48dp.
 */
private val PinnedShelfInset = 4.dp

/** Pinned sites' icons, a little smaller than a tab's ([RemDimens.IconSize]). */
private val PinnedIconSize = 18.dp

/** Room under the shelf, so the first tab starts a group of its own. */
private val PinnedShelfGap = 10.dp

/**
 * The expanded rail drawn over the page (see [TabRail]'s `floating`): rounded on the page's side
 * as Material's modal navigation rail is (its large shape, 16dp), with a level 2 shadow.
 */
private val FloatingRailCorner = 16.dp
private val FloatingRailShape = RoundedCornerShape(topEnd = FloatingRailCorner, bottomEnd = FloatingRailCorner)
private val FloatingRailElevation = 3.dp

/** How much the page behind the rail drawn over it is dimmed: Material's scrim, 32%. */
internal const val RailScrimAlpha = 0.32f

/**
 * The same in the dark theme, 50%: over the dark toolbar and a dark page Material's 32% hardly
 * shows, and a light page dimmed this far still stands 4.5:1 from the rail's dark fill.
 */
internal const val DarkRailScrimAlpha = 0.5f

private fun ColorScheme.isDark(): Boolean = background.luminance() < 0.5f

/** How much the page behind the rail drawn over it is dimmed in [scheme]. */
internal fun railScrimAlpha(scheme: ColorScheme): Float = if (scheme.isDark()) DarkRailScrimAlpha else RailScrimAlpha

/**
 * The line along the page side of the rail drawn over the page, in the dark theme: there its
 * shadow doesn't show, and its fill is the toolbar's, so without it the rail's edge was lost
 * against the toolbar and a dark page. The theme's outline, 3:1 against them dimmed (and against
 * the rail). The light theme needs none: its shadow and the dimmed toolbar mark the edge.
 */
internal fun floatingRailEdge(scheme: ColorScheme): Color? = if (scheme.isDark()) scheme.outline else null

private val FloatingRailEdgeWidth = 1.dp

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

/** A rail button's or tab's height: its 48dp touch target. */
private val RailTileHeight = 48.dp

/** One rail button and the 2dp gap that follows it. */
private val RailSlot = RailTileHeight + 2.dp

/**
 * Whether the tab switcher button scrolls with the list on a rail [railHeight] tall: on a short
 * window (below Material's medium height, 480dp: a phone held sideways, or with the keyboard up)
 * it does, so the list keeps the room it had before the rail had a header. Elsewhere it stays put
 * under the header.
 */
private fun switcherScrolls(railHeight: Dp): Boolean = railHeight < RemDimens.MediumHeight

/**
 * Above the list: the header, level with the toolbar, with the button that expands and collapses
 * the rail, and the tab switcher button under it unless it scrolls with the list.
 */
private fun railTopArea(railHeight: Dp): Dp =
    RemDimens.ToolbarHeight + if (switcherScrolls(railHeight)) 0.dp else RailSlot

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
    val room = railHeight - railTopArea(railHeight) - MinTabArea - RailExtensionsChrome
    return (room / RailSlot).toInt().coerceIn(1, MAX_RAIL_EXTENSIONS)
}

/**
 * The vertical strip on the left edge: the button that expands and collapses it, the tab
 * switcher, the shelf of pinned sites, open tabs, new tab, and the toolbar buttons of extensions
 * at the bottom. Shown on medium and large windows; compact windows use the tab-count button
 * instead, and their extension buttons live in the More menu.
 *
 * Collapsed it is a 56dp strip of icons. Expanded ([expanded]) it is Material 3 Expressive's
 * expanded navigation rail: [RemDimens.RailExpandedWidth] wide, each tab, pinned site and button
 * with its title beside its icon, in Material's label type. The icons stay where they were, the
 * width moves with the theme's spatial spring and the titles fade in. Everything works as on the
 * collapsed rail: menus, the middle click, dragging tabs onto the panes, the open dots, the
 * extension badges, keyboard focus; tooltips stand aside where the title is in sight.
 *
 * @param floating drawn over the page rather than beside it, on a window too narrow for the
 *   expanded rail to push the page aside: while wider than the collapsed rail it is a raised
 *   surface, and Esc closes it ([onDismiss]). The screen dims the page behind it ([TabRailScrim]).
 * @param extensions extensions with a toolbar button, in the order they are shown.
 * @param openPopupExtensionId the extension whose popup is open, marked like a selected tab.
 * @param dragState when given, tab tiles can be dragged onto the panes; [onDropTab] gets the drop.
 * @param onOpenBookmark a pinned site was clicked: show its open tab, or open it.
 * @param onOpenBookmarkInNewTab "Open in new tab" from a pinned site's menu.
 */
@Composable
fun TabRail(
    uiState: BrowserUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
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
    floating: Boolean = false,
    onDismiss: () -> Unit = {},
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
    val listState = rememberLazyListState()
    // Read while laying out and drawing only, so the rail is not recomposed on every frame
    val width = animateDpAsState(
        targetValue = if (expanded) RemDimens.RailExpandedWidth else RemDimens.RailWidth,
        animationSpec = RemMotion.defaultSpatial(),
        label = "railWidth"
    )
    val colors = MaterialTheme.colorScheme
    val edge = floatingRailEdge(colors)
    val floatingOpen = floating && expanded
    // Opened over the page, it holds the keyboard as a dialog does. From the keyboard the focus
    // moves into it; opened with a pointer, the focus leaves the page, so Tab starts in the rail
    // rather than in a page under the scrim. Tab and Shift+Tab then go round the rail (its first
    // place, the menu button, and its last), and the arrows stop at its ends (onExit below).
    val toggleFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val inputModes = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(floatingOpen) {
        if (!floatingOpen) return@LaunchedEffect
        if (inputModes.inputMode == InputMode.Keyboard) {
            runCatching { toggleFocus.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }
    val trapsFocus by rememberUpdatedState(floatingOpen)
    val dismiss by rememberUpdatedState(onDismiss)
    // Whether the rail was last used with a finger: the expanded rail's close buttons then stay
    // in sight, as there is no pointer to hover with (RailTile's trailing)
    var touchUsed by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .testTag("tab_rail")
    ) {
        val extensionSlots = railExtensionSlots(maxHeight)
        val switcherInList = switcherScrolls(maxHeight)
        // Before the tabs in the list: the tab switcher on a short window, then the shelf
        val itemsBeforeTabs = (if (switcherInList) 1 else 0) + (if (pinned.isNotEmpty()) 1 else 0)
        val activeIndex = uiState.tabs.indexOfFirst { it.id == uiState.activeTabId }
            .let { if (it < 0) it else it + itemsBeforeTabs }
        KeepActiveTabInView(
            state = listState,
            activeTabId = uiState.activeTabId,
            activeIndex = activeIndex,
            pinnedCount = pinned.size,
            reduceMotion = reduceMotion
        )
        val tabSwitcherButton = @Composable {
            RailTile(
                label = "Tab switcher",
                selected = false,
                highlighted = false,
                role = Role.Button,
                onClick = onOpenTabSwitcher,
                onOpenMenu = null,
                title = "Tab switcher",
                showTitle = expanded,
                titleTag = "tab_switcher_title",
                modifier = Modifier.testTag("tab_switcher_button")
            ) {
                RailIcon(Icons.Outlined.Tab)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    // A spring may overshoot on its way back; the rail is never narrower than its icons
                    val px = width.value.coerceAtLeast(RemDimens.RailWidth).roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
                    layout(px, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    // Over the page it casts a shadow while it is wider than the collapsed rail
                    val raised = floating && width.value > RemDimens.RailWidth + 1.dp
                    shadowElevation = if (raised) FloatingRailElevation.toPx() else 0f
                    shape = if (floating) FloatingRailShape else RectangleShape
                    clip = false
                }
                // The chrome's colour, so a page beside it never shows through while it narrows
                .background(colors.background, if (floating) FloatingRailShape else RectangleShape)
                .drawWithContent {
                    drawContent()
                    // In the dark theme, an edge along its page side while it is over the page
                    if (edge != null && floating && width.value > RemDimens.RailWidth + 1.dp) {
                        val stroke = FloatingRailEdgeWidth.toPx()
                        val corner = FloatingRailCorner.toPx()
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        // The page side only: not along the window's edges at the top, bottom and start
                        clipRect(
                            left = if (rtl) 0f else size.width - corner - stroke,
                            right = if (rtl) corner + stroke else size.width
                        ) {
                            inset(stroke / 2) {
                                drawOutline(
                                    outline = FloatingRailShape.createOutline(size, layoutDirection, this),
                                    color = edge,
                                    style = Stroke(stroke)
                                )
                            }
                        }
                    }
                }
                .focusProperties {
                    onExit = {
                        if (trapsFocus) {
                            val wrapped = when (requestedFocusDirection) {
                                FocusDirection.Next -> toggleFocus.requestFocus(FocusDirection.Enter)
                                FocusDirection.Previous -> lastFocus.requestFocus(FocusDirection.Enter)
                                else -> false
                            }
                            if (!wrapped) cancelFocusChange()
                        }
                    }
                }
                .focusGroup()
                // Only looks, before anything on the rail sees the events, and takes nothing
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> touchUsed = isFingerPress(change.type, event.buttons)
                                PointerEventType.Enter, PointerEventType.Move ->
                                    if (change.type == PointerType.Mouse && !change.pressed) touchUsed = false
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (floatingOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The header, level with the toolbar: Material's menu button, which keeps its place
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RemDimens.ToolbarHeight),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(modifier = Modifier.width(RemDimens.RailWidth)) {
                    RailTile(
                        label = if (expanded) "Collapse tab rail" else "Expand tab rail",
                        selected = false,
                        highlighted = false,
                        role = Role.Button,
                        onClick = onToggleExpanded,
                        onOpenMenu = null,
                        stateDescription = if (expanded) "Expanded" else "Collapsed",
                        modifier = Modifier
                            .focusRequester(toggleFocus)
                            .testTag("tab_rail_toggle")
                    ) {
                        RailIcon(if (expanded) Icons.AutoMirrored.Outlined.MenuOpen else Icons.Outlined.Menu)
                    }
                }
            }
            if (!switcherInList) tabSwitcherButton()

            // Tabs and the new-tab button take the room the extensions leave; the new-tab button
            // follows the last tab until the list has to scroll.
            Column(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .testTag("tab_rail_list"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (switcherInList) {
                        item(key = "tab-switcher") { tabSwitcherButton() }
                    }
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-shelf") {
                            PinnedShelf(
                                pinned = pinned,
                                openPins = openPins,
                                showTitles = expanded,
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
                            showTitle = expanded,
                            touchUsed = touchUsed,
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

                RailSeparator()
                RailTile(
                    label = "New tab",
                    selected = false,
                    highlighted = false,
                    role = Role.Button,
                    onClick = onNewTab,
                    onOpenMenu = null,
                    title = "New tab",
                    showTitle = expanded,
                    titleTag = "new_tab_title",
                    modifier = Modifier
                        .testTag("new_tab_button")
                        .then(if (extensions.isEmpty()) Modifier.focusRequester(lastFocus) else Modifier)
                ) {
                    RailIcon(Icons.Outlined.Add)
                }
            }

            if (extensions.isNotEmpty()) {
                RailExtensions(
                    extensions = extensions,
                    slots = extensionSlots,
                    showTitles = expanded,
                    openPopupExtensionId = openPopupExtensionId,
                    onTrigger = onTriggerExtensionAction,
                    onManage = onManageExtensions,
                    lastFocus = lastFocus
                )
            }
        }
    }
}

/**
 * The page dimmed behind the expanded rail drawn over it, Material's scrim (stronger in the dark
 * theme, [railScrimAlpha]). A click or tap on it closes the rail ([onDismiss]), as does a screen
 * reader's "Close the tab rail" on it: the page under it is hidden from screen readers meanwhile
 * (RemBrowseScreen), so this is what they find beside the rail. Once the rail is closing it only
 * fades: a click or tap straight after reaches the page under it.
 */
@Composable
fun TabRailScrim(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(RemMotion.defaultEffects()),
        exit = fadeOut(RemMotion.fastEffects()),
        modifier = modifier
    ) {
        // Not while it fades out: the rail is already closed
        val open = transition.targetState == EnterExitState.Visible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = railScrimAlpha(MaterialTheme.colorScheme)))
                .then(
                    if (open) {
                        Modifier
                            // Taken here, so nothing under it (a page, the toolbar) is clicked through it
                            .pointerInput(Unit) { detectTapGestures { dismiss() } }
                            .semantics {
                                contentDescription = "Close the tab rail"
                                onClick {
                                    dismiss()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .testTag("tab_rail_scrim")
        )
    }
}

/**
 * Keeps the active tab's tile in view on the rail. When another tab becomes the active one (a
 * click, Ctrl+Tab, a new tab, a tab chosen in the switcher) the list glides to it, or jumps with
 * Android's animations off. When the room the list has changes under it (the window is resized,
 * extension buttons come or go, a site is pinned or unpinned), it jumps to it if it was left out
 * of sight: on a phone held sideways, five pinned sites can fill the rail on their own.
 *
 * Scrolling the rail by hand changes none of these, so a list being looked through is never
 * pulled back to the active tab.
 */
@Composable
private fun KeepActiveTabInView(
    state: LazyListState,
    activeTabId: String?,
    activeIndex: Int,
    pinnedCount: Int,
    reduceMotion: Boolean
) {
    val spec = RemMotion.defaultSpatial<Float>()
    val tileHeight = with(LocalDensity.current) { RailTileHeight.roundToPx() }
    val index by rememberUpdatedState(activeIndex)
    val glide by rememberUpdatedState(if (reduceMotion) null else spec)
    val pins by rememberUpdatedState(pinnedCount)
    // The first time is the rail being laid out, which shows it in place rather than gliding there
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state, activeTabId) {
        // A new tab is laid out after this starts
        snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() && index < state.layoutInfo.totalItemsCount }
            .first { it }
        // Scrolled on the next frame, not in the middle of the layout that was just noticed
        withFrameNanos { }
        state.revealItem(index, tileHeight, if (shown) glide else null)
        shown = true
    }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.viewportSize.height to pins }
            .distinctUntilChanged()
            .collect {
                withFrameNanos { }
                state.revealItem(index, tileHeight, null)
            }
    }
}

/**
 * Scrolls the item at [index] into full view, or as much of it as fits from its top, moving the
 * list as little as possible: an item above comes to the top edge, one below to the bottom edge.
 * With a [spec] it glides there, otherwise it jumps.
 */
private suspend fun LazyListState.revealItem(index: Int, tileHeight: Int, spec: AnimationSpec<Float>?) {
    // An item off screen below is reached by estimate (the tiles there are [tileHeight] tall),
    // then put exactly in place
    repeat(3) { attempt ->
        val layout = layoutInfo
        val visible = layout.visibleItemsInfo
        if (index !in 0 until layout.totalItemsCount || visible.isEmpty()) return
        val start = layout.viewportStartOffset
        val end = layout.viewportEndOffset
        val item = visible.firstOrNull { it.index == index }
        val (top, bottom) = when {
            item != null -> item.offset to item.offset + item.size
            index < visible.first().index -> {
                if (spec == null || attempt > 0) scrollToItem(index) else animateScrollToItem(index)
                return
            }
            else -> {
                val last = visible.last()
                val bottom = last.offset + last.size + (index - last.index) * (tileHeight + layout.mainAxisItemSpacing)
                bottom - tileHeight to bottom
            }
        }
        val delta = when {
            top < start -> top - start
            bottom > end -> minOf(bottom - end, top - start)
            else -> 0
        }
        if (delta == 0) return
        if (spec == null || attempt > 0) scrollBy(delta.toFloat()) else animateScrollBy(delta.toFloat(), spec)
        if (item != null) return
    }
}

/** The rail's dividers: 24dp on the collapsed rail, across the expanded one less 16dp each side. */
@Composable
private fun RailSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** A rail button's icon, in the secondary content colour as the toolbar's are. */
@Composable
private fun RailIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(RemDimens.IconSize)
    )
}

/**
 * Extension buttons pinned to the bottom of the rail, like a desktop browser's toolbar. When
 * there are more extensions than [slots], the last slot lists the rest.
 */
@Composable
private fun RailExtensions(
    extensions: List<InstalledExtension>,
    slots: Int,
    showTitles: Boolean,
    openPopupExtensionId: String?,
    onTrigger: (String) -> Unit,
    onManage: () -> Unit,
    /** Given to the last button, the rail's last place (see TabRail). */
    lastFocus: FocusRequester
) {
    val shown = if (extensions.size > slots) extensions.take(slots - 1) else extensions
    val overflow = extensions.drop(shown.size)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag("rail_extensions"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        RailSeparator()
        shown.forEachIndexed { index, extension ->
            RailExtensionItem(
                extension = extension,
                showTitle = showTitles,
                popupOpen = extension.id == openPopupExtensionId,
                onClick = { onTrigger(extension.id) },
                onManage = onManage,
                modifier = if (overflow.isEmpty() && index == shown.lastIndex) Modifier.focusRequester(lastFocus) else Modifier
            )
        }
        if (overflow.isNotEmpty()) {
            RailExtensionsOverflow(
                extensions = overflow,
                showsAll = shown.isEmpty(),
                showTitle = showTitles,
                popupOpen = overflow.any { it.id == openPopupExtensionId },
                onTrigger = onTrigger,
                onManage = onManage,
                modifier = Modifier.focusRequester(lastFocus)
            )
        }
    }
}

@Composable
private fun RailExtensionItem(
    extension: InstalledExtension,
    showTitle: Boolean,
    popupOpen: Boolean,
    onClick: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
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
            title = extension.name,
            showTitle = showTitle,
            titleTag = "rail_extension_title_${extension.id}",
            modifier = modifier.testTag("rail_extension_${extension.id}")
        ) {
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
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            RemMenuItem(
                text = { Text("Open ${extension.name}") },
                onClick = {
                    showMenu = false
                    onClick()
                }
            )
            RemMenuItem(
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
    showTitle: Boolean,
    popupOpen: Boolean,
    onTrigger: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val label = if (showsAll) "Extensions" else "More extensions"
    Box {
        RailTile(
            label = label,
            selected = popupOpen,
            highlighted = showMenu,
            role = Role.Button,
            onClick = { showMenu = true },
            onOpenMenu = { showMenu = true },
            title = label,
            showTitle = showTitle,
            titleTag = "rail_extensions_more_title",
            modifier = modifier.testTag("rail_extensions_more")
        ) {
            RailIcon(Icons.Outlined.Extension)
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
            RemMenuItem(
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

/**
 * One place on the rail: a 40dp tile (48dp to touch) with its [icon] in a 40dp square at its
 * start. On the collapsed rail that square is all of it. On the expanded rail the tile reaches
 * across the rail, keeping the same edge, so the icon doesn't move, and its [title] fades in
 * beside the icon ([showTitle]); the tooltip then stands aside, unless the title is cut short.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RailTile(
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    role: Role,
    onClick: () -> Unit,
    /** A right-click or a long press: the tile's menu. Null for a plain button. */
    onOpenMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
    style: RailTileStyle = RailTileStyle.Button,
    /** What the tooltip shows, when it says less than [label], the accessible name. */
    tooltip: String = label,
    /**
     * A middle click (a mouse's middle button, or a touchpad's three-finger tap): closes a tab,
     * opens a pinned site in a new tab.
     */
    onMiddleClick: (() -> Unit)? = null,
    /** What the expanded rail shows beside the icon. */
    title: String? = null,
    showTitle: Boolean = false,
    titleTag: String? = null,
    /** Said after the name by a screen reader, such as whether the rail is expanded. */
    stateDescription: String? = null,
    /**
     * At the end of the expanded tile, such as a tab's close button: shown while the pointer is
     * over the tile or the keyboard's focus is in it, and always while [trailingAlways] (the
     * rail used with a finger, which has no pointer to hover with).
     */
    trailing: (@Composable () -> Unit)? = null,
    trailingAlways: Boolean = false,
    icon: @Composable BoxScope.() -> Unit
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
    // The rail's tiles ring outside their edge, as tabs must outside their border (see
    // keyboardFocusRing); the 48dp touch target leaves the room. A pin, on the shelf, which would
    // cut such a ring, keeps Material's inset ring: it has no border of its own.
    val ringOutside = style != RailTileStyle.Pinned
    var titleCut by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focusInside by remember { mutableStateOf(false) }
    val showTrailing = trailing != null && showTitle && (trailingAlways || hovered || focusInside)
    TooltipBox(
        // Beside the rail, as on desktop, so it never covers the tile above
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
        focusable = false,
        // Not while the whole title is in sight beside the icon
        enableUserInput = !(showTitle && title != null) || titleCut
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Box(
                modifier = modifier
                    // On the shelf the pins sit in from the shelf's edges instead
                    .padding(horizontal = if (style == RailTileStyle.Pinned) PinnedShelfInset else RailTileInset)
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(RemDimens.ControlSize)
                    .then(if (ringOutside) Modifier.keyboardFocusRing(shape) else Modifier)
                    .clip(shape)
                    .background(fill)
                    .then(
                        if (style == RailTileStyle.Tab) {
                            Modifier.border(1.dp, colors.outlineVariant, shape)
                        } else {
                            Modifier
                        }
                    )
                    .then(if (onOpenMenu != null) Modifier.contextMenuTrigger(onOpenMenu) else Modifier)
                    .then(if (onMiddleClick != null) Modifier.middleClickTrigger(onMiddleClick) else Modifier)
                    .then(
                        if (trailing != null) {
                            Modifier
                                .hoverable(hover)
                                .onFocusChanged { focusInside = it.hasFocus }
                        } else {
                            Modifier
                        }
                    )
                    .combinedClickable(
                        interactionSource = null,
                        indication = if (ringOutside) ringOutsideRipple() else ripple(focusRingShape = shape),
                        role = role,
                        onClick = onClick,
                        onLongClick = onOpenMenu,
                        onLongClickLabel = if (onOpenMenu != null) "More options" else null
                    )
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                        if (stateDescription != null) this.stateDescription = stateDescription
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(RemDimens.ControlSize),
                        contentAlignment = Alignment.Center,
                        content = icon
                    )
                    if (title != null) {
                        AnimatedVisibility(
                            visible = showTitle,
                            enter = fadeIn(RemMotion.defaultEffects()),
                            exit = fadeOut(RemMotion.fastEffects()),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = title,
                                // Material's navigation rail label, as a menu row's
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { titleCut = it.hasVisualOverflow },
                                modifier = Modifier
                                    .padding(end = if (showTrailing) 0.dp else 12.dp)
                                    .then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
                            )
                        }
                    }
                    if (showTrailing) trailing?.invoke()
                }
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
    showTitle: Boolean,
    touchUsed: Boolean,
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
            title = tab.displayTitle,
            showTitle = showTitle,
            titleTag = "rail_tab_title_${tab.id}",
            // A desktop browser's vertical tabs' close button, at the end of the expanded row
            trailing = {
                RemIconButton(
                    icon = Icons.Outlined.Close,
                    label = "Close tab",
                    onClick = onClose,
                    size = 32.dp,
                    iconSize = 18.dp,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("rail_tab_close_${tab.id}")
                )
            },
            trailingAlways = touchUsed,
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
                // In place of the site's icon, about its size
                RemLoadingIndicator(modifier = Modifier.size(24.dp))
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
            RemMenuItem(
                text = { Text("Close tab") },
                trailingIcon = { ShortcutHint("Ctrl+W") },
                onClick = {
                    showMenu = false
                    onClose()
                },
                modifier = Modifier.testTag("tab_bar_close_${tab.id}")
            )
            RemMenuItem(
                text = { Text("Close other tabs") },
                onClick = {
                    showMenu = false
                    onCloseOthers()
                }
            )
            if (paneCount > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                for (pane in 0 until paneCount) {
                    RemMenuItem(
                        text = { Text("Show in pane ${pane + 1}") },
                        onClick = {
                            showMenu = false
                            onShowInPane(pane)
                        }
                    )
                }
                if (isVisibleInPane) {
                    // The pane goes, the tab stays open here in the rail
                    RemMenuItem(
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
 * open tabs. A dot on a site's icon says a tab is open at its page or below it ([openPins]). On
 * the expanded rail the shelf widens with it and each site's name shows beside its icon, the
 * round hover area stretching to a pill that still fits the shelf's round ends.
 */
@Composable
private fun PinnedShelf(
    pinned: List<BookmarkItem>,
    openPins: Set<String>,
    showTitles: Boolean,
    reduceMotion: Boolean,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val shelf = pinnedShelfColors(MaterialTheme.colorScheme)
    Surface(
        modifier = Modifier
            .padding(bottom = PinnedShelfGap)
            .padding(horizontal = PinnedShelfInset)
            .fillMaxWidth()
            .testTag("pinned_shelf"),
        // Half the shelf's collapsed width, so its ends are round and each icon's hover circle sits in them
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
                    showTitle = showTitles,
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
    showTitle: Boolean,
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
            title = bookmark.title,
            showTitle = showTitle,
            titleTag = "pinned_site_title_${bookmark.id}",
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
            RemMenuItem(
                text = { Text("Open") },
                onClick = {
                    showMenu = false
                    onOpen()
                }
            )
            RemMenuItem(
                text = { Text("Open in new tab") },
                onClick = {
                    showMenu = false
                    onOpenInNewTab()
                }
            )
            RemMenuItem(
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
