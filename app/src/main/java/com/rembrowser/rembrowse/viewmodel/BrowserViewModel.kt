package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.data.BrowsingHistoryStore
import com.rembrowser.rembrowse.data.NoBrowsingHistory
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.util.UrlResolver
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The most pages the history keeps; the oldest drop off. */
const val MAX_HISTORY_ENTRIES = 1_000

class BrowserViewModel(
    private val engine: BrowserEngine,
    private val repository: BrowserStateRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val historyStore: BrowsingHistoryStore = NoBrowsingHistory,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Pages visited, most recent first; one entry per address (see [HistoryEntry]). */
    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val sessionListener = object : SessionEventListener {
        override fun onSessionUpdated(tabId: String, update: SessionNavigationUpdate) {
            // The engine also reports addresses it failed to load, such as a rem:// link a page
            // navigated to. Accepting one would turn a web tab into the settings page, so an
            // internal address is never taken from the engine.
            val reportsInternalUrl = update.url?.let(::isInternalUrl) == true
            applySessionUpdate(tabId, if (reportsInternalUrl) update.copy(url = null) else update)
        }

        private fun applySessionUpdate(tabId: String, update: SessionNavigationUpdate) {
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
            openNewTab(
                url = url,
                assignToPane = openerPane ?: state.activePaneIndex,
                desktopMode = opener?.desktopMode,
                openedByPage = true,
                openerTabId = opener?.id
            )
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

        override fun onDownloadRequested(request: DownloadRequest) {
            val handler = downloadHandler
            if (handler != null) {
                handler(request)
            } else {
                // Nobody to save it: let go of the connection rather than leave it hanging
                runCatching { request.body.close() }
            }
        }
    }

    /** Receives the files that pages offer to save. Set by whoever owns the downloads. */
    var downloadHandler: ((DownloadRequest) -> Unit)? = null

    /** Whether the browser window is on screen at all (false while it is minimised or covered). */
    private var windowShown = true

    /** Tabs in which the engine has shown a document since this browser started. */
    private val tabsWithContent = mutableSetOf<String>()

    init {
        restoreInitialState()
    }

    private fun restoreInitialState() {
        val persisted = repository.loadState()
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
            // A page that is open in this tab stays where it is: settings get their own tab.
            openSettings(SettingsSection.fromUrl(resolvedUrl))
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
        val resolved = url?.let { resolve(it) } ?: BrowserTab.NEW_TAB_URL
        val newTab = createTabModel(resolved, desktopMode).copy(openedByPage = openedByPage)
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

    /** Takes the fullscreen page back into its pane. The layout was never changed, so it is as it was. */
    fun exitFullscreen() {
        val tabId = _uiState.value.fullscreenTabId ?: return
        engine.getSession(tabId)?.exitFullScreen()
        _uiState.update { it.copy(fullscreenTabId = null) }
        syncSessions()
    }

    /**
     * The window was minimised or covered ([shown] false), or came back. While it is away no
     * page is active: they stop painting and their timers slow down, as for a background tab.
     * Sound keeps playing.
     */
    fun setWindowShown(shown: Boolean) {
        if (windowShown == shown) return
        windowShown = shown
        syncSessions()
    }

    fun goBack(paneIndex: Int = _uiState.value.activePaneIndex) {
        val tab = _uiState.value.tabForPane(paneIndex) ?: return
        engine.getSession(tab.id)?.goBack()
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

    /** Opens a saved page: in place when the active tab is still empty, otherwise in a new tab. */
    fun openBookmark(url: String) {
        val current = _uiState.value
        if (current.activeTab?.isNewTabPage == true) {
            navigatePane(current.activePaneIndex, url)
        } else {
            openNewTab(url)
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(useDarkTheme = enabled)) }
        persistCurrentState()
    }

    fun setAccentColor(accent: AccentColor) {
        _uiState.update { it.copy(settings = it.settings.copy(accent = accent)) }
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

    /** Opens a page from the history, the way a bookmark opens. */
    fun openHistoryEntry(url: String) = openBookmark(url)

    fun removeHistoryEntry(url: String) {
        updateHistory { entries -> entries.filterNot { it.url == url } }
    }

    fun clearHistory() {
        updateHistory { emptyList() }
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
        persistCurrentState()
        engine.closeAll()
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
        state.tabs.forEach { tab ->
            val inPane = tab.id in visibleIds
            // Internal pages are drawn by the app and never get a session of their own
            val session = if (inPane && !tab.isInternalPage) {
                ensureSessionCreated(tab)
            } else {
                engine.getSession(tab.id) ?: return@forEach
            }
            // A pane is only worth running while someone can see it: not under a fullscreen
            // page, and not while the whole window is away
            val seen = inPane && windowShown && (fullscreenId == null || tab.id == fullscreenId)
            session.setVisible(seen)
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
                layout = state.layout
            )
        )
    }
}
