package com.rembrowser.rembrowse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver
import kotlin.math.abs

/**
 * The single toolbar row: back, reload, address pill, bookmark, more, split view.
 * On compact windows the split-view button moves into the menu and a tab counter appears.
 */
@Composable
fun BrowserTopBar(
    uiState: BrowserUiState,
    compact: Boolean,
    installedExtensions: List<InstalledExtension>,
    addressBarFocusRequester: FocusRequester,
    onAddressBarChange: (String) -> Unit,
    onAddressBarFocusChange: (Boolean) -> Unit,
    onSubmitAddressBar: () -> Unit,
    onLeaveAddressBar: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onSelectLayout: (LayoutMode) -> Unit,
    onSplit: (currentOnLeft: Boolean) -> Unit,
    onSwapPanes: () -> Unit,
    onSetSplitPreset: (Float) -> Unit,
    onClosePane: () -> Unit,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTab = uiState.activeTab
    // True for anything that is not a web page: the start page and RemBrowse's own pages
    val noWebPage = activeTab?.isWebPage != true
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLayoutMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RemDimens.ToolbarHeight)
            .padding(start = if (compact) 4.dp else 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "Back",
            onClick = onBack,
            enabled = activeTab?.canGoBack == true
        )
        // Forward only takes up room when there is somewhere to go forward to
        if (activeTab?.canGoForward == true) {
            RemIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                label = "Forward",
                onClick = onForward
            )
        }
        val isLoading = activeTab?.isLoading == true && !noWebPage
        // On a phone-width window reload and bookmark live in the menu, so the address keeps
        // enough room to be read.
        if (!compact) {
            RemIconButton(
                icon = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                label = if (isLoading) "Stop loading" else "Reload",
                onClick = onReloadOrStop,
                enabled = !noWebPage
            )
        }

        AddressPill(
            activeTab = activeTab,
            input = uiState.addressBarInput,
            isFocused = uiState.isAddressBarFocused,
            focusRequester = addressBarFocusRequester,
            onChange = onAddressBarChange,
            onFocusChange = onAddressBarFocusChange,
            onSubmit = onSubmitAddressBar,
            onLeave = onLeaveAddressBar,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )

        val bookmarked = uiState.isActiveTabBookmarked
        if (compact) {
            TabCountButton(count = uiState.tabs.size, onClick = onOpenTabSwitcher)
        } else {
            RemIconButton(
                icon = if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                onClick = onToggleBookmark,
                enabled = !noWebPage,
                tint = if (bookmarked && !noWebPage) MaterialTheme.colorScheme.primary else Color.Unspecified,
                modifier = Modifier.testTag("bookmark_button")
            )
        }

        Box {
            RemIconButton(
                icon = Icons.Outlined.MoreHoriz,
                label = "More",
                onClick = { showMoreMenu = true },
                modifier = Modifier.testTag("more_button")
            )
            MoreMenu(
                expanded = showMoreMenu,
                onDismiss = { showMoreMenu = false },
                compact = compact,
                pageActionsEnabled = !noWebPage,
                isLoading = isLoading,
                isBookmarked = bookmarked,
                onReloadOrStop = onReloadOrStop,
                onToggleBookmark = onToggleBookmark,
                desktopMode = activeTab?.desktopMode != false,
                extensions = installedExtensions.filter { it.enabled && it.hasBrowserAction },
                onNewTab = onNewTab,
                onCloseActiveTab = onCloseActiveTab,
                onOpenCommandPalette = onOpenCommandPalette,
                onOpenBookmarks = onOpenBookmarks,
                onOpenDownloads = onOpenDownloads,
                onOpenHistory = onOpenHistory,
                onOpenExtensions = onOpenExtensions,
                onOpenSettings = onOpenSettings,
                onOpenSplitView = { showLayoutMenu = true },
                onToggleDesktopMode = onToggleDesktopMode,
                onTriggerExtensionAction = onTriggerExtensionAction
            )
            if (compact) {
                SplitMenu(
                    expanded = showLayoutMenu,
                    onDismiss = { showLayoutMenu = false },
                    uiState = uiState,
                    onSelectLayout = onSelectLayout,
                    onSplit = onSplit,
                    onSwapPanes = onSwapPanes,
                    onSetSplitPreset = onSetSplitPreset,
                    onClosePane = onClosePane
                )
            }
        }

        if (!compact) {
            Box(modifier = Modifier.padding(start = 4.dp, end = 2.dp)) {
                SplitButton(
                    isSplit = uiState.isSplit,
                    onClick = { showLayoutMenu = true }
                )
                SplitMenu(
                    expanded = showLayoutMenu,
                    onDismiss = { showLayoutMenu = false },
                    uiState = uiState,
                    onSelectLayout = onSelectLayout,
                    onSplit = onSplit,
                    onSwapPanes = onSwapPanes,
                    onSetSplitPreset = onSetSplitPreset,
                    onClosePane = onClosePane
                )
            }
        }
    }
}

/** The labelled "Split" button. Filled while a split is on screen. */
@Composable
private fun SplitButton(isSplit: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val content = if (isSplit) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(shape)
            .background(if (isSplit) colors.primaryContainer else colors.surface)
            .border(1.dp, if (isSplit) colors.primary else colors.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSplit }
            .testTag("split_button")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LayoutGlyph(mode = LayoutMode.SPLIT_1X2, color = content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "Split", style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun AddressPill(
    activeTab: BrowserTab?,
    input: String,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    // Unfocused: the tidy display form. Focused: the full address, selected, ready to replace.
    // In a split this one bar always shows, and acts on, the focused pane.
    val shown = if (isFocused) input else UrlResolver.toDisplayUrl(input)
    var value by remember { mutableStateOf(TextFieldValue(shown)) }
    LaunchedEffect(isFocused) {
        value = if (isFocused) {
            TextFieldValue(input, TextRange(0, input.length))
        } else {
            TextFieldValue(shown)
        }
    }
    LaunchedEffect(shown) {
        if (value.text != shown) {
            // Unfocused, the caret goes to the start: a text field scrolls to its caret, and an
            // address too long for the bar must show its site, not the tail of its path.
            value = TextFieldValue(shown, if (isFocused) TextRange(shown.length) else TextRange.Zero)
        }
    }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) colors.primary else colors.outlineVariant,
        label = "addressBorder"
    )

    Row(
        modifier = modifier
            .height(RemDimens.ControlSize)
            .clip(CircleShape)
            .background(colors.surface)
            .border(if (isFocused) 1.5.dp else 1.dp, borderColor, CircleShape)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AddressStatusIcon(activeTab = activeTab, isEditing = isFocused)
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                value = newValue
                if (newValue.text != input) onChange(newValue.text)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    onSubmit()
                    // Focus is handed to the page, never just cleared: clearing it lets Android
                    // refocus the first button in the window, and the Enter key's release
                    // would then press that button.
                    onLeave()
                }
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("omnibox_input")
                .semantics { contentDescription = "Address and search bar" }
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        // Abandon the edit and show the current page's address again
                        onLeave()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = "Search or enter a web address",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/** Search glyph while typing; otherwise the connection state, by shape as well as colour. */
@Composable
private fun AddressStatusIcon(activeTab: BrowserTab?, isEditing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val iconModifier = Modifier.size(18.dp)
    when {
        isEditing || activeTab == null || activeTab.isNewTabPage -> Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = iconModifier
        )
        activeTab.isSecure -> Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = "Connection is secure",
            tint = colors.onSurfaceVariant,
            modifier = iconModifier
        )
        UrlResolver.isHttpUrl(activeTab.url) -> Icon(
            imageVector = Icons.Outlined.LockOpen,
            contentDescription = "Connection is not secure",
            tint = colors.error,
            modifier = iconModifier
        )
        else -> Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = iconModifier
        )
    }
}

@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = if (count == 1) "1 open tab. Show tabs" else "$count open tabs. Show tabs"
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(RemDimens.ControlSize)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("tab_switcher_button"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.5.dp, colors.onSurfaceVariant, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    compact: Boolean,
    pageActionsEnabled: Boolean,
    isLoading: Boolean,
    isBookmarked: Boolean,
    onReloadOrStop: () -> Unit,
    onToggleBookmark: () -> Unit,
    desktopMode: Boolean,
    extensions: List<InstalledExtension>,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSplitView: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit
) {
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    RemDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (compact) {
            DropdownMenuItem(
                text = { Text(if (isLoading) "Stop loading" else "Reload") },
                enabled = pageActionsEnabled,
                onClick = run(onReloadOrStop),
                modifier = Modifier.testTag("menu_reload")
            )
            DropdownMenuItem(
                text = { Text(if (isBookmarked) "Remove bookmark" else "Bookmark this page") },
                enabled = pageActionsEnabled,
                onClick = run(onToggleBookmark),
                modifier = Modifier.testTag("bookmark_button")
            )
            divider()
        }
        DropdownMenuItem(
            text = { Text("New tab") },
            trailingIcon = { ShortcutHint("Ctrl+T") },
            onClick = run(onNewTab),
            modifier = Modifier.testTag("menu_new_tab")
        )
        DropdownMenuItem(
            text = { Text("Close tab") },
            trailingIcon = { ShortcutHint("Ctrl+W") },
            onClick = run(onCloseActiveTab)
        )
        DropdownMenuItem(
            text = { Text("Command palette") },
            trailingIcon = { ShortcutHint("Ctrl+K") },
            onClick = run(onOpenCommandPalette),
            modifier = Modifier.testTag("menu_command_palette")
        )
        if (compact) {
            DropdownMenuItem(
                text = { Text("Split view") },
                onClick = run(onOpenSplitView),
                modifier = Modifier.testTag("split_button")
            )
        }
        divider()
        DropdownMenuItem(
            text = { Text("Bookmarks") },
            onClick = run(onOpenBookmarks),
            modifier = Modifier.testTag("menu_bookmarks")
        )
        DropdownMenuItem(
            text = { Text("History") },
            trailingIcon = { ShortcutHint("Ctrl+H") },
            onClick = run(onOpenHistory),
            modifier = Modifier.testTag("menu_history")
        )
        DropdownMenuItem(
            text = { Text("Downloads") },
            trailingIcon = { ShortcutHint("Ctrl+J") },
            onClick = run(onOpenDownloads),
            modifier = Modifier.testTag("menu_downloads")
        )
        DropdownMenuItem(
            text = { Text("Extensions") },
            onClick = run(onOpenExtensions),
            modifier = Modifier.testTag("menu_extensions")
        )
        // Toolbar buttons of enabled extensions, e.g. a content blocker's popup. Wider windows
        // show them at the bottom of the tab rail instead.
        if (compact) {
            extensions.forEach { extension ->
                ExtensionMenuItem(
                    extension = extension,
                    onClick = run { onTriggerExtensionAction(extension.id) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        divider()
        DropdownMenuItem(
            text = { Text("Desktop site") },
            trailingIcon = {
                if (desktopMode) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "On",
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            onClick = run(onToggleDesktopMode)
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            trailingIcon = { ShortcutHint("Ctrl+,") },
            onClick = run(onOpenSettings),
            modifier = Modifier.testTag("menu_settings")
        )
    }
}

/** Preset sizes for the first pane of a two-pane split. */
private val SPLIT_PRESETS = listOf(0.5f to "50 / 50", 0.7f to "70 / 30")

/** [LayoutMode.SPLIT_1_2] in the Split menu. */
private const val ONE_AND_TWO_LABEL = "One left, two right"

/**
 * Everything about the split in one menu, opened from the Split button beside More.
 *
 * With one page on screen it offers the ways to split. While a split is open it also holds the
 * controls that act on the split as a whole (swap, size, close), so the panes need no row of
 * controls above them and keep the whole height of the window.
 */
@Composable
private fun SplitMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    uiState: BrowserUiState,
    onSelectLayout: (LayoutMode) -> Unit,
    onSplit: (currentOnLeft: Boolean) -> Unit,
    onSwapPanes: () -> Unit,
    onSetSplitPreset: (Float) -> Unit,
    onClosePane: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    @Composable
    fun item(
        label: String,
        tag: String,
        selected: Boolean = false,
        shortcut: String? = null,
        leading: @Composable (tint: Color) -> Unit,
        onClick: () -> Unit
    ) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { leading(if (selected) colors.primary else colors.onSurfaceVariant) },
            trailingIcon = {
                // The current choice is ticked, so it is not told apart by colour alone
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else if (shortcut != null) {
                    ShortcutHint(shortcut)
                }
            },
            onClick = {
                onDismiss()
                onClick()
            },
            modifier = Modifier
                .testTag(tag)
                .semantics { this.selected = selected }
        )
    }

    @Composable
    fun layoutItem(label: String, mode: LayoutMode, tag: String, shortcut: String? = null, onClick: () -> Unit) {
        item(
            label = label,
            tag = tag,
            selected = uiState.isSplit && uiState.layoutMode == mode,
            shortcut = shortcut,
            leading = { tint -> LayoutGlyph(mode = mode, color = tint) },
            onClick = onClick
        )
    }

    @Composable
    fun sectionLabel(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                .semantics { heading() }
        )
    }

    val mode = uiState.layoutMode
    RemDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!uiState.isSplit) {
            layoutItem("Split right", LayoutMode.SPLIT_1X2, "layout_1x2", "Ctrl+2") { onSplit(true) }
            layoutItem("Split left", LayoutMode.SPLIT_1X2, "split_left") { onSplit(false) }
            HorizontalDivider(color = colors.outlineVariant)
            layoutItem("Stacked", LayoutMode.SPLIT_2X1, "layout_2x1", "Ctrl+3") { onSelectLayout(LayoutMode.SPLIT_2X1) }
            layoutItem("Grid", LayoutMode.GRID_2X2, "layout_2x2", "Ctrl+4") { onSelectLayout(LayoutMode.GRID_2X2) }
            layoutItem(ONE_AND_TWO_LABEL, LayoutMode.SPLIT_1_2, "layout_1_2", "Ctrl+5") { onSelectLayout(LayoutMode.SPLIT_1_2) }
        } else {
        item(
            label = "Swap panes",
            tag = "split_swap",
            leading = { tint ->
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            },
            onClick = onSwapPanes
        )

        // One divider to size against: the presets only mean something with two panes
        if (mode != null && uiState.paneCount == 2) {
            HorizontalDivider(color = colors.outlineVariant)
            sectionLabel("Size")
            val current = if (mode == LayoutMode.SPLIT_2X1) uiState.splitRatios.vertical else uiState.splitRatios.horizontal
            SPLIT_PRESETS.forEach { (fraction, label) ->
                item(
                    label = label,
                    tag = "split_preset_${(fraction * 100).toInt()}",
                    selected = abs(current - fraction) < 0.01f,
                    leading = { tint -> LayoutGlyph(mode = mode, color = tint, firstPaneFraction = fraction) },
                    onClick = { onSetSplitPreset(fraction) }
                )
            }
        }

        HorizontalDivider(color = colors.outlineVariant)
        sectionLabel("Layout")
        // Changes the arrangement only; which page sits where is left to Swap panes
        layoutItem("Side by side", LayoutMode.SPLIT_1X2, "layout_1x2") { onSelectLayout(LayoutMode.SPLIT_1X2) }
        layoutItem("Stacked", LayoutMode.SPLIT_2X1, "layout_2x1", "Ctrl+3") { onSelectLayout(LayoutMode.SPLIT_2X1) }
        layoutItem("Grid", LayoutMode.GRID_2X2, "layout_2x2", "Ctrl+4") { onSelectLayout(LayoutMode.GRID_2X2) }
        layoutItem(ONE_AND_TWO_LABEL, LayoutMode.SPLIT_1_2, "layout_1_2", "Ctrl+5") { onSelectLayout(LayoutMode.SPLIT_1_2) }

        HorizontalDivider(color = colors.outlineVariant)
        // The focused pane goes and its neighbour takes the room; its tab stays in the rail
        item(
            label = "Close this pane",
            tag = "split_close_pane",
            leading = { tint ->
                Icon(Icons.Outlined.Remove, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            },
            onClick = onClosePane
        )
        // The focused page stays, full size; the other tabs remain open in the rail
        item(
            label = "Close split",
            tag = "layout_1x1",
            shortcut = "Ctrl+\\",
            leading = { tint ->
                Icon(Icons.Outlined.Close, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            },
            onClick = { onSelectLayout(LayoutMode.SINGLE_1X1) }
        )
        }
    }
}
