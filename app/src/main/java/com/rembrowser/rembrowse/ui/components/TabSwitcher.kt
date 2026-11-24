package com.rembrowser.rembrowse.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.ui.theme.RemDimens

/**
 * Grid overview of every open tab with search. Covers the content area; the toolbar and rail
 * stay in place so the user keeps their bearings.
 */
@Composable
fun TabSwitcher(
    uiState: BrowserUiState,
    framed: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Close other tabs" in a card's menu. */
    onCloseOtherTabs: (String) -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
    /** Raised to put the keyboard back in the search field (after the screenshot overlay took it). */
    searchFocusRequests: Int = 0
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { onSearchFocusChange(false) }
    }
    val tabs = remember(uiState.tabs, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            uiState.tabs
        } else {
            uiState.tabs.filter {
                it.displayTitle.contains(needle, ignoreCase = true) || it.url.contains(needle, ignoreCase = true)
            }
        }
    }
    val searchFocus = remember { FocusRequester() }
    val hasKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
    LaunchedEffect(Unit) {
        // With a hardware keyboard, typing should filter straight away. On touch devices
        // focusing would raise the on-screen keyboard over the grid, so the user opts in.
        if (hasKeyboard) runCatching { searchFocus.requestFocus() }
    }
    // Only requests made while the switcher is open: an older one must not raise the on-screen keyboard
    val requestsAtOpen = remember { searchFocusRequests }
    LaunchedEffect(searchFocusRequests) {
        if (searchFocusRequests != requestsAtOpen) {
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }

    val shape = if (framed) RoundedCornerShape(RemDimens.PaneRadius) else RoundedCornerShape(0.dp)
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("tab_switcher"),
        shape = shape,
        color = colors.surfaceContainerLowest,
        contentColor = colors.onSurface,
        border = if (framed) BorderStroke(1.dp, colors.outlineVariant) else null
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = { tabs.firstOrNull()?.let { onSelectTab(it.id) } },
                    onEscape = onDismiss,
                    onFocusChange = onSearchFocusChange,
                    focusRequester = searchFocus,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onNewTab,
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier.testTag("switcher_new_tab")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New tab")
                }
                RemIconButton(
                    icon = Icons.Outlined.Close,
                    label = "Close tab switcher",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("switcher_close")
                )
            }

            Text(
                text = when {
                    query.isNotBlank() && tabs.isEmpty() -> "No open tabs match “${query.trim()}”"
                    uiState.tabs.size == 1 -> "1 open tab"
                    else -> "${uiState.tabs.size} open tabs"
                },
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .semantics { heading() }
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Room for a card's keyboard focus ring, which the grid would otherwise cut
                contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tabs, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        isActive = tab.id == uiState.activeTabId,
                        onSelect = { onSelectTab(tab.id) },
                        onClose = { onCloseTab(tab.id) },
                        onCloseOthers = { onCloseOtherTabs(tab.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onEscape: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(RemDimens.ControlSize)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        // The TextFieldState BasicTextField replaces this one; moving to it is a change of its own
        @Suppress("DEPRECATION")
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .testTag("switcher_search")
                .semantics { contentDescription = "Search tabs" }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onEscape()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search tabs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * A tab in the switcher. A click shows it; a right-click or a long press opens its menu (Close
 * tab, Close other tabs, as on the rail) and a middle click closes it.
 */
@Composable
private fun TabCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        TabCardContent(
            tab = tab,
            isActive = isActive,
            onSelect = onSelect,
            onClose = onClose,
            onOpenMenu = { showMenu = true }
        )
        RemDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Close tab") },
                trailingIcon = { ShortcutHint("Ctrl+W") },
                onClick = {
                    showMenu = false
                    onClose()
                },
                modifier = Modifier.testTag("switcher_menu_close_${tab.id}")
            )
            DropdownMenuItem(
                text = { Text("Close other tabs") },
                onClick = {
                    showMenu = false
                    onCloseOthers()
                },
                modifier = Modifier.testTag("switcher_menu_close_others_${tab.id}")
            )
        }
    }
}

@Composable
private fun TabCardContent(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(colors.surface)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) colors.primary else colors.outlineVariant,
                shape = shape
            )
            .contextMenuTrigger(onOpenMenu)
            .middleClickTrigger(onClose)
            .touchLongPress("More options", onOpenMenu)
            .clickable(role = Role.Tab, onClick = onSelect)
            .semantics { selected = isActive }
            .testTag("switcher_tab_${tab.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(if (isActive) colors.primaryContainer else colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            SiteIcon(
                url = tab.url,
                title = tab.displayTitle,
                size = 36.dp,
                isNewTab = tab.isNewTabPage,
                tint = if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.displayTitle,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Close ${tab.displayTitle}",
                onClick = onClose,
                size = 32.dp,
                iconSize = 16.dp,
                modifier = Modifier.testTag("switcher_close_${tab.id}")
            )
        }
    }
}
