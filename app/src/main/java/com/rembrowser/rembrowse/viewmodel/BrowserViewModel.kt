package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.data.BrowsingHistoryStore
import com.rembrowser.rembrowse.data.DeclaredSiteIconStore
import com.rembrowser.rembrowse.data.NoBrowsingHistory
import com.rembrowser.rembrowse.data.NoDeclaredSiteIcons
import com.rembrowser.rembrowse.data.NoSiteIconCache
import com.rembrowser.rembrowse.data.SiteIconCache
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.engine.SitePermissionAsk
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.FindInPageState
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PageContextMenu
import com.rembrowser.rembrowse.model.PageMenuAction
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PageAddress
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.PermissionRequest
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.PrintPreview
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SiteIcons
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.TerminalUrls
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.util.DownloadFileNames
import com.rembrowser.rembrowse.util.UrlResolver
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The most pages the history keeps; the oldest drop off. */
const val MAX_HISTORY_ENTRIES = 1_000

/** Settings lists site permissions by site, then by kind. */
private val sitePermissionOrder = compareBy<SitePermissionEntry>(
    { it.displayHost },
    { it.origin },
    { it.embeddedOrigin.orEmpty() },
    { it.kind }
)

/**
 * How long after the print screen closes a page's own `window.print()` is ignored, so a page
 * that prints in a loop gives the user time to leave it.
 */
private const val PAGE_PRINT_COOLDOWN_MS = 3_000L

/**
 * How long the system's Back waits for a page's answer to it (see [BrowserViewModel.goBack]).
 * After that a page that hasn't answered, a hung one, no longer holds Back up.
 */
internal const val BACK_ANSWER_WAIT_MS = 1_000L

/** The system's Back, offered to the page in [tabId] at [askedAt] (the clock's time) and not answered yet. */
private class BackOffer(val tabId: String, val askedAt: Long)

class BrowserViewModel(
    private val engine: BrowserEngine,
    private val repository: BrowserStateRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val historyStore: BrowsingHistoryStore = NoBrowsingHistory,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The device is a phone (EngineConfig.isPhone): new tabs there ask for mobile sites until
     * the user chooses otherwise (see [forDevice]).
     */
    private val isPhone: Boolean = false,
    /**
     * The site icons kept between launches. They name the sites visited, so they go when the
     * history is cleared, and at each start while history is off (see [clearHistory]).
     */
    private val siteIcons: SiteIconCache = NoSiteIconCache,
    /**
     * The icons each site names for itself, kept between launches with the history and cleared
     * with it, like [siteIcons].
     */
    private val declaredIconStore: DeclaredSiteIconStore = NoDeclaredSiteIcons
) {
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Pages visited, most recent first; one entry per address (see [HistoryEntry]). */
    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /**
     * The icons each site's pages name for themselves (`<link rel="icon">` and the like), by
     * origin, the sites heard from last at the end. SiteIcon tries them before `/favicon.ico`.
     */
    private val _declaredSiteIcons = MutableStateFlow(declaredIconStore.load())
    val declaredSiteIcons: StateFlow<Map<String, List<DeclaredSiteIcon>>> = _declaredSiteIcons.asStateFlow()

    /**
     * What [declaredIconStore] holds: the sites heard from while history was on. Sites heard
     * from while it was off are shown, but never saved, also after history is turned back on.
     */
    private var savedSiteIcons = _declaredSiteIcons.value

    // Kept apart from uiState: typing in the find bar should not recompose the whole screen
    private val _findInPage = MutableStateFlow<FindInPageState?>(null)
    val findInPage: StateFlow<FindInPageState?> = _findInPage.asStateFlow()

    /** The context menu open on a page, if any. Also kept apart from uiState. */
    private val _pageMenu = MutableStateFlow<PageContextMenu?>(null)
    val pageMenu: StateFlow<PageContextMenu?> = _pageMenu.asStateFlow()

    /** The page on RemBrowse's print screen, or null when the screen is closed. */
    private val _printPreview = MutableStateFlow<PrintPreview?>(null)
    val printPreview: StateFlow<PrintPreview?> = _printPreview.asStateFlow()
    private var printPreviewSerial = 0L

    /** When the print screen last closed (null: never), so a page cannot reopen it at once. */
    private var printPreviewClosedAt: Long? = null

    /**
     * Sites waiting for the user's answer about a permission, oldest first. Each pane shows the
     * oldest one for its own tab; requests from tabs not on screen wait until they are.
     */
    private val _permissionRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val permissionRequests: StateFlow<List<PermissionRequest>> = _permissionRequests.asStateFlow()

    /** Every remembered site permission decision, for Settings (see [refreshSitePermissions]). */
    private val _sitePermissions = MutableStateFlow<List<SitePermissionEntry>>(emptyList())
    val sitePermissions: StateFlow<List<SitePermissionEntry>> = _sitePermissions.asStateFlow()

    /** The engine's side of each request in [permissionRequests], by request id. */
    private val pendingPermissions = mutableMapOf<String, SitePermissionAsk>()

    /**
     * "Allow this time" grants per tab: [kinds] for the page's site [origin], kept while the tab
     * stays on that site. In memory only, so they also end when the app does.
     */
    private data class AllowedOnce(val origin: String, val kinds: Set<SitePermissionKind>)

    private val allowedOnce = mutableMapOf<String, AllowedOnce>()

    /**
     * What each tab's page is capturing now ([devices]: camera, microphone), and the origin of
     * that page as the engine committed to it: the site whose decision the capture runs on.
     */
    private data class Capture(val pageOrigin: String?, val devices: Set<SitePermissionKind>)

    private val captures = mutableMapOf<String, Capture>()

    private val sessionListener = object : SessionEventListener {
        override fun onSessionUpdated(tabId: String, update: SessionNavigationUpdate) {
            // The engine also reports addresses it failed to load, such as a rem:// link a page
            // navigated to. Accepting one would turn a web tab into the settings page, so an
            // internal address is never taken from the engine.
            val reportsInternalUrl = update.url?.let(::isInternalUrl) == true
            applySessionUpdate(tabId, if (reportsInternalUrl) update.copy(url = null) else update)
        }

        private fun applySessionUpdate(tabId: String, update: SessionNavigationUpdate) {
            // A new page has its own matches; the bar stays open, ready to search it again
            if (update.isPageStart && _findInPage.value?.tabId == tabId) {
                _findInPage.update { it?.copy(result = null) }
            }
            // A menu about the page that was there goes with it
            if (update.isPageStart && _pageMenu.value?.tabId == tabId) _pageMenu.value = null
            if (update.isPageStart && update.url != null) onNewDocument(tabId, update.url)
            // A location reported outside a page start means a document is now showing. A new
            // session's own about:blank does not count.
            val shownUrl = update.url?.takeUnless { update.isPageStart }
            if (!shownUrl.isNullOrBlank() && shownUrl != BrowserTab.NEW_TAB_URL) tabsWithContent += tabId
            _uiState.update { state ->
                val updatedTabs = state.tabs.map { tab ->
                    // An internal page is drawn by the app. Whatever a session still reports
                    // for that tab (a late about:blank, say) is not what is on screen.
                    if (tab.id != tabId || tab.isInternalPage) {
                        tab
                    } else {
                        // A new document is loading: drop the previous page's title so a page
                        // without a <title> does not keep showing the old one.
                        val startsNewPage = update.isPageStart && update.url != null && update.url != tab.url
                        val title = update.title?.takeIf { it.isNotBlank() }
                            ?: if (startsNewPage) "" else tab.title
                        tab.copy(
                            url = update.url ?: tab.url,
                            title = title,
                            isLoading = update.isLoading ?: tab.isLoading,
                            progress = update.progress ?: tab.progress,
                            canGoBack = update.canGoBack ?: tab.canGoBack,
                            canGoForward = update.canGoForward ?: tab.canGoForward,
                            isSecure = update.isSecure ?: tab.isSecure,
                            crashed = update.crashed ?: tab.crashed
                        )
                    }
                }
                // A page whose process died is not showing its video any more
                val leftFullscreen = update.crashed == true && state.fullscreenTabId == tabId
                val newState = state.copy(
                    tabs = updatedTabs,
                    fullscreenTabId = if (leftFullscreen) null else state.fullscreenTabId
                )
                val syncedInput = if (!newState.isAddressBarFocused && newState.activeTabId == tabId && update.url != null) {
                    addressTextFor(newState.activeTab)
                } else {
                    newState.addressBarInput
                }
                newState.copy(addressBarInput = syncedInput)
            }
            if (update.url != null || update.title != null) {
                persistCurrentState()
            }
            recordHistory(tabId, shownUrl, update.title)
        }

        override fun onNewTabRequested(url: String, openerTabId: String?) {
            // Web content and extensions cannot open the browser's own pages. No tab is
            // created, so the engine reports the window as not opened.
            if (isInternalUrl(url)) return
            val state = _uiState.value
            val opener = state.tabs.firstOrNull { it.id == openerTabId }
            val openerPane = opener?.let { state.paneIndexOf(it.id) }?.takeIf { it >= 0 }
            val tabId = openNewTab(
                url = url,
                assignToPane = openerPane ?: state.activePaneIndex,
                desktopMode = opener?.desktopMode,
                openedByPage = true,
                openerTabId = opener?.id
            )
        }

        override fun onCloseRequested(tabId: String) {
            val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
            // A page may close only a tab a page opened, never one the user opened
            if (!tab.openedByPage) return
            closeReturningToOpener(tabId)
        }

        override fun onPromptRequested(prompt: PagePrompt) {
            _uiState.update { it.copy(pendingPrompts = it.pendingPrompts + prompt) }
        }

        override fun onPromptDismissed(promptId: String) {
            resolvePrompt(promptId)
        }

        override fun onFullScreenChanged(tabId: String, fullScreen: Boolean) {
            if (!fullScreen) {
                _uiState.update { if (it.fullscreenTabId == tabId) it.copy(fullscreenTabId = null) else it }
            } else if (_uiState.value.paneIndexOf(tabId) >= 0) {
                _uiState.update { it.copy(fullscreenTabId = tabId) }
            } else {
                // A page nobody can see has no business taking over the window
                engine.getSession(tabId)?.exitFullScreen()
            }
            // The panes behind a fullscreen page pause, and wake again when it ends
            syncSessions()
        }

        override fun onContextMenuRequested(menu: PageContextMenu) {
            val state = _uiState.value
            val tab = state.tabs.firstOrNull { it.id == menu.tabId } ?: return
            // Only a page on screen can have been clicked, and not one under a fullscreen page
            if (!tab.isWebPage || state.paneIndexOf(tab.id) < 0) return
            if (state.fullscreenTabId != null && state.fullscreenTabId != tab.id) return
            _pageMenu.value = menu
        }

        override fun onPrintReady(tabId: String, pdfPath: String, documentName: String, requestedByPage: Boolean) {
            // A page's print() never replaces what is on the print screen, and cannot reopen it
            // the moment it closes: a page printing in a loop must not lock the user out
            val closedAt = printPreviewClosedAt
            // A clock set back makes the time since negative: no reason to hold the page back
            val justClosed = closedAt != null && clock() - closedAt in 0 until PAGE_PRINT_COOLDOWN_MS
            if (requestedByPage && (_printPreview.value != null || justClosed)) {
                File(pdfPath).delete()
                return
            }
            val state = _uiState.value
            val tab = state.tabs.firstOrNull { it.id == tabId }
            // Only a page on screen prints, and not one under another page in fullscreen
            val onScreen = tab != null && tab.isWebPage && state.paneIndexOf(tab.id) >= 0 &&
                (state.fullscreenTabId == null || state.fullscreenTabId == tab.id)
            if (!onScreen) {
                File(pdfPath).delete()
                return
            }
            // A new print replaces one still on screen
            _printPreview.value?.let { File(it.pdfPath).delete() }
            closeFindInPage()
            _pageMenu.value = null
            _printPreview.value = PrintPreview(
                tabId = tabId,
                pdfPath = pdfPath,
                documentName = documentName,
                pageUrl = tab.url,
                serial = ++printPreviewSerial
            )
        }

        override fun onOpenLinkRequested(tabId: String, url: String, inForeground: Boolean) {
            val state = _uiState.value
            val tab = state.tabs.firstOrNull { it.id == tabId } ?: return
            val pane = state.paneIndexOf(tab.id)
            // Only a page on screen can have been clicked, and not one under a fullscreen page
            if (!tab.isWebPage || pane < 0) return
            if (state.fullscreenTabId != null && state.fullscreenTabId != tab.id) return
            if (inForeground) {
                openNewTab(
                    url = url,
                    assignToPane = pane,
                    desktopMode = tab.desktopMode,
                    openedByPage = true,
                    openerTabId = tab.id
                )
            } else {
                openTabInBackground(url, tab.id)
            }
        }

        override fun onSiteIconsDeclared(tabId: String, pageUrl: String, icons: List<DeclaredSiteIcon>) {
            if (_uiState.value.tabs.none { it.id == tabId }) return
            val origin = SiteOrigins.originOf(pageUrl) ?: return
            _declaredSiteIcons.value = SiteIcons.withSite(_declaredSiteIcons.value, origin, icons)
            // With history off they are kept for this run only, like the history itself
            if (!_uiState.value.settings.keepHistory) return
            val saved = SiteIcons.withSite(savedSiteIcons, origin, icons)
            if (saved === savedSiteIcons) return
            savedSiteIcons = saved
            declaredIconStore.save(saved)
        }

        override fun onCaptureDevicesChanged(tabId: String, devices: Set<SitePermissionKind>, pageOrigin: String?) {
            if (devices.isEmpty()) captures.remove(tabId) else captures[tabId] = Capture(pageOrigin, devices)
        }

        override fun onSitePermissionsChanged() {
            refreshSitePermissions()
        }

        override fun onRecordingChanged(tabId: String, recording: Boolean) {
            val changed = if (recording) recordingTabIds.add(tabId) else recordingTabIds.remove(tabId)
            if (changed) syncSessions()
        }

        override fun onDownloadRequested(request: DownloadRequest) {
            val handler = downloadHandler
            if (handler != null) {
                handler(request)
            } else {
                // Nobody to save it: let go of the connection rather than leave it hanging
                runCatching { request.body.close() }
            }
        }

        override fun onPermissionRequested(ask: SitePermissionAsk) {
            handlePermissionAsk(ask)
        }

        override fun onPermissionRequestWithdrawn(askId: String) {
            if (pendingPermissions.remove(askId) != null) {
                _permissionRequests.update { list -> list.filterNot { it.id == askId } }
            }
        }
    }

    /** Receives the files that pages offer to save. Set by whoever owns the downloads. */
    var downloadHandler: ((DownloadRequest) -> Unit)? = null

    /** Whether the browser window is on screen at all (false while it is minimised or covered). */
    private var windowShown = true

    /** Tabs using the camera or microphone, such as a call. They are never paused. */
    private val recordingTabIds = mutableSetOf<String>()

    /** Tabs in which the engine has shown a document since this browser started. */
    private val tabsWithContent = mutableSetOf<String>()

    /**
     * When each tab was last the focused one, as a count that only rises, so that of several tabs
     * on the same site the one used last can be shown (see [showTabForSite]). In memory only.
     */
    private val tabFocusedAt = mutableMapOf<String, Long>()
    private var focusCount = 0L

    init {
        restoreInitialState()
        // With history off, the icons of the sites visited last time are not kept either
        if (!_uiState.value.settings.keepHistory) {
            siteIcons.clear()
            forgetDeclaredIcons()
        }
    }

    private fun restoreInitialState() {
        val persisted = forDevice(repository.loadState())
        // In place before any tab is created: new tabs take their defaults from the settings
        _uiState.value = BrowserUiState(settings = persisted.settings)

        // "Open a new tab" at launch: the last session's tabs and layout are dropped, while
        // bookmarks and settings are kept.
        val restore = persisted.settings.restoreTabsOnStartup
        val initialTabs = if (restore) persisted.tabs.toMutableList() else mutableListOf()
        if (initialTabs.isEmpty()) {
            initialTabs.add(createTabModel(BrowserTab.NEW_TAB_URL))
        }

        val validTabIds = initialTabs.map { it.id }.toSet()
        val savedLayout = if (restore) persisted.layout?.sanitized(validTabIds) else null
        val layout = savedLayout ?: run {
            // A state from before free-form layouts (or a fresh start): one of the fixed ones
            val mode = if (restore) persisted.layoutMode else LayoutMode.SINGLE_1X1
            val paneTabIds = persisted.paneTabIds.filter { it in validTabIds }.toMutableList()
            ensurePaneSlotsFilled(mode.paneCount, initialTabs, paneTabIds)
            PaneNode.preset(mode, paneTabIds, persisted.splitRatios)
        }

        val paneTabIds = layout.tabIds
        val activePane = persisted.activePaneIndex.coerceIn(0, paneTabIds.lastIndex)
        val activeTab = initialTabs.firstOrNull { it.id == paneTabIds[activePane] }

        engine.setEnhancedPrivacy(persisted.settings.enhancedPrivacy)
        _uiState.value = BrowserUiState(
            layout = layout,
            splitRatios = persisted.splitRatios.clamped().following(layout),
            tabs = initialTabs,
            activePaneIndex = activePane,
            bookmarks = persisted.bookmarks,
            addressBarInput = addressTextFor(activeTab),
            settings = persisted.settings
        )
        // Only tabs that are on screen get a live engine session here. Background tabs are
        // restored the first time they are shown, so launching with many tabs stays cheap.
        syncSessions()
        persistCurrentState()
        refreshSitePermissions()
    }

    /**
     * [state] with the desktop site setting for this kind of device, the first time this version
     * starts (new install or update; [PersistedBrowserState.desktopSiteSetForDevice]): off on a
     * phone, as [setDesktopSiteByDefault] turns it off, so tabs with no site yet follow and tabs
     * already showing one keep their own mode. Tablets and laptops keep what they have. Every save
     * from then on says it was done, so a phone user who turns it back on keeps it.
     */
    private fun forDevice(state: PersistedBrowserState): PersistedBrowserState {
        if (state.desktopSiteSetForDevice || !isPhone) return state
        return state.copy(
            settings = state.settings.copy(desktopSiteByDefault = false),
            tabs = state.tabs.map { if (it.isWebPage) it else it.copy(desktopMode = false) },
            desktopSiteSetForDevice = true
        )
    }

    /**
     * Shows [layout] with [tabs]. Focus goes to the pane of [focusTabId]; when that tab is not
     * on screen (or none is given) it stays on the pane position it was on.
     */
    private fun applyLayout(
        current: BrowserUiState,
        layout: PaneNode,
        focusTabId: String? = null,
        tabs: List<BrowserTab> = current.tabs
    ) {
        val paneTabIds = layout.tabIds
        val activePane = paneTabIds.indexOf(focusTabId).takeIf { it >= 0 }
            ?: current.activePaneIndex.coerceIn(0, paneTabIds.lastIndex)
        val activeTab = tabs.firstOrNull { it.id == paneTabIds[activePane] }
        _uiState.value = current.copy(
            layout = layout,
            splitRatios = current.splitRatios.following(layout),
            tabs = tabs,
            activePaneIndex = activePane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(activeTab)
        )
        syncSessions()
        persistCurrentState()
    }

    /** Rearranges the panes into one of the fixed layouts, keeping the tabs already on screen. */
    fun setLayoutMode(mode: LayoutMode) {
        val current = _uiState.value
        val tabs = current.tabs.toMutableList()
        val paneIds = current.paneTabIds.toMutableList()
        val focusedTabId = current.activeTabId

        ensurePaneSlotsFilled(mode.paneCount, tabs, paneIds)

        val targetPaneIds = paneIds.take(mode.paneCount).toMutableList()
        // When shrinking layouts (e.g. 2x2 -> 1x1), keep the user's focused tab visible
        // while all other tabs stay open in the tab rail.
        if (focusedTabId != null && focusedTabId !in targetPaneIds) {
            targetPaneIds[0] = focusedTabId
        }
        applyLayout(current, PaneNode.preset(mode, targetPaneIds, current.splitRatios), focusedTabId, tabs)
    }

    fun selectTab(tabId: String) {
        val current = _uiState.value
        if (current.tabs.none { it.id == tabId }) return
        val existingPane = current.paneIndexOf(tabId)
        if (existingPane >= 0) {
            focusPane(existingPane)
        } else {
            assignTabToPane(current.activePaneIndex, tabId)
        }
    }

    /** Moves focus to the next ([delta] = 1) or previous ([delta] = -1) tab, wrapping around. */
    fun selectAdjacentTab(delta: Int) {
        val current = _uiState.value
        if (current.tabs.size < 2) return
        val index = current.tabs.indexOfFirst { it.id == current.activeTabId }
        if (index < 0) return
        val next = Math.floorMod(index + delta, current.tabs.size)
        selectTab(current.tabs[next].id)
    }

    /** Resizes the split at [path] by [deltaFraction] of its length. */
    fun resizeSplit(path: String, deltaFraction: Float) {
        _uiState.update { state ->
            val layout = state.layout.updateRatio(path) { it + deltaFraction }
            state.copy(layout = layout, splitRatios = state.splitRatios.following(layout))
        }
    }

    /**
     * Live resize while the divider of the split at [path] is dragged to [ratio], which is kept
     * within the allowed range. Call [commitSplitRatios] when the drag ends.
     */
    fun setSplitRatio(path: String, ratio: Float) {
        _uiState.update { state ->
            val layout = state.layout.updateRatio(path) { ratio }
            if (layout == state.layout) {
                state
            } else {
                state.copy(layout = layout, splitRatios = state.splitRatios.following(layout))
            }
        }
    }

    /** Back to an even split for the divider at [path]. */
    fun resetSplit(path: String) {
        _uiState.update { state ->
            val layout = state.layout.updateRatio(path) { 0.5f }
            state.copy(layout = layout, splitRatios = state.splitRatios.following(layout))
        }
        persistCurrentState()
    }

    /** Resizes the outermost side-by-side split, or the size the next one opens with. */
    fun updateHorizontalSplit(deltaFraction: Float) = resizeFirstSplit(SplitOrientation.ROW, deltaFraction)

    /** Resizes the outermost stacked split, or the size the next one opens with. */
    fun updateVerticalSplit(deltaFraction: Float) = resizeFirstSplit(SplitOrientation.COLUMN, deltaFraction)

    private fun resizeFirstSplit(orientation: SplitOrientation, deltaFraction: Float) {
        val split = _uiState.value.layout.firstSplit(orientation)
        if (split != null) {
            resizeSplit(split.path, deltaFraction)
            return
        }
        _uiState.update { state ->
            val ratios = state.splitRatios
            val updated = if (orientation == SplitOrientation.ROW) {
                ratios.copy(horizontal = ratios.horizontal + deltaFraction)
            } else {
                ratios.copy(vertical = ratios.vertical + deltaFraction)
            }
            state.copy(splitRatios = updated.clamped())
        }
    }

    /** Saves the split once per drag instead of on every pointer move. */
    fun commitSplitRatios() {
        persistCurrentState()
    }

    fun resetSplitRatios() {
        _uiState.update { it.copy(layout = it.layout.withAllRatios(0.5f), splitRatios = SplitRatios.DEFAULT) }
        persistCurrentState()
    }

    fun focusPane(paneIndex: Int) {
        val current = _uiState.value
        if (paneIndex !in 0 until current.paneCount) return
        if (paneIndex == current.activePaneIndex) return
        val paneTab = current.tabForPane(paneIndex)
        _uiState.value = current.copy(
            activePaneIndex = paneIndex,
            addressBarInput = if (current.isAddressBarFocused) {
                current.addressBarInput
            } else {
                addressTextFor(paneTab)
            }
        )
        syncSessions()
        persistCurrentState()
    }

    fun onAddressBarChange(input: String) {
        _uiState.update { it.copy(addressBarInput = input) }
    }

    fun onAddressBarFocusChange(focused: Boolean) {
        _uiState.update { state ->
            val input = if (!focused) addressTextFor(state.activeTab) else state.addressBarInput
            state.copy(isAddressBarFocused = focused, addressBarInput = input)
        }
    }

    fun submitAddressBar() {
        val state = _uiState.value
        if (state.addressBarInput.isBlank()) {
            // Nothing typed: just leave edit mode and show the current address again.
            _uiState.update { it.copy(isAddressBarFocused = false, addressBarInput = addressTextFor(it.activeTab)) }
            return
        }
        val resolved = resolve(state.addressBarInput)
        _uiState.update { it.copy(isAddressBarFocused = false, addressBarInput = resolved) }
        navigatePane(state.activePaneIndex, resolved)
    }

    fun navigatePane(paneIndex: Int, rawInput: String) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        val resolvedUrl = resolve(rawInput)
        if (isInternalUrl(resolvedUrl)) {
            // RemBrowse's own pages are drawn by the app; the web engine is not involved.
            // A page that is open in this tab stays where it is: settings and terminals get
            // their own tab.
            val terminal = TerminalUrls.parse(resolvedUrl)
            if (terminal != null) openTerminal(terminal) else openSettings(SettingsSection.fromUrl(resolvedUrl))
            return
        }
        val session = ensureSessionCreated(tab)

        _uiState.update { state ->
            val updatedTabs = state.tabs.map {
                if (it.id == tab.id) it.copy(url = resolvedUrl, isSecure = false, crashed = false) else it
            }
            val updated = state.copy(tabs = updatedTabs)
            val updatedAddress = if (paneIndex == state.activePaneIndex && !state.isAddressBarFocused) {
                addressTextFor(updated.activeTab)
            } else {
                state.addressBarInput
            }
            updated.copy(addressBarInput = updatedAddress)
        }

        session.loadUrl(resolvedUrl)
        // A tab that was showing an internal page had no session until now, so the new one
        // still has to be told it is on screen.
        syncSessions()
        persistCurrentState()
    }

    fun openNewTab(
        url: String? = null,
        assignToPane: Int? = null,
        desktopMode: Boolean? = null,
        openedByPage: Boolean = false,
        /** The tab whose page asked for this one, when a page did. */
        openerTabId: String? = null
    ): String {
        val resolved = url?.let { if (isInternalUrl(it)) it.trim() else resolve(it) } ?: BrowserTab.NEW_TAB_URL
        val newTab = createTabModel(resolved, desktopMode)
            .copy(openedByPage = openedByPage, openerTabId = openerTabId.takeIf { openedByPage })
        // Created before anything else so the engine can hand this exact session back to a
        // page that asked for a new window. An internal page has no session at all.
        if (!newTab.isInternalPage) ensureSessionCreated(newTab)

        val current = _uiState.value
        val targetPane = (assignToPane ?: current.activePaneIndex).coerceIn(0, current.paneCount - 1)
        // Either at the end of the list, or right after the tab it is opened from: the page
        // that asked for it, or else the tab it replaces on screen.
        val replacedTabId = current.paneTabIds[targetPane]
        val neighbourId = openerTabId ?: replacedTabId
        val neighbourIndex = current.tabs.indexOfFirst { it.id == neighbourId }
        val updatedTabs = if (current.settings.openNewTabsNextToCurrent && neighbourIndex >= 0) {
            current.tabs.toMutableList().apply { add(neighbourIndex + 1, newTab) }
        } else {
            current.tabs + newTab
        }
        applyLayout(current, current.layout.replaceTab(replacedTabId, newTab.id), newTab.id, updatedTabs)
        return newTab.id
    }

    fun closeTab(tabId: String) {
        val current = _uiState.value
        val closedIndex = current.tabs.indexOfFirst { it.id == tabId }
        if (closedIndex < 0) return

        engine.closeSession(tabId)
        tabsWithContent -= tabId
        endPermissionsOf(tabId)
        val remainingTabs = current.tabs.filterNot { it.id == tabId }.toMutableList()
        var layout = current.layout

        // Refill the closed tab's pane in place. Every other pane keeps its tab, so the rest of
        // the layout does not reshuffle (and its web views do not have to re-attach).
        if (layout.contains(tabId)) {
            val onScreen = current.paneTabIds
            val replacement = current.tabs
                .filter { it.id != tabId && it.id !in onScreen }
                .minByOrNull { candidate ->
                    val distance = current.tabs.indexOf(candidate) - closedIndex
                    // Prefer the neighbour just after the closed tab, then the one before it
                    abs(distance) * 2 - if (distance > 0) 1 else 0
                }
                ?: createTabModel(BrowserTab.NEW_TAB_URL).also { remainingTabs.add(it) }
            layout = layout.replaceTab(tabId, replacement.id)
        }

        applyLayout(
            current.copy(pendingPrompts = current.pendingPrompts.filterNot { it.tabId == tabId }),
            layout,
            tabs = remainingTabs
        )
    }

    /** Closes every background tab except [tabId]. Tabs shown in a pane are left alone. */
    fun closeOtherTabs(tabId: String) {
        val current = _uiState.value
        val visible = current.visibleTabIds.toSet()
        current.tabs
            .map { it.id }
            .filter { it != tabId && it !in visible }
            .forEach { closeTab(it) }
    }

    fun assignTabToPane(paneIndex: Int, tabId: String) {
        val current = _uiState.value
        val shownTabId = current.paneTabIds.getOrNull(paneIndex) ?: return
        if (current.tabs.none { it.id == tabId }) return

        val layout = if (current.layout.contains(tabId)) {
            // Swap cleanly so two GeckoView panes do not fight over the same GeckoSession
            current.layout.swapTabs(tabId, shownTabId)
        } else {
            current.layout.replaceTab(shownTabId, tabId)
        }
        applyLayout(current, layout, tabId)
    }

    fun swapPanes(firstPaneIndex: Int, secondPaneIndex: Int) {
        val current = _uiState.value
        val paneTabIds = current.paneTabIds
        val first = paneTabIds.getOrNull(firstPaneIndex) ?: return
        val second = paneTabIds.getOrNull(secondPaneIndex) ?: return
        if (first == second) return
        applyLayout(current, current.layout.swapTabs(first, second))
    }

    /**
     * Drops the tab [tabId] (dragged from the rail) on pane [targetPaneIndex].
     *
     * On an edge the pane is split in that direction and the tab takes the new half. On the
     * centre the tab takes the pane over, and the tab that was there stays open in the rail.
     * A tab that was already on screen moves: the pane it leaves closes. Nothing happens when
     * the layout is full and a new pane would be needed, or when the tab is dropped on itself.
     */
    fun dropTabOnPane(tabId: String, targetPaneIndex: Int, zone: DropZone) {
        val current = _uiState.value
        val targetTabId = current.paneTabIds.getOrNull(targetPaneIndex) ?: return
        if (targetTabId == tabId || current.tabs.none { it.id == tabId }) return

        // Moving a visible tab frees its pane first; the target is another pane, so one remains
        val withoutDragged = current.layout.remove(tabId) ?: return
        val layout = if (zone == DropZone.CENTER) {
            withoutDragged.replaceTab(targetTabId, tabId)
        } else {
            withoutDragged.split(targetTabId, tabId, zone)
        }
        if (!layout.contains(tabId)) return
        applyLayout(current, layout, tabId)
    }

    /**
     * Closes pane [paneIndex]: its neighbour takes the room and its tab stays open in the rail.
     * Focus stays with the page it was on, or moves to the neighbour when that page's pane closed.
     */
    fun closePane(paneIndex: Int = _uiState.value.activePaneIndex) {
        val current = _uiState.value
        val closingTabId = current.paneTabIds.getOrNull(paneIndex) ?: return
        val layout = current.layout.remove(closingTabId) ?: return
        applyLayout(current, layout, current.activeTabId)
    }

    /**
     * Opens the focused tab in a two-pane split next to another tab: a background tab if there
     * is one, otherwise a new start page. The focused tab keeps focus and lands on the side asked for.
     */
    fun splitCurrentTab(currentOnLeft: Boolean = true) {
        val currentId = _uiState.value.activeTabId ?: return
        if (_uiState.value.layoutMode != LayoutMode.SPLIT_1X2) {
            setLayoutMode(LayoutMode.SPLIT_1X2)
        }
        val wantedPane = if (currentOnLeft) 0 else 1
        if (_uiState.value.paneIndexOf(currentId) != wantedPane) {
            swapPanes(0, 1)
        }
        focusPane(_uiState.value.paneIndexOf(currentId))
    }

    /** Swaps the focused pane with the next one. Focus follows the page the user was on. */
    fun swapFocusedPane() {
        val current = _uiState.value
        val count = current.paneCount
        if (count < 2) return
        val from = current.activePaneIndex
        val to = (from + 1) % count
        swapPanes(from, to)
        focusPane(to)
    }

    /** Sizes the first pane to [firstPaneFraction] of the split: 0.5 is even, 0.7 is 70/30. */
    fun setSplitPreset(firstPaneFraction: Float) {
        _uiState.update {
            it.copy(
                layout = it.layout.updateRatio("") { firstPaneFraction },
                splitRatios = SplitRatios(firstPaneFraction, firstPaneFraction).clamped()
            )
        }
        persistCurrentState()
    }

    fun maximizePane(paneIndex: Int) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        applyLayout(current, PaneNode.Leaf(tab.id), tab.id)
    }

    /**
     * Opens the find bar on the focused tab, or puts the cursor back in it if it is open. Only
     * web pages can be searched; RemBrowse's own pages and the start page have nothing to find.
     */
    fun openFindInPage() {
        val tab = _uiState.value.activeTab ?: return
        if (!tab.isWebPage || engine.getSession(tab.id) == null) return
        val current = _findInPage.value
        _findInPage.value = if (current?.tabId == tab.id) {
            current.copy(focusRequests = current.focusRequests + 1)
        } else {
            current?.let { engine.getSession(it.tabId)?.clearFindInPage() }
            FindInPageState(tabId = tab.id, focusRequests = 1)
        }
    }

    /** The text in the find bar changed: the first match from the current place is selected. */
    fun updateFindQuery(query: String) {
        val find = _findInPage.value ?: return
        if (query == find.query) return
        _findInPage.value = find.copy(query = query, result = null)
        if (query.isEmpty()) {
            engine.getSession(find.tabId)?.clearFindInPage()
        } else {
            search(find.tabId, query, backwards = false, matchCase = find.matchCase)
        }
    }

    fun findNext() = findAgain(backwards = false)

    fun findPrevious() = findAgain(backwards = true)

    fun setFindMatchCase(matchCase: Boolean) {
        val find = _findInPage.value ?: return
        if (find.matchCase == matchCase) return
        _findInPage.value = find.copy(matchCase = matchCase, result = null)
        if (find.query.isNotEmpty()) search(find.tabId, find.query, backwards = false, matchCase = matchCase)
    }

    /** Closes the find bar and takes the highlighting off the page. */
    fun closeFindInPage() {
        val find = _findInPage.value ?: return
        engine.getSession(find.tabId)?.clearFindInPage()
        _findInPage.value = null
    }

    private fun findAgain(backwards: Boolean) {
        val find = _findInPage.value ?: return
        if (find.query.isEmpty()) return
        search(find.tabId, find.query, backwards, find.matchCase)
    }

    private fun search(tabId: String, query: String, backwards: Boolean, matchCase: Boolean) {
        engine.getSession(tabId)?.findInPage(query, backwards, matchCase) { result ->
            // A result for text that has since changed, or for a bar since closed, is dropped
            _findInPage.update { find ->
                if (find != null && find.tabId == tabId && find.query == result.query) find.copy(result = result) else find
            }
        }
    }

    /**
     * Opens the print dialog for the web page in [paneIndex] (it can also save the page as a
     * PDF). The browser's own pages and terminals have nothing to print.
     */
    fun printPage(paneIndex: Int = _uiState.value.activePaneIndex) {
        val tab = _uiState.value.tabForPane(paneIndex) ?: return
        if (!tab.isWebPage) return
        // Laying the page out for printing focuses it in Gecko, so the pane takes the focus too
        focusPane(paneIndex)
        engine.getSession(tab.id)?.print()
    }

    /** Closes the print screen without printing, and deletes the laid-out PDF. */
    fun dismissPrintPreview() {
        val preview = _printPreview.value ?: return
        closePrintPreview()
        File(preview.pdfPath).delete()
    }

    private fun closePrintPreview() {
        _printPreview.value = null
        printPreviewClosedAt = clock()
    }

    /**
     * Saves the page on the print screen to Downloads as [fileName] (".pdf" is added when it is
     * missing), as any other download, and closes the screen. False when the PDF is gone or
     * nobody takes downloads; the screen then stays open.
     */
    fun savePrintPreviewAsPdf(fileName: String): Boolean {
        val preview = _printPreview.value ?: return false
        val handler = downloadHandler ?: return false
        val file = File(preview.pdfPath)
        val body = try {
            FileInputStream(file)
        } catch (_: IOException) {
            return false
        }
        closePrintPreview()
        handler(
            DownloadRequest(
                // Not tied to the tab: saving it must never close the tab as a download can
                tabId = null,
                url = preview.pageUrl.orEmpty(),
                // Typed by the user: no folders, and only characters a file name can have
                fileName = DownloadFileNames.sanitize(PrintPreview.pdfFileName(fileName)),
                mimeType = "application/pdf",
                contentLength = file.length(),
                skipConfirmation = true,
                body = body
            )
        )
        // The open stream keeps the file's contents readable until the download has copied them
        file.delete()
        return true
    }

    /**
     * Closes the print screen and hands its PDF to the caller, who now owns the file (it goes to
     * Android's print dialog, for a printer, which deletes it when done).
     */
    fun takePrintPreviewForPrinter(): PrintPreview? {
        val preview = _printPreview.value ?: return null
        closePrintPreview()
        return preview
    }

    fun dismissPageMenu() {
        _pageMenu.value = null
    }

    /**
     * Does [action] from the open page menu, and closes the menu. Copying is left to the screen,
     * which has the clipboard (see [com.rembrowser.rembrowse.model.pageMenuCopyText]).
     */
    fun onPageMenuAction(action: PageMenuAction) {
        val menu = _pageMenu.value ?: return
        _pageMenu.value = null
        val pane = _uiState.value.paneIndexOf(menu.tabId)
        when (action) {
            PageMenuAction.OPEN_LINK_IN_NEW_TAB -> menu.openableLinkUrl?.let { openTabInBackground(it, menu.tabId) }
            PageMenuAction.OPEN_LINK_IN_SPLIT -> menu.openableLinkUrl?.let { openBeside(it, menu.tabId) }
            PageMenuAction.OPEN_MEDIA_IN_NEW_TAB -> menu.openableMediaUrl?.let { openTabInBackground(it, menu.tabId) }
            PageMenuAction.BACK -> if (pane >= 0) goBack(pane)
            PageMenuAction.FORWARD -> if (pane >= 0) goForward(pane)
            PageMenuAction.RELOAD -> engine.getSession(menu.tabId)?.reload()
            PageMenuAction.BOOKMARK -> if (pane >= 0) {
                focusPane(pane)
                toggleBookmarkForActiveTab()
            }
            PageMenuAction.FIND_IN_PAGE -> if (pane >= 0) {
                focusPane(pane)
                openFindInPage()
            }
            PageMenuAction.PRINT -> if (pane >= 0) printPage(pane)
            // Copying happens in the UI, which has the clipboard, and so does the screenshot overlay
            PageMenuAction.SCREENSHOT,
            PageMenuAction.COPY_LINK,
            PageMenuAction.COPY_LINK_TEXT,
            PageMenuAction.COPY_IMAGE,
            PageMenuAction.COPY_MEDIA_ADDRESS,
            PageMenuAction.COPY_PAGE_ADDRESS -> Unit
        }
    }

    /**
     * Opens [url] in a new tab that stays in the background, next to the tab it came from (when
     * Settings say so), as a link opened in a new tab does on a desktop. It starts loading at once.
     */
    fun openTabInBackground(url: String, openerTabId: String?): String {
        val current = _uiState.value
        val opener = current.tabs.firstOrNull { it.id == openerTabId }
        val newTab = createTabModel(resolve(url), opener?.desktopMode).copy(openedByPage = opener != null)
        if (!newTab.isInternalPage) ensureSessionCreated(newTab)
        val openerIndex = current.tabs.indexOfFirst { it.id == openerTabId }
        val tabs = if (current.settings.openNewTabsNextToCurrent && openerIndex >= 0) {
            current.tabs.toMutableList().apply { add(openerIndex + 1, newTab) }
        } else {
            current.tabs + newTab
        }
        // The layout is unchanged; this pauses the new page, which nobody can see yet
        applyLayout(current, current.layout, current.activeTabId, tabs)
        return newTab.id
    }

    /**
     * Opens [url] in a new pane beside the one showing [besideTabId], which keeps focus. When no
     * pane can be added, the page opens in a background tab instead.
     */
    fun openBeside(url: String, besideTabId: String) {
        val current = _uiState.value
        if (current.paneCount >= PaneNode.MAX_PANES || current.paneIndexOf(besideTabId) < 0) {
            openTabInBackground(url, besideTabId)
            return
        }
        val newTabId = openTabInBackground(url, besideTabId)
        val withNewTab = _uiState.value
        applyLayout(withNewTab, withNewTab.layout.split(besideTabId, newTabId, DropZone.RIGHT), besideTabId)
    }

    /**
     * Shows a tab on [url]'s site, as for a tapped site notification: of several, the one focused
     * last (then one already on screen); with none, [url] opens in a new tab. A page fullscreen in
     * another tab leaves fullscreen, so the tab shown is not hidden behind it. Returns the tab
     * shown, or null when [url] is not a web address.
     */
    fun showTabForSite(url: String): String? {
        val origin = SiteOrigins.originOf(url) ?: return null
        val state = _uiState.value
        val onScreen = state.visibleTabIds.toSet()
        val match = state.tabs
            .filter { it.isWebPage && SiteOrigins.originOf(it.url) == origin }
            .maxWithOrNull(compareBy<BrowserTab>({ tabFocusedAt[it.id] ?: 0L }, { it.id in onScreen }))
        val fullscreenId = state.fullscreenTabId
        if (fullscreenId != null && fullscreenId != match?.id) exitFullscreen()
        if (match == null) return openNewTab(url)
        selectTab(match.id)
        return match.id
    }

    /** Takes the fullscreen page back into its pane. The layout was never changed, so it is as it was. */
    fun exitFullscreen() {
        val tabId = _uiState.value.fullscreenTabId ?: return
        engine.getSession(tabId)?.exitFullScreen()
        _uiState.update { it.copy(fullscreenTabId = null) }
        syncSessions()
    }

    /**
     * The window was minimised or covered ([shown] false), or came back. While it is away no
     * page is active, as for a background tab: it stops painting and its timers fire at most
     * once a second (RemBrowse keeps background pages running: see EngineConfig). Pages using
     * the camera or microphone stay active.
     */
    fun setWindowShown(shown: Boolean) {
        if (windowShown == shown) return
        windowShown = shown
        syncSessions()
    }

    /** The system's Back offered to a page and not answered yet, if any; see [goBack]. */
    private var backOffer: BackOffer? = null

    /** The pane of a system Back waiting for the page's answer to the one before it, if any. */
    private var backWaitingPane: Int? = null

    /**
     * Back in the pane's tab: its history, or, at the first page of a tab a page opened, closing
     * it and showing the tab that opened it again (see [BrowserUiState.canNavigateBack]).
     *
     * With [pageFirst] (the system's Back: the key and the gesture) the page is offered it first,
     * and the above happens only when the page has nothing of its own to close, such as a
     * `<dialog>` or a popover. The page answers later: a Back pressed meanwhile waits for that
     * answer and is then offered in turn, so it is neither lost nor done twice. One waits at most;
     * further presses meanwhile are dropped. A page that hasn't answered within
     * [BACK_ANSWER_WAIT_MS] holds Back up no longer: the next press goes back without asking it.
     * The toolbar's arrow, `Alt+Left`, the page menu and the touchpad's swipe go back at once, as
     * on a desktop.
     */
    fun goBack(paneIndex: Int = _uiState.value.activePaneIndex, pageFirst: Boolean = false) {
        if (pageFirst) {
            offerBackToPage(paneIndex)
            return
        }
        _uiState.value.tabForPane(paneIndex)?.let(::navigateBack)
    }

    private fun navigateBack(tab: BrowserTab) {
        if (!tab.canGoBack && _uiState.value.canNavigateBack(tab)) {
            closeReturningToOpener(tab.id)
            return
        }
        engine.getSession(tab.id)?.goBack()
    }

    private fun offerBackToPage(paneIndex: Int) {
        val state = _uiState.value
        val unanswered = backOffer
        if (unanswered != null) {
            val tabStillOpen = state.tabs.any { it.id == unanswered.tabId }
            if (tabStillOpen && clock() - unanswered.askedAt in 0 until BACK_ANSWER_WAIT_MS) {
                backWaitingPane = paneIndex
                return
            }
            // That page never answered (it hangs) or its tab is gone: an answer that still comes is
            // ignored, and the page isn't asked again for this press
            backOffer = null
            backWaitingPane = null
            val tab = state.tabForPane(paneIndex) ?: return
            if (tab.id == unanswered.tabId) {
                navigateBack(tab)
                return
            }
        }
        val tab = state.tabForPane(paneIndex) ?: return
        val session = engine.getSession(tab.id) ?: return navigateBack(tab)
        val offer = BackOffer(tab.id, clock())
        backOffer = offer
        session.handleBack(
            onHandled = { backAnswered(offer, handled = true) },
            onUnhandled = { backAnswered(offer, handled = false) }
        )
    }

    private fun backAnswered(offer: BackOffer, handled: Boolean) {
        if (backOffer !== offer) return
        backOffer = null
        if (!handled) _uiState.value.tabs.firstOrNull { it.id == offer.tabId }?.let(::navigateBack)
        val waiting = backWaitingPane ?: return
        backWaitingPane = null
        offerBackToPage(waiting)
    }

    /**
     * Closes a tab a page opened and shows the page that opened it where it was, as a popup
     * window closing would: a sign-in popup, or a link that opened in a new tab, opens in its
     * opener's pane.
     */
    private fun closeReturningToOpener(tabId: String) {
        val state = _uiState.value
        val tab = state.tabs.firstOrNull { it.id == tabId } ?: return
        val opener = tab.openerTabId?.takeIf { id -> state.tabs.any { it.id == id } }
        val pane = state.paneIndexOf(tabId)
        if (opener != null && pane >= 0) {
            val openerPane = state.paneIndexOf(opener)
            if (openerPane < 0) assignTabToPane(pane, opener) else if (pane == state.activePaneIndex) focusPane(openerPane)
        }
        closeTab(tabId)
    }

    fun goForward(paneIndex: Int = _uiState.value.activePaneIndex) {
        val tab = _uiState.value.tabForPane(paneIndex) ?: return
        engine.getSession(tab.id)?.goForward()
    }

    fun reloadOrStop(paneIndex: Int = _uiState.value.activePaneIndex) {
        val tab = _uiState.value.tabForPane(paneIndex) ?: return
        if (!tab.isWebPage) return
        val session = engine.getSession(tab.id) ?: return
        if (tab.isLoading) {
            session.stop()
        } else {
            session.reload()
        }
    }

    fun toggleDesktopMode(paneIndex: Int = _uiState.value.activePaneIndex) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        val nextMode = !tab.desktopMode
        // An internal page has no session; the choice is stored and used if the tab later
        // navigates to a website.
        if (!tab.isInternalPage) ensureSessionCreated(tab).setDesktopMode(nextMode)

        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map {
                    if (it.id == tab.id) it.copy(desktopMode = nextMode) else it
                }
            )
        }
        persistCurrentState()
    }

    fun toggleBookmarkForActiveTab() {
        val current = _uiState.value
        val activeTab = current.activeTab ?: return
        if (!activeTab.isWebPage) return
        val existing = current.bookmarks.firstOrNull { it.url == activeTab.url }
        val updatedBookmarks = if (existing != null) {
            current.bookmarks.filterNot { it.id == existing.id }
        } else {
            listOf(
                BookmarkItem(
                    id = idGenerator(),
                    title = activeTab.displayTitle,
                    url = activeTab.url
                )
            ) + current.bookmarks
        }
        _uiState.value = current.copy(bookmarks = updatedBookmarks)
        persistCurrentState()
    }

    fun removeBookmark(bookmarkId: String) {
        _uiState.update { state ->
            state.copy(bookmarks = state.bookmarks.filterNot { it.id == bookmarkId })
        }
        persistCurrentState()
    }

    /**
     * Opens a saved page, a pinned site among them, without piling up tabs on it. A tab showing
     * the page or one below it ([PageAddress.isUnder]) is shown instead: one already in a pane
     * first, else the one used last. When that is the active tab and it has moved on to another
     * page below the bookmark, it goes back to the bookmarked page; on the page itself nothing
     * changes. With no such tab the page opens in place when the active tab is still empty, else
     * in a new tab.
     */
    fun openBookmark(url: String) {
        val bookmark = PageAddress.of(url)
        val match = bookmark?.let(::tabForBookmark)
        if (match == null) {
            openInEmptyOrNewTab(url)
            return
        }
        val state = _uiState.value
        val fullscreenId = state.fullscreenTabId
        if (fullscreenId != null && fullscreenId != match.id) exitFullscreen()
        if (match.id != state.activeTabId) {
            selectTab(match.id)
        } else if (PageAddress.of(match.url)?.isSamePageAs(bookmark) != true) {
            navigatePane(state.activePaneIndex, url)
        }
    }

    /**
     * The tab to show for a bookmark: of the web pages at or below it, one in a pane (the
     * focused one first), else the one focused last; null when there is none.
     */
    private fun tabForBookmark(bookmark: PageAddress): BrowserTab? {
        val state = _uiState.value
        val onScreen = state.visibleTabIds.toSet()
        return state.tabs
            .filter { it.isWebPage && PageAddress.of(it.url)?.isUnder(bookmark) == true }
            .maxWithOrNull(compareBy<BrowserTab>({ it.id in onScreen }, { tabFocusedAt[it.id] ?: 0L }))
    }

    /** Opens a saved page in a new tab, even when a tab on its site is open already. */
    fun openBookmarkInNewTab(url: String) {
        openNewTab(url)
    }

    /** Opens [url] in place when the active tab is still empty, otherwise in a new tab. */
    private fun openInEmptyOrNewTab(url: String) {
        val current = _uiState.value
        if (current.activeTab?.isNewTabPage == true) {
            navigatePane(current.activePaneIndex, url)
        } else {
            openNewTab(url)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(settings = it.settings.copy(themeMode = mode)) }
        persistCurrentState()
    }

    fun setAccentColor(accent: AccentColor) {
        _uiState.update { it.copy(settings = it.settings.copy(accent = accent)) }
        persistCurrentState()
    }

    fun setSystemColors(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(useSystemColors = enabled)) }
        persistCurrentState()
    }

    fun setRestoreTabsOnStartup(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(restoreTabsOnStartup = enabled)) }
        persistCurrentState()
    }

    fun setOpenNewTabsNextToCurrent(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(openNewTabsNextToCurrent = enabled)) }
        persistCurrentState()
    }

    /**
     * Sets what new tabs ask sites for. Tabs with no website in them yet (start pages, the
     * settings tab) follow along, so the choice applies to the next address typed into them;
     * tabs already showing a site keep their own setting.
     */
    fun setDesktopSiteByDefault(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(desktopSiteByDefault = enabled),
                tabs = state.tabs.map { if (it.isWebPage) it else it.copy(desktopMode = enabled) }
            )
        }
        _uiState.value.tabs.filterNot { it.isWebPage }.forEach { tab ->
            engine.getSession(tab.id)?.setDesktopMode(enabled)
        }
        persistCurrentState()
    }

    fun setAskBeforeDownloading(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(askBeforeDownloading = enabled)) }
        persistCurrentState()
    }

    /** Whether a touchpad's two-finger sideways swipe goes back and forward. */
    fun setSwipeNavigation(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(swipeNavigation = enabled)) }
        persistCurrentState()
    }

    /** Whether two-finger swipes go the other way round (left is Back). */
    fun setReverseSwipeDirection(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(reverseSwipeDirection = enabled)) }
        persistCurrentState()
    }

    /** Whether the tab rail is expanded, with tab titles, on windows wide enough for it. */
    fun setTabRailExpanded(expanded: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(tabRailExpanded = expanded)) }
        persistCurrentState()
    }

    /** Whether visited pages are recorded. Turning it off keeps the history already there. */
    fun setKeepHistory(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(keepHistory = enabled)) }
        persistCurrentState()
    }

    /** Changes how pages reach the screen. Pages on screen are shown the new way at once. */
    fun setPageRendering(rendering: PageRendering) {
        _uiState.update { it.copy(settings = it.settings.copy(pageRendering = rendering)) }
        persistCurrentState()
    }

    /**
     * Opens a page from the history: in place when the active tab is still empty, otherwise in a
     * new tab. Unlike a bookmark it never switches to another tab on the site, which may be
     * showing a different page of it.
     */
    fun openHistoryEntry(url: String) = openInEmptyOrNewTab(url)

    fun removeHistoryEntry(url: String) {
        updateHistory { entries -> entries.filterNot { it.url == url } }
    }

    /**
     * Forgets every page visited, and the site icons kept with them: an icon is kept under its
     * site's address, so the icons left behind would still say where the user had been.
     */
    fun clearHistory() {
        updateHistory { emptyList() }
        siteIcons.clear()
        forgetDeclaredIcons()
    }

    /** Forgets which icons the sites visited named for themselves, here and on the device. */
    private fun forgetDeclaredIcons() {
        _declaredSiteIcons.value = emptyMap()
        savedSiteIcons = emptyMap()
        declaredIconStore.save(emptyMap())
    }

    /**
     * A tab showed a page ([shownUrl]) or learned its title. Only ordinary web pages are kept:
     * the start page, the settings page, extension pages and other internal addresses are not.
     */
    private fun recordHistory(tabId: String, shownUrl: String?, title: String?) {
        if (!_uiState.value.settings.keepHistory) return
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (!shownUrl.isNullOrBlank() && UrlResolver.isWebUrl(shownUrl)) {
            val visit = HistoryEntry(url = shownUrl, title = tab.title, visitedAtMillis = clock())
            updateHistory { entries -> (listOf(visit) + entries.filterNot { it.url == shownUrl }).take(MAX_HISTORY_ENTRIES) }
        } else if (!title.isNullOrBlank() && UrlResolver.isWebUrl(tab.url)) {
            // The title usually arrives after the address; it belongs to the entry just made
            val current = _history.value
            val index = current.indexOfFirst { it.url == tab.url }
            if (index >= 0 && current[index].title != title) {
                updateHistory { entries -> entries.map { if (it.url == tab.url) it.copy(title = title) else it } }
            }
        }
    }

    private fun updateHistory(change: (List<HistoryEntry>) -> List<HistoryEntry>) {
        val updated = change(_history.value)
        if (updated == _history.value) return
        _history.value = updated
        historyStore.save(updated)
    }

    /**
     * Closes the tab a page opened only to hand over a file. The engine never shows a document
     * in such a tab (the response became a download instead), so it would stay behind as an
     * empty page once the download is over.
     */
    fun closeTabLeftBehindByDownload(request: DownloadRequest) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == request.tabId } ?: return
        if (tab.openedByPage && tab.id !in tabsWithContent) closeTab(tab.id)
    }

    fun setSearchEngine(engine: SearchEngine) {
        _uiState.update { it.copy(settings = it.settings.copy(searchEngine = engine)) }
        persistCurrentState()
    }

    /**
     * Shows the settings page, in the tab that already has it open if there is one. Passing a
     * [section] jumps to it; otherwise an open settings tab stays where it was.
     */
    fun openSettings(section: SettingsSection? = null) {
        val state = _uiState.value
        val existing = state.tabs.firstOrNull { it.isSettingsPage }
        val active = state.activeTab
        val url = (section ?: SettingsSection.APPEARANCE).url
        when {
            existing != null -> {
                if (section != null) showInternalPage(existing.id, section.url)
                selectTab(existing.id)
            }
            // An untouched start page becomes the settings page instead of being left behind
            active != null && active.isNewTabPage && !active.canGoBack && !active.canGoForward ->
                showInternalPage(active.id, url)
            else -> openNewTab(url)
        }
    }

    /**
     * Opens a terminal tab on [target] (a new terminal offers the saved hosts) and returns its
     * id. An untouched start page becomes the terminal; otherwise it gets a tab of its own.
     */
    fun openTerminal(target: TerminalTarget = TerminalTarget.Picker): String {
        val url = TerminalUrls.urlFor(target)
        val active = _uiState.value.activeTab
        return if (active != null && active.isNewTabPage && !active.canGoBack && !active.canGoForward) {
            showInternalPage(active.id, url)
            active.id
        } else {
            openNewTab(url)
        }
    }

    /** Terminal tab [tabId] was pointed at [target] (a host picked in a new terminal). */
    fun showTerminalTarget(tabId: String, target: TerminalTarget) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.isTerminal) return
        showInternalPage(tabId, TerminalUrls.urlFor(target))
    }

    /**
     * What terminal tab [tabId] is called now: the title its shell set, or the server's name.
     * Not saved on its own: shells change it at every prompt.
     */
    fun setTerminalTitle(tabId: String, title: String) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId && it.isTerminal && it.title != title) it.copy(title = title) else it })
        }
    }

    /** Switches the settings tab [tabId] to [section]; its address follows, as on any page. */
    fun showSettingsSection(tabId: String, section: SettingsSection) {
        showInternalPage(tabId, section.url)
    }

    /**
     * Shows one of the app's own pages in [tabId]. Only called for a tab that already shows an
     * internal page or is an untouched start page, so no web page or history is lost.
     */
    private fun showInternalPage(tabId: String, url: String) {
        // The app draws this page, so the tab needs no engine session. A start page has one;
        // left open it would cost a content process and keep reporting about:blank for this tab.
        engine.closeSession(tabId)
        endPermissionsOf(tabId)
        _uiState.update { state ->
            val updated = state.copy(
                tabs = state.tabs.map {
                    if (it.id == tabId) {
                        it.copy(
                            url = url,
                            title = "",
                            isLoading = false,
                            progress = 0,
                            canGoBack = false,
                            canGoForward = false,
                            isSecure = false,
                            crashed = false
                        )
                    } else {
                        it
                    }
                }
            )
            val address = if (!state.isAddressBarFocused && updated.activeTabId == tabId) url else state.addressBarInput
            updated.copy(addressBarInput = address)
        }
        persistCurrentState()
    }

    fun setEnhancedPrivacy(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(enhancedPrivacy = enabled)) }
        engine.setEnhancedPrivacy(enabled)
        persistCurrentState()
    }

    /** Removes a page dialog once the UI has answered it (or the page withdrew it). */
    fun resolvePrompt(promptId: String) {
        _uiState.update { state ->
            state.copy(pendingPrompts = state.pendingPrompts.filterNot { it.id == promptId })
        }
    }

    fun shutdown() {
        dismissPrintPreview()
        persistCurrentState()
        pendingPermissions.keys.toList().forEach { resolvePermission(it, PermissionAnswer.DISMISS) }
        allowedOnce.clear()
        captures.clear()
        engine.closeAll()
    }

    /**
     * Answers the site permission request [requestId]. ALLOW and BLOCK are remembered for the
     * site, and also answer the same question waiting from that site in any tab. ALLOW_ONCE lasts
     * while the tab stays on the site, and answers that tab's other requests it covers; it counts
     * as DISMISS for a request that does not offer it. DISMISS refuses this request only.
     */
    fun answerPermission(requestId: String, answer: PermissionAnswer) {
        val request = _permissionRequests.value.firstOrNull { it.id == requestId } ?: return
        val ask = pendingPermissions[requestId] ?: return
        val given = if (answer == PermissionAnswer.ALLOW_ONCE && !request.offersAllowOnce) PermissionAnswer.DISMISS else answer
        resolvePermission(requestId, given)
        val embeddedOrigin = ask.embeddedOrigin
        when (given) {
            PermissionAnswer.ALLOW, PermissionAnswer.BLOCK -> {
                _permissionRequests.value
                    .filter { other ->
                        other.origin == request.origin && request.kinds.containsAll(other.kinds) &&
                            pendingPermissions[other.id]?.embeddedOrigin == embeddedOrigin
                    }
                    .forEach { resolvePermission(it.id, given) }
                val state = if (given == PermissionAnswer.ALLOW) SitePermissionState.ALLOW else SitePermissionState.BLOCK
                request.kinds.forEach { kind -> rememberSitePermission(request.origin, kind, state, embeddedOrigin) }
                if (given == PermissionAnswer.BLOCK) {
                    // As a Block in Settings does: other tabs' "Allow this time" streams on the site end
                    request.kinds.forEach { forgetAllowedOnce(request.origin, it) }
                    stopCapturing(request.origin, request.kinds)
                }
            }
            PermissionAnswer.ALLOW_ONCE -> {
                val earlier = allowedOnce[request.tabId]?.takeIf { it.origin == request.origin }?.kinds.orEmpty()
                allowedOnce[request.tabId] = AllowedOnce(request.origin, earlier + request.kinds)
                _permissionRequests.value
                    .filter { other ->
                        other.tabId == request.tabId && other.origin == request.origin &&
                            request.kinds.containsAll(other.kinds)
                    }
                    .forEach { resolvePermission(it.id, PermissionAnswer.ALLOW_ONCE) }
            }
            PermissionAnswer.DISMISS -> Unit
        }
    }

    /** Reloads [sitePermissions] from the engine, which keeps the decisions. */
    fun refreshSitePermissions() {
        engine.loadSitePermissions { entries -> _sitePermissions.value = entries.sortedWith(sitePermissionOrder) }
    }

    /**
     * Remembers [state] for [kind] on [origin], or forgets the decision when [state] is null, so
     * the site asks again. For STORAGE_ACCESS, [embeddedOrigin] names the embedded site (see
     * [SitePermissionEntry.embeddedOrigin]); null changes every embedded site's decision.
     */
    fun setSitePermission(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState?,
        embeddedOrigin: String? = null
    ) {
        val site = SiteOrigins.originOf(origin) ?: return
        engine.setSitePermission(site, kind, state, embeddedOrigin)
        forgetAllowedOnce(site, kind)
        if (state != SitePermissionState.ALLOW) stopCapturing(site, setOf(kind))
        if (state != null) {
            if (kind == SitePermissionKind.STORAGE_ACCESS && embeddedOrigin == null) {
                // Only the embedded sites already listed can change: there is no new one to name
                _sitePermissions.update { list ->
                    list.map { if (it.origin == site && it.kind == kind) it.copy(state = state) else it }
                }
            } else {
                rememberSitePermission(site, kind, state, embeddedOrigin)
            }
        } else {
            _sitePermissions.update { list ->
                list.filterNot {
                    it.origin == site && it.kind == kind && (embeddedOrigin == null || it.embeddedOrigin == embeddedOrigin)
                }
            }
        }
    }

    /** Forgets every decision remembered for [origin], so it asks again for anything. */
    fun clearSitePermissions(origin: String) {
        val site = SiteOrigins.originOf(origin) ?: return
        engine.clearSitePermissions(site)
        forgetAllowedOnce(site, kind = null)
        stopCapturing(site, setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE))
        _sitePermissions.update { list -> list.filterNot { it.origin == site } }
    }

    /**
     * A decision for [site] no longer allows [kinds]. A page already capturing one of them keeps
     * its stream until it reloads, so the tabs on [site] that are using it now are reloaded;
     * nothing else is. Location has no such signal: a page following it stops only when it next asks.
     */
    private fun stopCapturing(site: String, kinds: Set<SitePermissionKind>) {
        // The page that is capturing, as the engine committed to it: the address bar may already
        // show a navigation that never happened (a download, a 204, window.stop())
        captures
            .filter { (_, capture) -> capture.pageOrigin == site && capture.devices.any { it in kinds } }
            .keys.toList()
            .forEach { tabId ->
                captures.remove(tabId)
                engine.getSession(tabId)?.reload()
            }
    }

    private fun handlePermissionAsk(ask: SitePermissionAsk) {
        if (ask.kinds.isEmpty() || ask.id in pendingPermissions) {
            ask.answer(PermissionAnswer.DISMISS)
            return
        }
        // What the user already decided for the site answers it without asking again
        if (ask.kinds.any { ask.stored[it] == SitePermissionState.BLOCK }) {
            ask.answer(PermissionAnswer.DISMISS)
            return
        }
        val undecided = ask.kinds.filterNot { ask.stored[it] == SitePermissionState.ALLOW }.toSet()
        if (undecided.isEmpty()) {
            ask.answer(PermissionAnswer.ALLOW)
            return
        }
        // Nothing to ask the user in: an unknown tab, or one not showing a web page
        val tab = _uiState.value.tabs.firstOrNull { it.id == ask.tabId }
        if (tab == null || !tab.isWebPage) {
            ask.answer(PermissionAnswer.DISMISS)
            return
        }
        // The ask's origin is the page in the tab's address bar, whatever frame asked
        val grants = allowedOnce[tab.id]?.takeIf { it.origin == ask.origin }
        if (grants != null && grants.kinds.containsAll(undecided)) {
            ask.answer(PermissionAnswer.ALLOW_ONCE)
            return
        }
        val request = PermissionRequest(
            id = ask.id,
            tabId = tab.id,
            origin = ask.origin,
            displayHost = SiteOrigins.displayHost(ask.origin),
            kinds = undecided,
            embeddedHost = ask.embeddedOrigin?.let(SiteOrigins::displayHost),
            offersAllowOnce = undecided.all { it.offersAllowOnce }
        )
        pendingPermissions[request.id] = ask
        _permissionRequests.update { it + request }
    }

    /** Takes request [id] off the list and gives the engine [answer]. */
    private fun resolvePermission(id: String, answer: PermissionAnswer) {
        val ask = pendingPermissions.remove(id)
        _permissionRequests.update { list -> list.filterNot { it.id == id } }
        ask?.answer(answer)
    }

    /**
     * Tab [tabId] started loading [url] as a new document (not a change within the page). Its
     * requests belong to the document that made them, which is going, so they are withdrawn,
     * also for a reload or a page of the same site. Its one-time grants end with the site.
     */
    private fun onNewDocument(tabId: String, url: String) {
        pendingPermissions
            .filter { (_, ask) -> ask.tabId == tabId }
            .keys.toList()
            .forEach { resolvePermission(it, PermissionAnswer.DISMISS) }
        if (allowedOnce[tabId]?.origin != SiteOrigins.originOf(url)) allowedOnce.remove(tabId)
    }

    /** Tab [tabId] closed or stopped showing web pages: its requests and one-time grants end. */
    private fun endPermissionsOf(tabId: String) {
        pendingPermissions
            .filter { (_, ask) -> ask.tabId == tabId }
            .keys.toList()
            .forEach { resolvePermission(it, PermissionAnswer.DISMISS) }
        allowedOnce.remove(tabId)
        captures.remove(tabId)
    }

    /** Drops one-time grants for [kind] (every kind when null) on [site], so its new decision counts. */
    private fun forgetAllowedOnce(site: String, kind: SitePermissionKind?) {
        allowedOnce.entries.toList().forEach { (tabId, grant) ->
            if (grant.origin != site) return@forEach
            val remaining = if (kind == null) emptySet() else grant.kinds - kind
            if (remaining.isEmpty()) allowedOnce.remove(tabId) else allowedOnce[tabId] = grant.copy(kinds = remaining)
        }
    }

    /** Puts a decision the engine has just been told about into [sitePermissions] at once. */
    private fun rememberSitePermission(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState,
        embeddedOrigin: String?
    ) {
        val entry = SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, state, embeddedOrigin)
        _sitePermissions.update { list ->
            (list.filterNot { it.origin == origin && it.kind == kind && it.embeddedOrigin == embeddedOrigin } + entry)
                .sortedWith(sitePermissionOrder)
        }
    }

    /** What the address bar shows for [tab]; the start page shows an empty field. */
    private fun addressTextFor(tab: BrowserTab?): String {
        return tab?.takeUnless { it.isNewTabPage }?.url.orEmpty()
    }

    /** A new tab. Unless told otherwise it asks for desktop or mobile sites as Settings say. */
    private fun createTabModel(url: String, desktopMode: Boolean? = null): BrowserTab {
        return BrowserTab(
            id = idGenerator(),
            url = url,
            desktopMode = desktopMode ?: _uiState.value.settings.desktopSiteByDefault
        )
    }

    private fun isInternalUrl(url: String): Boolean =
        url.trim().startsWith(BrowserTab.INTERNAL_SCHEME, ignoreCase = true)

    /** Typed text to an address, using the search engine chosen in Settings. */
    private fun resolve(input: String): String =
        UrlResolver.resolveInput(input, _uiState.value.settings.searchEngine.queryUrl)

    private fun ensureSessionCreated(tab: BrowserTab): BrowserSessionHandle =
        engine.getSession(tab.id) ?: engine.createSession(
            tabId = tab.id,
            // Reached for an internal page only when its tab is about to load a website: the
            // session starts empty and the caller loads the address.
            initialUrl = if (tab.isInternalPage) "" else tab.url,
            desktopMode = tab.desktopMode,
            listener = sessionListener
        )

    private fun ensurePaneSlotsFilled(
        requiredPaneCount: Int,
        tabs: MutableList<BrowserTab>,
        paneTabIds: MutableList<String>
    ) {
        val distinctIds = LinkedHashSet(paneTabIds)
        paneTabIds.clear()
        paneTabIds.addAll(distinctIds)

        while (paneTabIds.size < requiredPaneCount) {
            val unusedExisting = tabs.firstOrNull { it.id !in paneTabIds }
            if (unusedExisting != null) {
                paneTabIds.add(unusedExisting.id)
            } else {
                val newTab = createTabModel(BrowserTab.NEW_TAB_URL)
                tabs.add(newTab)
                paneTabIds.add(newTab.id)
            }
        }
    }

    /**
     * Makes the engine match the UI state: tabs shown in a pane get a live session and stay
     * painted, background tabs are paused, and extensions learn which single tab has focus.
     */
    private fun syncSessions() {
        val state = _uiState.value
        val visibleIds = state.visibleTabIds.toSet()
        // Fullscreen ends with the tab, or when its pane goes on to show something else
        var fullscreenId = state.fullscreenTabId
        if (fullscreenId != null && fullscreenId !in visibleIds) {
            engine.getSession(fullscreenId)?.exitFullScreen()
            _uiState.update { it.copy(fullscreenTabId = null) }
            fullscreenId = null
        }
        val activeId = state.activeTabId
        val tabIds = state.tabs.map { it.id }.toSet()
        recordingTabIds.retainAll(tabIds)
        // Every change of focus comes through here
        tabFocusedAt.keys.retainAll(tabIds)
        if (activeId != null) tabFocusedAt[activeId] = ++focusCount
        // The find bar belongs to the focused tab: it closes when focus moves on or the tab goes
        _findInPage.value?.let { find ->
            if (find.tabId != activeId) closeFindInPage()
        }
        // So does a page menu, when its page leaves the screen
        _pageMenu.value?.let { menu ->
            if (menu.tabId !in visibleIds) _pageMenu.value = null
        }
        state.tabs.forEach { tab ->
            val inPane = tab.id in visibleIds
            // Internal pages are drawn by the app and never get a session of their own
            val session = if (inPane && !tab.isInternalPage) {
                ensureSessionCreated(tab)
            } else {
                engine.getSession(tab.id) ?: return@forEach
            }
            // A pane is only worth running while someone can see it: not under a fullscreen
            // page, and not while the whole window is away. A call keeps running wherever it is.
            val seen = inPane && windowShown && (fullscreenId == null || tab.id == fullscreenId)
            session.setVisible(seen || tab.id in recordingTabIds)
            session.setFocusedForExtensions(tab.id == activeId)
        }
    }

    private fun persistCurrentState() {
        val state = _uiState.value
        repository.saveState(
            PersistedBrowserState(
                // For a version that only knows the first fixed layouts: the nearest one
                layoutMode = state.layoutMode?.takeUnless { it == LayoutMode.SPLIT_1_2 } ?: LayoutMode.GRID_2X2,
                splitRatios = state.splitRatios,
                tabs = state.tabs,
                paneTabIds = state.paneTabIds,
                activePaneIndex = state.activePaneIndex,
                bookmarks = state.bookmarks,
                settings = state.settings,
                layout = state.layout,
                // Set for the device at start (forDevice), before the first save
                desktopSiteSetForDevice = true
            )
        )
    }
}
