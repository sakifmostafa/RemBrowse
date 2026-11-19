package com.rembrowser.rembrowse.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.components.BookmarksDialog
import com.rembrowser.rembrowse.ui.components.BrowserTopBar
import com.rembrowser.rembrowse.ui.components.CommandPalette
import com.rembrowser.rembrowse.ui.components.ExtensionInstallPromptDialog
import com.rembrowser.rembrowse.ui.components.ExtensionPopupDialog
import com.rembrowser.rembrowse.ui.components.ExtensionsManagerDialog
import com.rembrowser.rembrowse.ui.components.PagePromptDialog
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.SettingsDialog
import com.rembrowser.rembrowse.ui.components.TabRail
import com.rembrowser.rembrowse.ui.components.TabSwitcher
import com.rembrowser.rembrowse.ui.components.TiledBrowserGrid
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel

/**
 * Which overlays of the browser chrome are open. Lives outside the composition so hardware
 * keyboard shortcuts handled by the activity can open them too.
 */
@Stable
class BrowserChromeState {
    var showTabSwitcher by mutableStateOf(false)
    var showCommandPalette by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showExtensions by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)

    val addressBarFocusRequester = FocusRequester()

    internal var addressFocusRequests by mutableIntStateOf(0)
        private set

    /** Moves keyboard focus to the address bar (and selects its text) on the next frame. */
    fun focusAddressBar() {
        showTabSwitcher = false
        addressFocusRequests++
    }
}

@Composable
fun RemBrowseScreen(
    browserViewModel: BrowserViewModel,
    extensionsViewModel: ExtensionsViewModel,
    chrome: BrowserChromeState = remember { BrowserChromeState() },
    versionName: String = "",
    onPickLocalXpi: () -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    extensionPopupContent: @Composable (Modifier) -> Unit
) {
    val browserState by browserViewModel.uiState.collectAsStateWithLifecycle()
    val extensionsState by extensionsViewModel.uiState.collectAsStateWithLifecycle()
    val hasKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY

    // Waiting a frame lets a closing dialog (the command palette) hand window focus back first
    LaunchedEffect(chrome.addressFocusRequests) {
        if (chrome.addressFocusRequests > 0) {
            withFrameNanos { }
            runCatching { chrome.addressBarFocusRequester.requestFocus() }
        }
    }

    BackHandler(enabled = !chrome.showTabSwitcher && browserState.activeTab?.canGoBack == true) {
        browserViewModel.goBack()
    }
    BackHandler(enabled = chrome.showTabSwitcher) {
        chrome.showTabSwitcher = false
    }

    val openNewTab = {
        browserViewModel.openNewTab()
        chrome.showTabSwitcher = false
        // Typing straight into a new tab is expected with a keyboard; on touch it would only
        // raise the on-screen keyboard over the start page.
        if (hasKeyboard) chrome.focusAddressBar()
    }
    val closeActiveTab = {
        browserState.activeTabId?.let { browserViewModel.closeTab(it) }
        Unit
    }
    val openExtensions = {
        extensionsViewModel.refreshInstalled()
        chrome.showExtensions = true
    }
    val toggleSplitView = {
        browserViewModel.setLayoutMode(
            if (browserState.layoutMode == LayoutMode.SINGLE_1X1) LayoutMode.SPLIT_1X2 else LayoutMode.SINGLE_1X1
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        val compact = maxWidth < RemDimens.CompactWidth

        Row(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
                TabRail(
                    uiState = browserState,
                    onOpenTabSwitcher = { chrome.showTabSwitcher = !chrome.showTabSwitcher },
                    onSelectTab = { tabId ->
                        chrome.showTabSwitcher = false
                        browserViewModel.selectTab(tabId)
                    },
                    onCloseTab = browserViewModel::closeTab,
                    onCloseOtherTabs = browserViewModel::closeOtherTabs,
                    onAssignTabToPane = browserViewModel::assignTabToPane,
                    onNewTab = openNewTab,
                    onOpenBookmark = { url ->
                        chrome.showTabSwitcher = false
                        browserViewModel.openBookmark(url)
                    },
                    onRemoveBookmark = browserViewModel::removeBookmark
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                BrowserTopBar(
                    uiState = browserState,
                    compact = compact,
                    installedExtensions = extensionsState.installedExtensions,
                    addressBarFocusRequester = chrome.addressBarFocusRequester,
                    onAddressBarChange = browserViewModel::onAddressBarChange,
                    onAddressBarFocusChange = browserViewModel::onAddressBarFocusChange,
                    onSubmitAddressBar = {
                        chrome.showTabSwitcher = false
                        browserViewModel.submitAddressBar()
                    },
                    onBack = { browserViewModel.goBack() },
                    onForward = { browserViewModel.goForward() },
                    onReloadOrStop = { browserViewModel.reloadOrStop() },
                    onToggleBookmark = browserViewModel::toggleBookmarkForActiveTab,
                    onToggleDesktopMode = { browserViewModel.toggleDesktopMode() },
                    onSelectLayout = browserViewModel::setLayoutMode,
                    onSwapPanes = browserViewModel::swapPanes,
                    onResetSplit = browserViewModel::resetSplitRatios,
                    onNewTab = openNewTab,
                    onCloseActiveTab = closeActiveTab,
                    onOpenTabSwitcher = { chrome.showTabSwitcher = !chrome.showTabSwitcher },
                    onOpenCommandPalette = { chrome.showCommandPalette = true },
                    onOpenBookmarks = { chrome.showBookmarks = true },
                    onOpenExtensions = openExtensions,
                    onOpenSettings = { chrome.showSettings = true },
                    onTriggerExtensionAction = extensionsViewModel::triggerBrowserAction
                )

                Box(modifier = Modifier.weight(1f)) {
                    TiledBrowserGrid(
                        uiState = browserState,
                        compact = compact,
                        onFocusPane = browserViewModel::focusPane,
                        onUpdateHorizontalSplit = browserViewModel::updateHorizontalSplit,
                        onUpdateVerticalSplit = browserViewModel::updateVerticalSplit,
                        onCommitSplit = browserViewModel::commitSplitRatios,
                        onResetSplitRatios = browserViewModel::resetSplitRatios,
                        onReloadPane = browserViewModel::reloadOrStop,
                        paneViewport = paneViewport
                    )

                    if (chrome.showTabSwitcher) {
                        TabSwitcher(
                            uiState = browserState,
                            framed = !compact,
                            onSelectTab = { tabId ->
                                chrome.showTabSwitcher = false
                                browserViewModel.selectTab(tabId)
                            },
                            onCloseTab = browserViewModel::closeTab,
                            onNewTab = openNewTab,
                            onDismiss = { chrome.showTabSwitcher = false },
                            modifier = if (compact) Modifier else Modifier.padding(end = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (chrome.showCommandPalette) {
        val isSplit = browserState.layoutMode != LayoutMode.SINGLE_1X1
        val commands = listOf(
            PaletteCommand("search", "Search the web", Icons.Outlined.Search, "Ctrl+L") {
                chrome.focusAddressBar()
            },
            PaletteCommand("switch_tab", "Switch to tab…", Icons.Outlined.Tab) {
                chrome.showTabSwitcher = true
            },
            PaletteCommand(
                id = "split_view",
                label = if (isSplit) "Exit split view" else "Split view",
                icon = Icons.AutoMirrored.Outlined.ViewSidebar,
                shortcut = "Ctrl+\\",
                action = toggleSplitView
            ),
            PaletteCommand("new_tab", "New tab", Icons.Outlined.Add, "Ctrl+T", openNewTab),
            PaletteCommand("close_tab", "Close tab", Icons.Outlined.Close, "Ctrl+W", closeActiveTab),
            PaletteCommand("bookmarks", "Bookmarks", Icons.Outlined.StarBorder) {
                chrome.showBookmarks = true
            },
            PaletteCommand("extensions", "Extensions", Icons.Outlined.Extension, action = openExtensions),
            PaletteCommand("settings", "Open settings", Icons.Outlined.Settings, "Ctrl+,") {
                chrome.showSettings = true
            }
        )
        CommandPalette(
            commands = commands,
            tabs = browserState.tabs,
            onNavigate = { input ->
                chrome.showTabSwitcher = false
                browserViewModel.navigatePane(browserState.activePaneIndex, input)
            },
            onSelectTab = { tabId ->
                chrome.showTabSwitcher = false
                browserViewModel.selectTab(tabId)
            },
            onDismiss = { chrome.showCommandPalette = false }
        )
    }

    if (chrome.showExtensions) {
        ExtensionsManagerDialog(
            uiState = extensionsState,
            onDismiss = { chrome.showExtensions = false },
            onSelectTab = extensionsViewModel::selectTab,
            onSearchQueryChange = extensionsViewModel::onSearchQueryChange,
            onSubmitSearch = extensionsViewModel::submitSearch,
            onInstallAddon = extensionsViewModel::installAddon,
            onInstallUri = { uri -> extensionsViewModel.installFromUri(uri) },
            onPickLocalXpi = onPickLocalXpi,
            onSetEnabled = extensionsViewModel::setExtensionEnabled,
            onUninstall = extensionsViewModel::uninstallExtension,
            onOpenAction = extensionsViewModel::triggerBrowserAction,
            onClearMessages = extensionsViewModel::clearMessages
        )
    }

    if (chrome.showBookmarks) {
        BookmarksDialog(
            bookmarks = browserState.bookmarks,
            onOpenBookmark = { url ->
                chrome.showTabSwitcher = false
                browserViewModel.openBookmark(url)
            },
            onRemoveBookmark = browserViewModel::removeBookmark,
            onDismiss = { chrome.showBookmarks = false }
        )
    }

    if (chrome.showSettings) {
        SettingsDialog(
            settings = browserState.settings,
            versionName = versionName,
            onSetDarkTheme = browserViewModel::setDarkTheme,
            onSetAccent = browserViewModel::setAccentColor,
            onSetEnhancedPrivacy = browserViewModel::setEnhancedPrivacy,
            onDismiss = { chrome.showSettings = false }
        )
    }

    browserState.visiblePrompt?.let { prompt ->
        val origin = browserState.tabs.firstOrNull { it.id == prompt.tabId }
            ?.let { UrlResolver.extractDisplayHost(it.url) }
            ?.takeIf { it.isNotBlank() }
            ?: "This page"
        PagePromptDialog(
            prompt = prompt,
            originLabel = origin,
            onResolved = { browserViewModel.resolvePrompt(prompt.id) }
        )
    }

    extensionsState.pendingPrompt?.let { prompt ->
        ExtensionInstallPromptDialog(
            prompt = prompt,
            onDecision = extensionsViewModel::respondToInstallPrompt
        )
    }

    extensionsState.activePopup?.let { popup ->
        ExtensionPopupDialog(
            popup = popup,
            onDismiss = extensionsViewModel::dismissPopup,
            popupContent = extensionPopupContent
        )
    }
}
