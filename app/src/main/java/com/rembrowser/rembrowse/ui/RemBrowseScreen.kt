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
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
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
import com.rembrowser.rembrowse.model.PxRect
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
import com.rembrowser.rembrowse.ui.components.PermissionPromptOverlay
import com.rembrowser.rembrowse.ui.components.SitePermissionActions
import com.rembrowser.rembrowse.ui.components.PagePromptDialog
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.PaletteHost
import com.rembrowser.rembrowse.ui.components.PrintScreen
import com.rembrowser.rembrowse.ui.components.CaptureRegionLifetime
import com.rembrowser.rembrowse.ui.components.CaptureRegionMarker
import com.rembrowser.rembrowse.ui.components.CaptureRegions
import com.rembrowser.rembrowse.ui.components.FocusBeforeCapture
import com.rembrowser.rembrowse.ui.components.ScreenCaptureOverlay
import com.rembrowser.rembrowse.ui.components.ScreenCaptureState
import com.rembrowser.rembrowse.ui.components.ScreenshotNoticeBar
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import com.rembrowser.rembrowse.ui.components.reportCaptureRegion
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
import com.rembrowser.rembrowse.ui.theme.terminalCursorColor
import com.rembrowser.rembrowse.util.UrlResolver
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import com.rembrowser.rembrowse.viewmodel.UpdatesViewModel
import androidx.compose.ui.platform.LocalContext
import com.rembrowser.rembrowse.engine.PointerDevices
import androidx.compose.ui.platform.LocalView
import java.lang.ref.WeakReference
import kotlinx.coroutines.withTimeoutOrNull

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

    /** The screenshot overlay (Ctrl+Alt+S) and the notice after it. */
    val screenCapture = ScreenCaptureState()

    /** Moves keyboard focus to the address bar (and selects its text) on the next frame. */
    fun focusAddressBar() {
        showTabSwitcher = false
        pendingContentFocus = false
        addressFocusRequests++
    }

    /** Gives the address bar the keyboard back, leaving the tab switcher as it is. */
    fun refocusAddressBar() {
        pendingContentFocus = false
        addressFocusRequests++
    }

    internal var tabSearchFocusRequests by mutableIntStateOf(0)
        private set

    /** Gives the open tab switcher's search field the keyboard back. */
    fun focusTabSearch() {
        tabSearchFocusRequests++
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
    /**
     * Copies the window pixels in the area (window coordinates, the overlay already gone) to the
     * clipboard as a picture, and says what became of it. Cancelled if it takes too long, after
     * which it must leave the clipboard alone. Without one, a screenshot fails.
     */
    onCaptureScreen: suspend (area: PxRect) -> ScreenshotResult = { ScreenshotResult.Failed },
    /** Opens the system's Keyboard Shortcuts sheet, which lists the browser's shortcuts. */
    onShowKeyboardShortcuts: () -> Unit = {},
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
    val openSitePermissions = { openSettings(SettingsSection.SITE_PERMISSIONS) }
    val history by browserViewModel.history.collectAsStateWithLifecycle()
    val findInPage by browserViewModel.findInPage.collectAsStateWithLifecycle()
    val pageMenu by browserViewModel.pageMenu.collectAsStateWithLifecycle()
    val printPreview by browserViewModel.printPreview.collectAsStateWithLifecycle()
    val permissionRequests by browserViewModel.permissionRequests.collectAsStateWithLifecycle()
    val sitePermissions by browserViewModel.sitePermissions.collectAsStateWithLifecycle()
    val sitePermissionActions = remember(browserViewModel) {
        SitePermissionActions(
            onRefresh = browserViewModel::refreshSitePermissions,
            onSet = { entry, state ->
                browserViewModel.setSitePermission(entry.origin, entry.kind, state, entry.embeddedOrigin)
            },
            onReset = browserViewModel::clearSitePermissions
        )
    }
    // The replacement, LocalClipboard, is suspend-only; moving to it is a change of its own
    @Suppress("DEPRECATION")
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
    // The tab switcher stays open: it can be taken too
    val takeScreenshot = {
        chrome.screenCapture.start()
        Unit
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
            onSetThemeMode = browserViewModel::setThemeMode,
            onSetAccent = browserViewModel::setAccentColor,
            onSetSystemColors = browserViewModel::setSystemColors,
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
                    onOpenBookmarkInNewTab = { url ->
                        browserViewModel.openBookmarkInNewTab(url)
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
                    onTakeScreenshot = takeScreenshot,
                    onOpenExtensions = openExtensions,
                    onOpenSettings = { openSettings(null) },
                    onTriggerExtensionAction = extensionsViewModel::triggerBrowserAction,
                    onNewTerminal = { openTerminal(TerminalTarget.Picker) },
                    onShowKeyboardShortcuts = onShowKeyboardShortcuts,
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
                                    cursorColor = terminalCursorColor(
                                        context,
                                        browserState.settings.accent,
                                        browserState.settings.useSystemColors
                                    )
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
                                terminalActions = terminalActions,
                                sitePermissions = sitePermissions,
                                sitePermissionActions = sitePermissionActions
                            )
                            }
                        },
                        paneViewport = paneViewport,
                        paneOverlay = { tab ->
                            // A click on the screenshot overlay over this pane takes all of it
                            CaptureRegionMarker(chrome.screenCapture, CaptureRegions.pane(tab.id))
                            // The oldest question of this pane's tab, under the find bar if both are open
                            PermissionPromptOverlay(
                                request = permissionRequests.firstOrNull { it.tabId == tab.id },
                                findBarOpen = findInPage?.tabId == tab.id,
                                onAnswer = browserViewModel::answerPermission,
                                onReturnFocus = chrome::focusContent
                            )
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
                                        if (action == PageMenuAction.SCREENSHOT) takeScreenshot()
                                    }
                                    browserViewModel.onPageMenuAction(action)
                                },
                                onDismiss = browserViewModel::dismissPageMenu
                            )
                        }
                    )

                    if (chrome.showTabSwitcher && !fullscreen) {
                        CaptureRegionLifetime(chrome.screenCapture, CaptureRegions.TAB_SWITCHER)
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
                            searchFocusRequests = chrome.tabSearchFocusRequests,
                            modifier = (if (compact) Modifier else Modifier.padding(end = 6.dp, bottom = 6.dp))
                                // Over the panes: a click on the screenshot overlay there takes the switcher
                                .reportCaptureRegion(chrome.screenCapture, CaptureRegions.TAB_SWITCHER, layer = 1)
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
            ScreenshotNoticeBar(
                notice = chrome.screenCapture.notice,
                onDismiss = chrome.screenCapture::dismissNotice
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

    // Over everything else in the window, the chrome included
    val screenCapture = chrome.screenCapture
    when (screenCapture.phase) {
        ScreenCaptureState.Phase.SELECTING -> ScreenCaptureOverlay(
            state = screenCapture,
            focusedRegionKey = if (chrome.showTabSwitcher && !fullscreen) {
                CaptureRegions.TAB_SWITCHER
            } else {
                browserState.activeTabId?.let(CaptureRegions::pane)
            }
        )
        ScreenCaptureState.Phase.CAPTURING -> screenCapture.area?.let { area ->
            val generation = screenCapture.generation
            LaunchedEffect(generation) {
                // The overlay left with this phase. Two frames make sure the window has drawn
                // without it; the copy itself then waits for the next frame to be handed over.
                withFrameNanos { }
                withFrameNanos { }
                // Given up on after a while, which cancels the copy too: it frees its pictures and
                // can't reach the clipboard later
                val result = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MILLIS) { onCaptureScreen(area) }
                    ?: ScreenshotResult.Failed
                screenCapture.finish(result, generation)
            }
        }
        ScreenCaptureState.Phase.IDLE -> Unit
    }
    // Composed after every other Back handler, so it comes first while the overlay is up
    BackHandler(enabled = screenCapture.isSelecting, onBack = screenCapture::cancel)
    // The keyboard goes back to whatever had it when the overlay came up: noted before the overlay
    // takes the focus (a frame later), given back once it has gone, however it went
    val composeView = LocalView.current
    val focusBeforeCapture = remember { arrayOfNulls<FocusBeforeCapture>(1) }
    LaunchedEffect(screenCapture.isSelecting) {
        if (screenCapture.isSelecting) {
            val state = browserViewModel.uiState.value
            val focusedView = composeView.rootView.findFocus()
            focusBeforeCapture[0] = when {
                chrome.showTabSwitcher && chrome.isTabSearchFocused -> FocusBeforeCapture.TabSearch
                state.isAddressBarFocused -> FocusBeforeCapture.AddressBar(state.addressBarInput)
                focusedView != null && focusedView !== composeView -> FocusBeforeCapture.View(WeakReference(focusedView))
                chrome.showTabSwitcher -> FocusBeforeCapture.Nothing
                else -> FocusBeforeCapture.Page
            }
            return@LaunchedEffect
        }
        val before = focusBeforeCapture[0] ?: return@LaunchedEffect
        focusBeforeCapture[0] = null
        // The overlay has left the screen, and its focus with it
        withFrameNanos { }
        when (before) {
            is FocusBeforeCapture.AddressBar -> {
                // Losing the focus put the page's address back in place of what was being typed
                browserViewModel.onAddressBarChange(before.typed)
                chrome.refocusAddressBar()
            }
            FocusBeforeCapture.TabSearch -> chrome.focusTabSearch()
            is FocusBeforeCapture.View -> {
                val view = before.view.get()?.takeIf { it.isAttachedToWindow && it.isShown }
                if (view == null || !(view.isFocused || view.requestFocus())) chrome.focusContent()
            }
            FocusBeforeCapture.Page -> chrome.focusContent()
            FocusBeforeCapture.Nothing -> Unit
        }
    }

    // The commands of the palette (Ctrl+K) and Go to address (Ctrl+G), and the saved SSH hosts
    // both connect to: built here only, when one of them is open
    fun paletteCommands(): List<PaletteCommand> {
        val isSplit = browserState.isSplit
        return listOf(
            // Neither is offered by Go to address: one would drop what was typed there, the other
            // would only open it again
            PaletteCommand("search", "Search the web", Icons.Outlined.Search, "Ctrl+L", inGoToAddress = false) {
                chrome.focusAddressBar()
            },
            PaletteCommand(
                "go_to_address", "Go to address…", Icons.Outlined.Language, "Ctrl+G",
                keywords = listOf("url"), inGoToAddress = false
            ) {
                chrome.toggleGoToAddress()
            },
            PaletteCommand("switch_tab", "Switch to tab…", Icons.Outlined.Tab, keywords = listOf("tabs")) {
                chrome.showTabSwitcher = true
            },
            PaletteCommand(
                id = "split_view",
                label = if (isSplit) "Exit split view" else "Split view",
                icon = Icons.AutoMirrored.Outlined.ViewSidebar,
                shortcut = "Ctrl+\\",
                promotable = false,
                action = toggleSplitView
            ),
            PaletteCommand("new_tab", "New tab", Icons.Outlined.Add, "Ctrl+T", action = openNewTab),
            PaletteCommand(
                "new_terminal", "New terminal", Icons.Outlined.Terminal, "Ctrl+Shift+T",
                keywords = listOf("terminal", "ssh", "shell", "console")
            ) {
                openTerminal(TerminalTarget.Picker)
            },
            PaletteCommand("close_tab", "Close tab", Icons.Outlined.Close, "Ctrl+W", promotable = false, action = closeActiveTab),
            PaletteCommand("bookmarks", "Bookmarks", Icons.Outlined.StarBorder, keywords = listOf("favourites", "favorites")) {
                chrome.showBookmarks = true
            },
            PaletteCommand("find_in_page", "Find in page", Icons.Outlined.FindInPage, "Ctrl+F", action = openFindInPage),
            PaletteCommand("print", "Print…", Icons.Outlined.Print, "Ctrl+P", keywords = listOf("pdf"), action = printPage),
            PaletteCommand(
                "screenshot", "Take screenshot", Icons.Outlined.ScreenshotMonitor, "Ctrl+Alt+S",
                keywords = listOf("screenshot", "capture", "snip"), action = takeScreenshot
            ),
            PaletteCommand("history", "History", Icons.Outlined.History, "Ctrl+H", keywords = listOf("recent"), action = openHistory),
            PaletteCommand("downloads", "Downloads", Icons.Outlined.FileDownload, "Ctrl+J", keywords = listOf("files"), action = openDownloads),
            PaletteCommand("extensions", "Extensions", Icons.Outlined.Extension, keywords = listOf("addons"), action = openExtensions),
            PaletteCommand("site_permissions", "Site permissions", Icons.Outlined.Tune, action = openSitePermissions),
            PaletteCommand(
                "keyboard_shortcuts", "Keyboard shortcuts", Icons.Outlined.Keyboard,
                keywords = listOf("keys", "help"), action = onShowKeyboardShortcuts
            ),
            PaletteCommand("settings", "Open settings", Icons.Outlined.Settings, "Ctrl+,", keywords = listOf("preferences", "options")) {
                openSettings(null)
            }
        )
    }
    fun paletteHosts(): List<PaletteHost> = terminalState?.hosts.orEmpty().map { host ->
        PaletteHost(id = host.id, name = host.label, address = host.address) {
            openTerminal(TerminalTarget.SavedHost(host.id))
        }
    }

    if (chrome.showCommandPalette) {
        CommandPalette(
            commands = paletteCommands(),
            tabs = browserState.tabs,
            history = history,
            hosts = paletteHosts(),
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
            commands = paletteCommands(),
            hosts = paletteHosts(),
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

/** A screenshot that hasn't come back by then (the engine never answered) is given up. */
private const val SCREENSHOT_TIMEOUT_MILLIS = 10_000L
