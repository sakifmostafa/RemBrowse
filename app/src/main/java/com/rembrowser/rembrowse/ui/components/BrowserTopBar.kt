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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onSelectLayout: (LayoutMode) -> Unit,
    onSwapPanes: (Int, Int) -> Unit,
    onResetSplit: () -> Unit,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTab = uiState.activeTab
    val isNewTabPage = activeTab?.isNewTabPage != false
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
        val isLoading = activeTab?.isLoading == true && !isNewTabPage
        // On a phone-width window reload and bookmark live in the menu, so the address keeps
        // enough room to be read.
        if (!compact) {
            RemIconButton(
                icon = if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh,
                label = if (isLoading) "Stop loading" else "Reload",
                onClick = onReloadOrStop,
                enabled = !isNewTabPage
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
                enabled = !isNewTabPage,
                tint = if (bookmarked && !isNewTabPage) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
                pageActionsEnabled = !isNewTabPage,
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
                onOpenExtensions = onOpenExtensions,
                onOpenSettings = onOpenSettings,
                onOpenSplitView = { showLayoutMenu = true },
                onToggleDesktopMode = onToggleDesktopMode,
                onTriggerExtensionAction = onTriggerExtensionAction
            )
            if (compact) {
                LayoutMenu(
                    expanded = showLayoutMenu,
                    onDismiss = { showLayoutMenu = false },
                    uiState = uiState,
                    onSelectLayout = onSelectLayout,
                    onSwapPanes = onSwapPanes,
                    onResetSplit = onResetSplit
                )
            }
        }

        if (!compact) {
            Box {
                RemIconButton(
                    icon = Icons.AutoMirrored.Outlined.ViewSidebar,
                    label = "Split view",
                    onClick = { showLayoutMenu = true },
                    selected = uiState.layoutMode != LayoutMode.SINGLE_1X1,
                    modifier = Modifier.testTag("split_button")
                )
                LayoutMenu(
                    expanded = showLayoutMenu,
                    onDismiss = { showLayoutMenu = false },
                    uiState = uiState,
                    onSelectLayout = onSelectLayout,
                    onSwapPanes = onSwapPanes,
                    onResetSplit = onResetSplit
                )
            }
        }
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
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current

    // Unfocused: the tidy display form. Focused: the full address, selected, ready to replace.
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
            value = TextFieldValue(shown, TextRange(shown.length))
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
                    focusManager.clearFocus()
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
                        focusManager.clearFocus()
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
            text = { Text("Extensions") },
            onClick = run(onOpenExtensions),
            modifier = Modifier.testTag("menu_extensions")
        )
        // Toolbar buttons of enabled extensions, e.g. a content blocker's popup
        extensions.forEach { extension ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = extension.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp)
                    )
                },
                trailingIcon = {
                    val badge = extension.badgeText
                    if (!badge.isNullOrBlank()) ShortcutHint(badge)
                },
                onClick = run { onTriggerExtensionAction(extension.id) },
                modifier = Modifier.padding(start = 12.dp)
            )
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

@Composable
private fun LayoutMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    uiState: BrowserUiState,
    onSelectLayout: (LayoutMode) -> Unit,
    onSwapPanes: (Int, Int) -> Unit,
    onResetSplit: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    RemDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        LayoutMode.entries.forEachIndexed { index, mode ->
            val selected = uiState.layoutMode == mode
            DropdownMenuItem(
                text = { Text(mode.description) },
                leadingIcon = {
                    LayoutGlyph(mode = mode, color = if (selected) colors.primary else colors.onSurfaceVariant)
                },
                trailingIcon = {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        ShortcutHint("Ctrl+${index + 1}")
                    }
                },
                onClick = {
                    onDismiss()
                    onSelectLayout(mode)
                },
                modifier = Modifier
                    .testTag("layout_${mode.label}")
                    .semantics { this.selected = selected }
            )
        }
        val paneCount = uiState.layoutMode.paneCount
        if (paneCount > 1) {
            HorizontalDivider(color = colors.outlineVariant)
            val active = uiState.activePaneIndex
            for (other in 0 until paneCount) {
                if (other == active) continue
                DropdownMenuItem(
                    text = { Text("Swap with pane ${other + 1}") },
                    onClick = {
                        onDismiss()
                        onSwapPanes(active, other)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Reset split") },
                onClick = {
                    onDismiss()
                    onResetSplit()
                }
            )
        }
    }
}
