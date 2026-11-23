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
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.TerminalUrls
import com.rembrowser.rembrowse.model.PageMenuAction
import com.rembrowser.rembrowse.model.pageMenuCopyText
import com.rembrowser.rembrowse.ui.components.BookmarksDialog
import com.rembrowser.rembrowse.ui.components.BrowserTopBar
import com.rembrowser.rembrowse.ui.components.CommandPalette
import com.rembrowser.rembrowse.ui.components.GoToAddressDialog
import com.rembrowser.rembrowse.ui.components.DownloadNoticeBar
import com.rembrowser.rembrowse.ui.components.FindInPageActions
import com.rembrowser.rembrowse.ui.components.FindInPageOverlay
import com.rembrowser.rembrowse.ui.components.UpdateActions
import com.rembrowser.rembrowse.ui.components.UpdateNoticeBar
import com.rembrowser.rembrowse.ui.components.DownloadPromptDialog
import com.rembrowser.rembrowse.ui.components.DownloadsPanelActions
import com.rembrowser.rembrowse.ui.components.ExtensionInstallPromptDialog
import com.rembrowser.rembrowse.ui.components.ExtensionPopupDialog
import com.rembrowser.rembrowse.ui.components.ExtensionsPanelActions
import com.rembrowser.rembrowse.ui.components.HistoryActions
import com.rembrowser.rembrowse.ui.components.PageContextMenuOverlay
import com.rembrowser.rembrowse.ui.components.PagePromptDialog
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.PrintScreen
import com.rembrowser.rembrowse.ui.components.sendPdfToPrinter
import com.rembrowser.rembrowse.ui.components.SettingsActions
import com.rembrowser.rembrowse.ui.components.SettingsPage
import com.rembrowser.rembrowse.ui.components.TabDragGhost
import com.rembrowser.rembrowse.ui.components.TabDragState
import com.rembrowser.rembrowse.ui.components.TabRail
import com.rembrowser.rembrowse.ui.components.TabSwitcher
import com.rembrowser.rembrowse.ui.components.TerminalPage
import com.rembrowser.rembrowse.ui.components.TerminalSettingsActions
import com.rembrowser.rembrowse.ui.components.TiledBrowserGrid
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.ui.theme.tones
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import com.rembrowser.rembrowse.viewmodel.UpdatesViewModel
import androidx.compose.ui.platform.LocalContext
import com.rembrowser.rembrowse.engine.PointerDevices

/**
 * Which overlays of the browser chrome are open. Lives outside the composition so hardware
 * keyboard shortcuts handled by the activity can open them too.
 */
@Stable
class BrowserChromeState {
    var showTabSwitcher by mutableStateOf(false)
    var showCommandPalette by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)

    /** The Go to address popup (Ctrl+G). */
    var showGoToAddress by mutableStateOf(false)
        private set

    /**
     * Set when a popup window took an Enter press and closed: the release of that Enter arrives
     * in the browser's window, where it must not reach the page.
     */
    var swallowNextEnterUp = false

    /** Opens the Go to address popup, or closes it if it is open. */
    fun toggleGoToAddress() {
        if (showGoToAddress) {
            showGoToAddress = false
            return
        }
        showCommandPalette = false
        showTabSwitcher = false
        showGoToAddress = true
    }

    fun closeGoToAddress() {
        showGoToAddress = false
    }

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
    /** Copies the picture at an address (the page menu's Copy image), shown on the given page. */
    onCopyImage: (imageUrl: String, pageUrl: String?) -> Unit = { _, _ -> },
    /**
     * Opens the system file picker for a page's upload button. The caller answers the prompt
     * (its onResult or onDismiss) and then resolves it in [browserViewModel].
     */
    onPickFiles: (PagePrompt.FilePicker) -> Unit = {},
    /** RemBrowse's own updates; null leaves them out (as in tests that don't need them). */
    updatesViewModel: UpdatesViewModel? = null,
    /** Terminal tabs and Settings → Terminal; null leaves them empty (as in tests that don't need them). */
    terminalViewModel: TerminalViewModel? = null,
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

    // Nothing left to go back to, on a laptop (a mouse or touchpad): Back does nothing rather than
    // send RemBrowse away. A touchpad's three-finger swipe is the system's Back there, and easy to
    // make by accident. Phones keep Android's own Back. Composed first, so every handler below,
    // which only acts when it has something to do, comes before it.
    val context = LocalContext.current
    val backAtRootStays = remember(context) { PointerDevices.hasMouseOrTouchpad(context) }
    BackHandler(enabled = backAtRootStays) { }
    BackHandler(enabled = !chrome.showTabSwitcher && browserState.canNavigateBack(browserState.activeTab)) {
        browserViewModel.goBack()
    }
    val closeTabSwitcher = {
        chrome.showTabSwitcher = false
        chrome.focusContent()
    }
    BackHandler(enabled = chrome.showTabSwitcher, onBack = closeTabSwitcher)
    // A fullscreen page (a video) has the whole window: no rail, no toolbar, no other panes.
    // Back leaves it before doing anything else.
    val fullscreen = browserState.fullscreenTabId != null
    BackHandler(enabled = fullscreen, onBack = browserViewModel::exitFullscreen)
    val tabDrag = remember { TabDragState() }

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
    // A terminal tab on [target]; a chosen server starts connecting at once
    val openTerminal = { target: TerminalTarget ->
        chrome.showTabSwitcher = false
        val tabId = browserViewModel.openTerminal(target)
        if (target != TerminalTarget.Picker) terminalViewModel?.connect(tabId, target)
        Unit
    }
    val terminalState = terminalViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // A terminal's address bar names the server, the way `ssh` is typed, not its rem:// address
    val terminalAddress = browserState.activeTab?.takeIf { it.isTerminal }?.let { tab ->
        when (val target = TerminalUrls.parse(tab.url)) {
            TerminalTarget.Picker, null -> "New terminal"
            is TerminalTarget.Address -> "ssh ${target.display}"
            is TerminalTarget.SavedHost -> terminalState?.hosts?.firstOrNull { it.id == target.hostId }?.let { "ssh ${it.address}" } ?: "Terminal"
        }
    }
    val terminalActions = remember(terminalViewModel) {
        terminalViewModel?.let { terminal ->
            TerminalSettingsActions(
                onSaveHost = { host, password, forget -> terminal.saveHost(host, password, forget) },
                onDeleteHost = terminal::deleteHost,
                onConnectHost = { host -> openTerminal(TerminalTarget.SavedHost(host.id)) },
                onGenerateKey = terminal::generateKey,
                onImportKey = terminal::importKey,
                onDeleteKey = terminal::deleteKey,
                onForgetKnownHost = terminal::forgetKnownHost,
                onSetFontSize = terminal::setFontSize,
                onSetTheme = terminal::setTheme,
                onSetFont = terminal::setFont,
                onSetCursorStyle = terminal::setCursorStyle,
                onSetSuggestions = terminal::setSuggestions,
                onSetRememberCommands = terminal::setRememberCommands,
                onClearCommandHistory = terminal::clearCommandHistory
            )
        }
    }
    val openDownloads = { openSettings(SettingsSection.DOWNLOADS) }
    val openHistory = { openSettings(SettingsSection.HISTORY) }
    val history by browserViewModel.history.collectAsStateWithLifecycle()
    val findInPage by browserViewModel.findInPage.collectAsStateWithLifecycle()
    val pageMenu by browserViewModel.pageMenu.collectAsStateWithLifecycle()
    val printPreview by browserViewModel.printPreview.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val findActions = remember(browserViewModel) {
        FindInPageActions(
            onQueryChange = browserViewModel::updateFindQuery,
            onNext = browserViewModel::findNext,
            onPrevious = browserViewModel::findPrevious,
            onMatchCaseChange = browserViewModel::setFindMatchCase,
            onClose = {
                browserViewModel.closeFindInPage()
                // Back to the page that was being searched
                chrome.focusContent()
            }
        )
    }
    // Back closes the find bar before it goes back in the page, as in Chrome. Composed after the
    // handlers above, so it comes first; it stands aside while the tab switcher or a fullscreen
    // page is in front, whose own Back comes first. (Esc is read before the keyboard app, in
    // MainActivity.)
    BackHandler(
        enabled = findInPage != null && !fullscreen && !chrome.showTabSwitcher,
        onBack = findActions.onClose
    )
    val openFindInPage = {
        chrome.showTabSwitcher = false
        browserViewModel.openFindInPage()
    }
    val printPage = {
        chrome.showTabSwitcher = false
        browserViewModel.printPage()
    }
    val updatesState = updatesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val updateActions = remember(updatesViewModel) {
        updatesViewModel?.let { updates ->
            UpdateActions(
                onCheck = updates::checkNow,
                onDownload = updates::downloadAndInstall,
                onCancel = updates::cancelDownload,
                onInstall = updates::install,
                onSetCheckAutomatically = updates::setCheckAutomatically
            )
        }
    }
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
            onSetKeepHistory = browserViewModel::setKeepHistory,
            onSetSwipeNavigation = browserViewModel::setSwipeNavigation,
            onSetReverseSwipeDirection = browserViewModel::setReverseSwipeDirection,
            onSetPageRendering = browserViewModel::setPageRendering
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
            // Fullscreen reaches under the (hidden) system bars and around any cutout
            .then(if (fullscreen) Modifier else Modifier.safeDrawingPadding())
    ) {
        val compact = maxWidth < RemDimens.CompactWidth
        val toolbarExtensions = remember(extensionsState.installedExtensions) {
            extensionsState.installedExtensions.filter { it.enabled && it.hasBrowserAction }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (!compact && !fullscreen) {
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
                    onClosePane = browserViewModel::closePane,
                    dragState = tabDrag,
                    onDropTab = browserViewModel::dropTabOnPane,
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
                if (!fullscreen) BrowserTopBar(
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
                    onClosePane = { browserViewModel.closePane() },
                    onNewTab = openNewTab,
                    onCloseActiveTab = closeActiveTab,
                    onOpenTabSwitcher = { chrome.showTabSwitcher = !chrome.showTabSwitcher },
                    onOpenCommandPalette = { chrome.showCommandPalette = true },
                    onOpenBookmarks = { chrome.showBookmarks = true },
                    onOpenDownloads = openDownloads,
                    onOpenHistory = openHistory,
                    onFindInPage = openFindInPage,
                    onPrint = printPage,
                    onOpenExtensions = openExtensions,
                    onOpenSettings = { openSettings(null) },
                    onTriggerExtensionAction = extensionsViewModel::triggerBrowserAction,
                    onNewTerminal = { openTerminal(TerminalTarget.Picker) },
                    addressDisplay = terminalAddress
                )

                Box(modifier = Modifier.weight(1f)) {
                    TiledBrowserGrid(
                        uiState = browserState,
                        compact = compact,
                        onFocusPane = browserViewModel::focusPane,
                        onResizeSplit = browserViewModel::setSplitRatio,
                        onCommitSplit = browserViewModel::commitSplitRatios,
                        onResetSplit = browserViewModel::resetSplit,
                        dragState = tabDrag,
                        onReloadPane = browserViewModel::reloadOrStop,
                        contentFocusRequested = chrome.pendingContentFocus,
                        onContentFocusHandled = { chrome.pendingContentFocus = false },
                        onFocusWebContent = onFocusWebContent,
                        internalPage = { tab, pageModifier ->
                            if (tab.isTerminal && terminalViewModel != null) {
                                TerminalPage(
                                    tab = tab,
                                    terminal = terminalViewModel,
                                    isActive = browserState.activeTabId == tab.id && !browserState.isAddressBarFocused,
                                    onChooseTarget = { target -> browserViewModel.showTerminalTarget(tab.id, target) },
                                    onOpenSettings = { openSettings(SettingsSection.TERMINAL) },
                                    modifier = pageModifier,
                                    cursorColor = browserState.settings.accent.tones().darkPrimary
                                )
                            } else {
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
                                historyActions = historyActions,
                                updates = updatesState,
                                updateActions = updateActions,
                                terminal = terminalState,
                                terminalActions = terminalActions
                            )
                            }
                        },
                        paneViewport = paneViewport,
                        paneOverlay = { tab ->
                            FindInPageOverlay(tabId = tab.id, find = findInPage, actions = findActions)
                            PageContextMenuOverlay(
                                menu = pageMenu,
                                tab = tab,
                                canSplit = browserState.paneCount < PaneNode.MAX_PANES,
                                isBookmarked = browserState.isBookmarked(tab.url),
                                onAction = { action ->
                                    pageMenu?.let { menu ->
                                        pageMenuCopyText(menu, action, tab.url)?.let { clipboard.setText(AnnotatedString(it)) }
                                        if (action == PageMenuAction.COPY_IMAGE) {
                                            menu.copyableImageUrl?.let { onCopyImage(it, tab.url) }
                                        }
                                    }
                                    browserViewModel.onPageMenuAction(action)
                                },
                                onDismiss = browserViewModel::dismissPageMenu
                            )
                        }
                    )

                    if (chrome.showTabSwitcher && !fullscreen) {
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

        TabDragGhost(dragState = tabDrag, tabs = browserState.tabs)

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Stays until answered, which is at most once per version; it waits out a fullscreen video
            UpdateNoticeBar(
                update = updatesState?.notice?.takeUnless { fullscreen },
                onUpdate = {
                    updatesViewModel?.acceptNotice()
                    openSettings(SettingsSection.ABOUT)
                },
                onDismiss = { updatesViewModel?.dismissNotice() }
            )
            // Over the page, never in its way for long: it leaves by itself after a few seconds
            DownloadNoticeBar(
                notice = downloadsState.notice,
                message = downloadsState.message,
                onOpen = openDownload,
                onShowDownloads = openDownloads,
                onDismiss = {
                    downloadsViewModel.dismissNotice()
                    downloadsViewModel.clearMessage()
                }
            )
        }

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
        val isSplit = browserState.isSplit
        val commands = listOf(
            PaletteCommand("search", "Search the web", Icons.Outlined.Search, "Ctrl+L") {
                chrome.focusAddressBar()
            },
            PaletteCommand("go_to_address", "Go to address…", Icons.Outlined.Language, "Ctrl+G") {
                chrome.toggleGoToAddress()
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
            PaletteCommand("new_terminal", "New terminal", Icons.Outlined.Terminal, "Ctrl+Shift+T") {
                openTerminal(TerminalTarget.Picker)
            },
            PaletteCommand("close_tab", "Close tab", Icons.Outlined.Close, "Ctrl+W", closeActiveTab),
            PaletteCommand("bookmarks", "Bookmarks", Icons.Outlined.StarBorder) {
                chrome.showBookmarks = true
            },
            PaletteCommand("find_in_page", "Find in page", Icons.Outlined.FindInPage, "Ctrl+F", openFindInPage),
            PaletteCommand("print", "Print…", Icons.Outlined.Print, "Ctrl+P", printPage),
            PaletteCommand("history", "History", Icons.Outlined.History, "Ctrl+H", openHistory),
            PaletteCommand("downloads", "Downloads", Icons.Outlined.FileDownload, "Ctrl+J", openDownloads),
            PaletteCommand("extensions", "Extensions", Icons.Outlined.Extension, action = openExtensions),
            PaletteCommand("settings", "Open settings", Icons.Outlined.Settings, "Ctrl+,") {
                openSettings(null)
            }
        ) + terminalState?.hosts.orEmpty().map { host ->
            PaletteCommand("ssh_${host.id}", "SSH: ${host.displayName}", Icons.Outlined.Terminal) {
                openTerminal(TerminalTarget.SavedHost(host.id))
            }
        }
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

    if (chrome.showGoToAddress) {
        val tab = browserState.activeTab
        // The window's own width, as the layout measures it (a freeform window included)
        val windowIsCompact = LocalConfiguration.current.screenWidthDp.dp < RemDimens.CompactWidth
        GoToAddressDialog(
            currentAddress = tab?.takeUnless { it.isNewTabPage || it.isInternalPage }?.url.orEmpty(),
            tabs = browserState.tabs,
            history = history,
            compact = windowIsCompact,
            onNavigate = { input ->
                browserViewModel.navigatePane(browserState.activePaneIndex, input)
                closeTabSwitcher()
            },
            onNavigateInNewTab = { input ->
                browserViewModel.openNewTab()
                browserViewModel.navigatePane(browserViewModel.uiState.value.activePaneIndex, input)
                closeTabSwitcher()
            },
            onSelectTab = { tabId ->
                browserViewModel.selectTab(tabId)
                closeTabSwitcher()
            },
            onEnterTaken = { chrome.swallowNextEnterUp = true },
            onDismiss = chrome::closeGoToAddress
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

    // A page laid out for printing: saved as a PDF or sent to a printer from RemBrowse's own screen.
    // Under a page's own dialogs, which a page may open once its print is under way.
    printPreview?.let { preview ->
        key(preview.serial) {
            PrintScreen(
                preview = preview,
                onSaveAsPdf = browserViewModel::savePrintPreviewAsPdf,
                onPrint = { jobName ->
                    sendPdfToPrinter(context, browserViewModel.takePrintPreviewForPrinter(), jobName)
                },
                onCancel = browserViewModel::dismissPrintPreview,
                onEnterTaken = { chrome.swallowNextEnterUp = true }
            )
        }
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
