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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.components.BookmarksDialog
import com.rembrowser.rembrowse.ui.components.BrowserTopBar
import com.rembrowser.rembrowse.ui.components.CommandPalette
import com.rembrowser.rembrowse.ui.components.DownloadNoticeBar
import com.rembrowser.rembrowse.ui.components.DownloadPromptDialog
import com.rembrowser.rembrowse.ui.components.DownloadsPanelActions
import com.rembrowser.rembrowse.ui.components.ExtensionInstallPromptDialog
import com.rembrowser.rembrowse.ui.components.ExtensionPopupDialog
import com.rembrowser.rembrowse.ui.components.ExtensionsPanelActions
import com.rembrowser.rembrowse.ui.components.HistoryActions
import com.rembrowser.rembrowse.ui.components.PagePromptDialog
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.SettingsActions
import com.rembrowser.rembrowse.ui.components.SettingsPage
import com.rembrowser.rembrowse.ui.components.TabRail
import com.rembrowser.rembrowse.ui.components.TabSwitcher
import com.rembrowser.rembrowse.ui.components.TiledBrowserGrid
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel

/**
 * Which overlays of the browser chrome are open. Lives outside the composition so hardware
 * keyboard shortcuts handled by the activity can open them too.
 */
@Stable
class BrowserChromeState {
    var showTabSwitcher by mutableStateOf(false)
    var showCommandPalette by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)

    val addressBarFocusRequester = FocusRequester()

    internal var addressFocusRequests by mutableIntStateOf(0)
        private set

    /** Set while keyboard focus is waiting to be handed to the page in the focused pane. */
    internal var pendingContentFocus by mutableStateOf(false)

    /** True while the tab switcher's search field has keyboard focus. */
    var isTabSearchFocused by mutableStateOf(false)

    /** Moves keyboard focus to the address bar (and selects its text) on the next frame. */
    fun focusAddressBar() {
        showTabSwitcher = false
        pendingContentFocus = false
        addressFocusRequests++
    }

    /**
     * Hands keyboard focus to the page in the focused pane. Used whenever a text field is done
     * (Enter or Esc in the address bar, closing the tab switcher) so focus always lands
     * somewhere deliberate instead of on whichever button happens to be first in the window.
     */
    fun focusContent() {
        pendingContentFocus = true
    }
}

@Composable
fun RemBrowseScreen(
    browserViewModel: BrowserViewModel,
    extensionsViewModel: ExtensionsViewModel,
    downloadsViewModel: DownloadsViewModel,
    chrome: BrowserChromeState = remember { BrowserChromeState() },
    versionName: String = "",
    onPickLocalXpi: () -> Unit,
    /** Shows a finished download in another app; false when nothing on the device can. */
    onOpenDownload: (DownloadItem) -> Boolean = { false },
    onOpenDownloadsFolder: () -> Unit = {},
    onFocusWebContent: (tabId: String) -> Unit = {},
    /**
     * Opens the system file picker for a page's upload button. The caller answers the prompt
     * (its onResult or onDismiss) and then resolves it in [browserViewModel].
     */
    onPickFiles: (PagePrompt.FilePicker) -> Unit = {},
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    extensionPopupContent: @Composable (Modifier) -> Unit
) {
    val browserState by browserViewModel.uiState.collectAsStateWithLifecycle()
    val extensionsState by extensionsViewModel.uiState.collectAsStateWithLifecycle()
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
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
    val closeTabSwitcher = {
        chrome.showTabSwitcher = false
        chrome.focusContent()
    }
    BackHandler(enabled = chrome.showTabSwitcher, onBack = closeTabSwitcher)

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
    val openSettings = { section: SettingsSection? ->
        chrome.showTabSwitcher = false
        browserViewModel.openSettings(section)
    }
    val openExtensions = {
        extensionsViewModel.refreshInstalled()
        openSettings(SettingsSection.EXTENSIONS)
    }
    val openDownloads = { openSettings(SettingsSection.DOWNLOADS) }
    val openHistory = { openSettings(SettingsSection.HISTORY) }
    val history by browserViewModel.history.collectAsStateWithLifecycle()
    val historyActions = remember(browserViewModel) {
        HistoryActions(
            onOpen = browserViewModel::openHistoryEntry,
            onRemove = browserViewModel::removeHistoryEntry,
            onClear = browserViewModel::clearHistory
        )
    }
    val settingsActions = remember(browserViewModel, extensionsViewModel) {
        SettingsActions(
            onSetDarkTheme = browserViewModel::setDarkTheme,
            onSetAccent = browserViewModel::setAccentColor,
            onSetSearchEngine = browserViewModel::setSearchEngine,
            onSetRestoreTabsOnStartup = browserViewModel::setRestoreTabsOnStartup,
            onSetOpenNewTabsNextToCurrent = browserViewModel::setOpenNewTabsNextToCurrent,
            onSetDesktopSiteByDefault = browserViewModel::setDesktopSiteByDefault,
            onSetEnhancedPrivacy = browserViewModel::setEnhancedPrivacy,
            onSetAdBlocker = extensionsViewModel::setAdBlockerEnabled,
            onSetAskBeforeDownloading = browserViewModel::setAskBeforeDownloading,
            onSetKeepHistory = browserViewModel::setKeepHistory
        )
    }
    val openDownload = { id: String -> downloadsViewModel.open(id, onOpenDownload) }
    val downloadActions = remember(downloadsViewModel, onOpenDownload, onOpenDownloadsFolder) {
        DownloadsPanelActions(
            onOpen = openDownload,
            onCancel = downloadsViewModel::cancel,
            onRemove = downloadsViewModel::remove,
            onClearFinished = downloadsViewModel::clearFinished,
            onOpenFolder = onOpenDownloadsFolder
        )
    }
    val extensionActions = remember(extensionsViewModel, onPickLocalXpi) {
        ExtensionsPanelActions(
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
    val toggleSplitView = {
        if (browserState.isSplit) {
            browserViewModel.setLayoutMode(LayoutMode.SINGLE_1X1)
        } else {
            browserViewModel.splitCurrentTab(currentOnLeft = true)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        val compact = maxWidth < RemDimens.CompactWidth
        val toolbarExtensions = remember(extensionsState.installedExtensions) {
            extensionsState.installedExtensions.filter { it.enabled && it.hasBrowserAction }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
                TabRail(
                    uiState = browserState,
                    onOpenTabSwitcher = { chrome.showTabSwitcher = !chrome.showTabSwitcher },
                    onSelectTab = { tabId ->
                        browserViewModel.selectTab(tabId)
                        closeTabSwitcher()
                    },
                    onCloseTab = browserViewModel::closeTab,
                    onCloseOtherTabs = browserViewModel::closeOtherTabs,
                    onAssignTabToPane = browserViewModel::assignTabToPane,
                    onNewTab = openNewTab,
                    onOpenBookmark = { url ->
                        browserViewModel.openBookmark(url)
                        closeTabSwitcher()
                    },
                    onRemoveBookmark = browserViewModel::removeBookmark,
                    extensions = toolbarExtensions,
                    openPopupExtensionId = extensionsState.activePopup?.extensionId,
                    onTriggerExtensionAction = extensionsViewModel::triggerBrowserAction,
                    onManageExtensions = openExtensions
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
                    onLeaveAddressBar = chrome::focusContent,
                    onBack = { browserViewModel.goBack() },
                    onForward = { browserViewModel.goForward() },
                    onReloadOrStop = { browserViewModel.reloadOrStop() },
                    onToggleBookmark = browserViewModel::toggleBookmarkForActiveTab,
                    onToggleDesktopMode = { browserViewModel.toggleDesktopMode() },
                    onSelectLayout = browserViewModel::setLayoutMode,
                    onSplit = browserViewModel::splitCurrentTab,
                    onSwapPanes = browserViewModel::swapFocusedPane,
                    onSetSplitPreset = browserViewModel::setSplitPreset,
                    onNewTab = openNewTab,
                    onCloseActiveTab = closeActiveTab,
                    onOpenTabSwitcher = { chrome.showTabSwitcher = !chrome.showTabSwitcher },
                    onOpenCommandPalette = { chrome.showCommandPalette = true },
                    onOpenBookmarks = { chrome.showBookmarks = true },
                    onOpenDownloads = openDownloads,
                    onOpenHistory = openHistory,
                    onOpenExtensions = openExtensions,
                    onOpenSettings = { openSettings(null) },
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
                        contentFocusRequested = chrome.pendingContentFocus,
                        onContentFocusHandled = { chrome.pendingContentFocus = false },
                        onFocusWebContent = onFocusWebContent,
                        internalPage = { tab, pageModifier ->
                            SettingsPage(
                                section = SettingsSection.fromUrl(tab.url),
                                onSelectSection = { section ->
                                    if (section == SettingsSection.EXTENSIONS) extensionsViewModel.refreshInstalled()
                                    browserViewModel.showSettingsSection(tab.id, section)
                                },
                                settings = browserState.settings,
                                versionName = versionName,
                                extensionsState = extensionsState,
                                downloadsState = downloadsState,
                                downloadLocation = downloadsViewModel.locationLabel,
                                canShowDownloadFolder = downloadsViewModel.canShowFolder,
                                actions = settingsActions,
                                extensionActions = extensionActions,
                                downloadActions = downloadActions,
                                modifier = pageModifier,
                                history = history,
                                historyActions = historyActions
                            )
                        },
                        paneViewport = paneViewport
                    )

                    if (chrome.showTabSwitcher) {
                        TabSwitcher(
                            uiState = browserState,
                            framed = !compact,
                            onSelectTab = { tabId ->
                                browserViewModel.selectTab(tabId)
                                closeTabSwitcher()
                            },
                            onCloseTab = browserViewModel::closeTab,
                            onNewTab = openNewTab,
                            onDismiss = closeTabSwitcher,
                            onSearchFocusChange = { chrome.isTabSearchFocused = it },
                            modifier = if (compact) Modifier else Modifier.padding(end = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
        }

        // Over the page, never in its way for long: it leaves by itself after a few seconds
        DownloadNoticeBar(
            notice = downloadsState.notice,
            message = downloadsState.message,
            onOpen = openDownload,
            onShowDownloads = openDownloads,
            onDismiss = {
                downloadsViewModel.dismissNotice()
                downloadsViewModel.clearMessage()
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        extensionsState.activePopup?.let { popup ->
            ExtensionPopupDialog(
                popup = popup,
                icon = extensionsState.installedExtensions.firstOrNull { it.id == popup.extensionId }?.icon,
                // Beside the extension buttons when the rail shows them, like a toolbar popup
                anchorToRail = !compact,
                onDismiss = extensionsViewModel::dismissPopup,
                popupContent = extensionPopupContent
            )
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
            PaletteCommand("history", "History", Icons.Outlined.History, "Ctrl+H", openHistory),
            PaletteCommand("downloads", "Downloads", Icons.Outlined.FileDownload, "Ctrl+J", openDownloads),
            PaletteCommand("extensions", "Extensions", Icons.Outlined.Extension, action = openExtensions),
            PaletteCommand("settings", "Open settings", Icons.Outlined.Settings, "Ctrl+,") {
                openSettings(null)
            }
        )
        CommandPalette(
            commands = commands,
            tabs = browserState.tabs,
            history = history,
            onNavigate = { input ->
                browserViewModel.navigatePane(browserState.activePaneIndex, input)
                closeTabSwitcher()
            },
            onSelectTab = { tabId ->
                browserViewModel.selectTab(tabId)
                closeTabSwitcher()
            },
            onDismiss = { chrome.showCommandPalette = false }
        )
    }

    if (chrome.showBookmarks) {
        BookmarksDialog(
            bookmarks = browserState.bookmarks,
            onOpenBookmark = { url ->
                browserViewModel.openBookmark(url)
                closeTabSwitcher()
            },
            onRemoveBookmark = browserViewModel::removeBookmark,
            onDismiss = { chrome.showBookmarks = false }
        )
    }

    browserState.visiblePrompt?.let { prompt ->
        if (prompt is PagePrompt.FilePicker) {
            // An upload button: the system file picker answers it, once per request
            LaunchedEffect(prompt.id) { onPickFiles(prompt) }
            return@let
        }
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

    downloadsState.pendingRequest?.let { request ->
        DownloadPromptDialog(request = request, onDecision = downloadsViewModel::answerPendingRequest)
    }

    extensionsState.pendingPrompt?.let { prompt ->
        ExtensionInstallPromptDialog(
            prompt = prompt,
            onDecision = extensionsViewModel::respondToInstallPrompt
        )
    }
}
