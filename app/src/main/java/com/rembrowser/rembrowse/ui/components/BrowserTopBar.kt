package com.rembrowser.rembrowse.ui.components

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    onFindInPage: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPrint: () -> Unit = {},
    /** Puts the screenshot overlay up (Ctrl+Alt+S). */
    onTakeScreenshot: () -> Unit = {},
    onNewTerminal: () -> Unit = {},
    /** Opens the system's Keyboard Shortcuts sheet; offered in More only with a keyboard attached. */
    onShowKeyboardShortcuts: () -> Unit = {},
    /** What the unfocused address bar shows instead of the address (a terminal's `ssh user@host`). */
    addressDisplay: String? = null
) {
    val activeTab = uiState.activeTab
    // True for anything that is not a web page: the start page and RemBrowse's own pages
    val noWebPage = activeTab?.isWebPage != true
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLayoutMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) RemDimens.CompactToolbarHeight else RemDimens.ToolbarHeight)
            .testTag("toolbar")
            .padding(start = if (compact) 4.dp else 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "Back",
            onClick = onBack,
            // Also for a tab a link opened: Back closes it and shows the page it came from
            enabled = uiState.canNavigateBack(activeTab)
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
            displayOverride = addressDisplay,
            height = if (compact) RemDimens.CompactControlSize else RemDimens.ControlSize,
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
                onNewTerminal = onNewTerminal,
                onCloseActiveTab = onCloseActiveTab,
                onOpenCommandPalette = onOpenCommandPalette,
                onOpenBookmarks = onOpenBookmarks,
                onOpenDownloads = onOpenDownloads,
                onOpenHistory = onOpenHistory,
                onFindInPage = onFindInPage,
                onPrint = onPrint,
                onTakeScreenshot = onTakeScreenshot,
                onOpenExtensions = onOpenExtensions,
                onOpenSettings = onOpenSettings,
                onOpenSplitView = { showLayoutMenu = true },
                onToggleDesktopMode = onToggleDesktopMode,
                onTriggerExtensionAction = onTriggerExtensionAction,
                onShowKeyboardShortcuts = onShowKeyboardShortcuts
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
            // Outside its border, which is the accent while a split is open (see keyboardFocusRing)
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(if (isSplit) colors.primaryContainer else colors.surface)
            .border(1.dp, if (isSplit) colors.primary else colors.outlineVariant, shape)
            .clickable(interactionSource = null, indication = ringOutsideRipple(), role = Role.Button, onClick = onClick)
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
    modifier: Modifier = Modifier,
    displayOverride: String? = null,
    height: Dp = RemDimens.ControlSize
) {
    val colors = MaterialTheme.colorScheme

    // The replacement, LocalClipboard, is suspend-only; moving to it is a change of its own
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current

    // Unfocused: the tidy display form. Focused: the full address, selected, ready to replace.
    // In a split this one bar always shows, and acts on, the focused pane.
    val shown = if (isFocused) input else displayOverride ?: UrlResolver.toDisplayUrl(input)
    var value by remember { mutableStateOf(TextFieldValue(shown)) }
    val presses = remember { AddressFieldPresses() }
    // The right-click menu, open at this spot in the field
    var menuAt by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            value = TextFieldValue(shown)
            menuAt = null
        }
    }
    LaunchedEffect(shown) {
        if (value.text != shown) {
            // Unfocused, the caret goes to the start: a text field scrolls to its caret, and an
            // address too long for the bar must show its site, not the tail of its path. Focused
            // (the short form giving way to the full address), the selection stays.
            value = if (isFocused) value.withTextKeepingSelection(shown) else TextFieldValue(shown, TextRange.Zero)
        }
    }

    // Focus selects the whole address, as in desktop browsers, but only once the press that gave
    // it focus is over and only if that press did not select something by dragging. A frame later,
    // because the text field places its caret for a click after focus has arrived.
    fun selectAllIfPending() {
        scope.launch {
            withFrameNanos { }
            if (presses.selectAllPending && !presses.pressed) {
                presses.selectAllPending = false
                if (value.selection.collapsed) value = value.copy(selection = TextRange(0, value.text.length))
            }
        }
    }

    fun edit(next: TextFieldValue) {
        value = next
        if (next.text != input) onChange(next.text)
    }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) colors.primary else colors.outlineVariant,
        label = "addressBorder"
    )

    Row(
        modifier = modifier
            .height(height)
            .testTag("address_pill")
            .clip(CircleShape)
            .background(colors.surface)
            .border(if (isFocused) 1.5.dp else 1.dp, borderColor, CircleShape)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AddressStatusIcon(activeTab = activeTab, isEditing = isFocused)
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // The TextFieldState BasicTextField replaces this one; moving to it is a change of its own
            @Suppress("DEPRECATION")
            BasicTextField(
                value = value,
                onValueChange = { newValue ->
                    var next = newValue
                    if (newValue.selection.collapsed) {
                        presses.lastCaret = newValue.selection.start
                    } else if (newValue.text == value.text && presses.isDoubleClickWord(newValue.selection)) {
                        // A double click: stop the word at the address's punctuation
                        presses.wordNarrowed = true
                        addressWordAt(newValue.text, presses.lastCaret!!)?.let { next = newValue.copy(selection = it) }
                    }
                    edit(next)
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
                    .fillMaxWidth()
                    .testTag("omnibox_input")
                    .semantics { contentDescription = "Address and search bar" }
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused && !presses.focused) {
                            presses.selectAllPending = true
                            if (!presses.pressed) selectAllIfPending()
                        } else if (!state.isFocused) {
                            presses.selectAllPending = false
                        }
                        presses.focused = state.isFocused
                        onFocusChange(state.isFocused)
                    }
                    // Watches presses before the text field handles them, without taking them,
                    // except a right click, which opens the field's own menu
                    .pointerInput(Unit) {
                        val clickSlop = with(density) { DoubleClickSlop.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (currentEvent.buttons.isSecondaryPressed) {
                                down.consume()
                                if (!presses.focused) focusRequester.requestFocus()
                                menuAt = down.position
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                            presses.press(down.uptimeMillis, down.position, viewConfiguration.doubleTapTimeoutMillis, clickSlop)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    presses.move(change.position, viewConfiguration.touchSlop)
                                    if (!change.pressed) break
                                }
                            } finally {
                                presses.release()
                                if (presses.selectAllPending) selectAllIfPending()
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (menuAt != null && event.type == KeyEventType.KeyDown) {
                            // Typing, or Esc, closes the menu first
                            menuAt = null
                            if (event.key == Key.Escape) return@onPreviewKeyEvent true
                        }
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

            menuAt?.let { at ->
                AddressFieldMenu(
                    at = at,
                    hasSelection = value.selection.length > 0,
                    hasText = value.text.isNotEmpty(),
                    canPaste = clipboard.hasText(),
                    onCut = {
                        clipboard.setText(AnnotatedString(value.selectedText()))
                        edit(value.replaceSelection(""))
                    },
                    onCopy = { clipboard.setText(AnnotatedString(value.selectedText())) },
                    onPaste = {
                        val pasted = pastedOnOneLine(clipboard.getText()?.text.orEmpty())
                        if (pasted.isNotEmpty()) edit(value.replaceSelection(pasted))
                    },
                    onPasteAndGo = {
                        val pasted = pastedOnOneLine(clipboard.getText()?.text.orEmpty())
                        if (pasted.isNotEmpty()) {
                            edit(TextFieldValue(pasted, TextRange(pasted.length)))
                            onSubmit()
                            onLeave()
                        }
                    },
                    onSelectAll = { value = value.copy(selection = TextRange(0, value.text.length)) },
                    onDismiss = { menuAt = null }
                )
            }
        }
    }
}

/** How far apart two clicks may be and still be a double click; Compose's own figure for text. */
private val DoubleClickSlop = 50.dp

/**
 * The address bar's right-click menu, opened at [at] in the field. It does not take focus, so the
 * field keeps its text, selection and caret while it is open.
 */
@Composable
private fun AddressFieldMenu(
    at: Offset,
    hasSelection: Boolean,
    hasText: Boolean,
    canPaste: Boolean,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onPasteAndGo: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    Box(
        modifier = Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(0.dp)
    ) {
        RemDropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = DpOffset.Zero,
            properties = PopupProperties(focusable = false),
            modifier = Modifier.testTag("address_menu")
        ) {
            RemMenuItem(
                text = { Text("Cut") },
                trailingIcon = { ShortcutHint("Ctrl+X") },
                enabled = hasSelection,
                onClick = run(onCut),
                modifier = Modifier.testTag("address_menu_cut")
            )
            RemMenuItem(
                text = { Text("Copy") },
                trailingIcon = { ShortcutHint("Ctrl+C") },
                enabled = hasSelection,
                onClick = run(onCopy),
                modifier = Modifier.testTag("address_menu_copy")
            )
            RemMenuItem(
                text = { Text("Paste") },
                trailingIcon = { ShortcutHint("Ctrl+V") },
                enabled = canPaste,
                onClick = run(onPaste),
                modifier = Modifier.testTag("address_menu_paste")
            )
            RemMenuItem(
                text = { Text("Paste and go") },
                enabled = canPaste,
                onClick = run(onPasteAndGo),
                modifier = Modifier.testTag("address_menu_paste_and_go")
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RemMenuItem(
                text = { Text("Select all") },
                trailingIcon = { ShortcutHint("Ctrl+A") },
                enabled = hasText,
                onClick = run(onSelectAll),
                modifier = Modifier.testTag("address_menu_select_all")
            )
        }
    }
}

/** Search glyph while typing; otherwise the connection state, by shape as well as colour. */
@Composable
private fun AddressStatusIcon(activeTab: BrowserTab?, isEditing: Boolean) {
    val colors = MaterialTheme.colorScheme
    val iconModifier = Modifier.size(18.dp)
    when {
        !isEditing && activeTab?.isTerminal == true -> Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = "Terminal",
            tint = colors.primary,
            modifier = iconModifier
        )
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
            .clickable(
                interactionSource = null,
                indication = ripple(focusRingShape = MaterialTheme.shapes.medium),
                role = Role.Button,
                onClick = onClick
            )
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
                style = glyphStyle(MaterialTheme.typography.labelSmall),
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
    onNewTerminal: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onFindInPage: () -> Unit,
    onPrint: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSplitView: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit,
    onShowKeyboardShortcuts: () -> Unit
) {
    // Only worth offering when there is a keyboard to press the shortcuts on
    val hasKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
    // With very large text the hints would squeeze the names until a word breaks in two
    // ("Take screensho-t"); the names come first, and Keyboard shortcuts still lists the keys
    val showHints = LocalDensity.current.fontScale < 1.5f
    fun hint(keys: String): (@Composable () -> Unit)? = if (showHints) { { ShortcutHint(keys) } } else null
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    RemDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (compact) {
            RemMenuItem(
                text = { Text(if (isLoading) "Stop loading" else "Reload") },
                leadingIcon = { MenuIcon(if (isLoading) Icons.Outlined.Close else Icons.Outlined.Refresh) },
                enabled = pageActionsEnabled,
                onClick = run(onReloadOrStop),
                modifier = Modifier.testTag("menu_reload")
            )
            RemMenuItem(
                text = { Text(if (isBookmarked) "Remove bookmark" else "Bookmark this page") },
                leadingIcon = { MenuIcon(if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder) },
                enabled = pageActionsEnabled,
                onClick = run(onToggleBookmark),
                modifier = Modifier.testTag("bookmark_button")
            )
            divider()
        }
        RemMenuItem(
            text = { Text("New tab") },
            leadingIcon = { MenuIcon(Icons.Outlined.Add) },
            trailingIcon = hint("Ctrl+T"),
            onClick = run(onNewTab),
            modifier = Modifier.testTag("menu_new_tab")
        )
        RemMenuItem(
            text = { Text("New terminal") },
            leadingIcon = { MenuIcon(Icons.Outlined.Terminal) },
            trailingIcon = hint("Ctrl+Shift+T"),
            onClick = run(onNewTerminal),
            modifier = Modifier.testTag("menu_new_terminal")
        )
        RemMenuItem(
            text = { Text("Close tab") },
            leadingIcon = { MenuIcon(Icons.Outlined.Close) },
            trailingIcon = hint("Ctrl+W"),
            onClick = run(onCloseActiveTab)
        )
        RemMenuItem(
            text = { Text("Command palette") },
            leadingIcon = { MenuIcon(Icons.AutoMirrored.Outlined.ManageSearch) },
            trailingIcon = hint("Ctrl+K"),
            onClick = run(onOpenCommandPalette),
            modifier = Modifier.testTag("menu_command_palette")
        )
        RemMenuItem(
            text = { Text("Find in page") },
            leadingIcon = { MenuIcon(Icons.Outlined.FindInPage) },
            trailingIcon = hint("Ctrl+F"),
            enabled = pageActionsEnabled,
            onClick = run(onFindInPage),
            modifier = Modifier.testTag("menu_find_in_page")
        )
        RemMenuItem(
            text = { Text("Print…") },
            leadingIcon = { MenuIcon(Icons.Outlined.Print) },
            trailingIcon = hint("Ctrl+P"),
            enabled = pageActionsEnabled,
            onClick = run(onPrint),
            modifier = Modifier.testTag("menu_print")
        )
        // Anything in the window can be taken, so it is never greyed out
        RemMenuItem(
            text = { Text("Take screenshot") },
            leadingIcon = { MenuIcon(Icons.Outlined.ScreenshotMonitor) },
            trailingIcon = hint("Ctrl+Alt+S"),
            onClick = run(onTakeScreenshot),
            modifier = Modifier.testTag("menu_screenshot")
        )
        if (compact) {
            RemMenuItem(
                text = { Text("Split view") },
                leadingIcon = { MenuIcon(Icons.AutoMirrored.Outlined.ViewSidebar) },
                onClick = run(onOpenSplitView),
                modifier = Modifier.testTag("split_button")
            )
        }
        divider()
        RemMenuItem(
            text = { Text("Bookmarks") },
            leadingIcon = { MenuIcon(Icons.Outlined.StarBorder) },
            onClick = run(onOpenBookmarks),
            modifier = Modifier.testTag("menu_bookmarks")
        )
        RemMenuItem(
            text = { Text("History") },
            leadingIcon = { MenuIcon(Icons.Outlined.History) },
            trailingIcon = hint("Ctrl+H"),
            onClick = run(onOpenHistory),
            modifier = Modifier.testTag("menu_history")
        )
        RemMenuItem(
            text = { Text("Downloads") },
            leadingIcon = { MenuIcon(Icons.Outlined.FileDownload) },
            trailingIcon = hint("Ctrl+J"),
            onClick = run(onOpenDownloads),
            modifier = Modifier.testTag("menu_downloads")
        )
        RemMenuItem(
            text = { Text("Extensions") },
            leadingIcon = { MenuIcon(Icons.Outlined.Extension) },
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
        // On or off for this tab: ticked and filled while on
        RemCheckableMenuItem(
            text = { Text("Desktop site") },
            checked = desktopMode,
            onCheckedChange = { run(onToggleDesktopMode)() },
            leadingIcon = { MenuIcon(Icons.Outlined.DesktopWindows) },
            modifier = Modifier.testTag("menu_desktop_site")
        )
        if (hasKeyboard) {
            RemMenuItem(
                text = { Text("Keyboard shortcuts") },
                leadingIcon = { MenuIcon(Icons.Outlined.Keyboard) },
                onClick = run(onShowKeyboardShortcuts),
                modifier = Modifier.testTag("menu_keyboard_shortcuts")
            )
        }
        RemMenuItem(
            text = { Text("Settings") },
            leadingIcon = { MenuIcon(Icons.Outlined.Settings) },
            trailingIcon = hint("Ctrl+,"),
            onClick = run(onOpenSettings),
            modifier = Modifier.testTag("menu_settings")
        )
    }
}

/**
 * A More menu row's icon: the one the command palette shows for the same command, at the
 * chrome's icon size, in the menu's own colour for it (onSurfaceVariant, dimmed when the row is
 * off). The row's text names it, so the icon is not read out.
 */
@Composable
private fun MenuIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(RemDimens.IconSize))
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

    /**
     * A row; [selected] is null for an action, and true or false for one of a set of choices
     * (the split's layout and size), which Material draws as a selectable menu item: the current
     * one filled and ticked, so it is not told apart by colour alone.
     */
    @Composable
    fun item(
        label: String,
        tag: String,
        selected: Boolean? = null,
        shortcut: String? = null,
        leading: @Composable (tint: Color) -> Unit,
        onClick: () -> Unit
    ) {
        val run = {
            onDismiss()
            onClick()
        }
        val hint: (@Composable () -> Unit)? = shortcut?.let { keys -> { ShortcutHint(keys) } }
        if (selected != null) {
            RemSelectableMenuItem(
                text = { Text(label) },
                selected = selected,
                onClick = run,
                leadingIcon = { leading(LocalContentColor.current) },
                trailingIcon = hint,
                modifier = Modifier.testTag(tag)
            )
        } else {
            RemMenuItem(
                text = { Text(label) },
                onClick = run,
                leadingIcon = { leading(LocalContentColor.current) },
                trailingIcon = hint,
                modifier = Modifier.testTag(tag)
            )
        }
    }

    /** A layout: one of the choices while a split is open, a way to start one before. */
    @Composable
    fun layoutItem(label: String, mode: LayoutMode, tag: String, shortcut: String? = null, onClick: () -> Unit) {
        item(
            label = label,
            tag = tag,
            selected = if (uiState.isSplit) uiState.layoutMode == mode else null,
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
            // In line with the rows' icons: 4dp inset and 12dp padding (RemMenuItem)
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
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
