package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.util.UrlResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.abs

class BrowserViewModel(
    private val engine: BrowserEngine,
    private val repository: BrowserStateRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val sessionListener = object : SessionEventListener {
        override fun onSessionUpdated(tabId: String, update: SessionNavigationUpdate) {
            _uiState.update { state ->
                val updatedTabs = state.tabs.map { tab ->
                    if (tab.id != tabId) {
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
                val newState = state.copy(tabs = updatedTabs)
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
        }

        override fun onNewTabRequested(url: String, openerTabId: String?) {
            val state = _uiState.value
            val opener = state.tabs.firstOrNull { it.id == openerTabId }
            val openerPane = opener?.let { state.paneIndexOf(it.id) }?.takeIf { it >= 0 }
            openNewTab(
                url = url,
                assignToPane = openerPane ?: state.activePaneIndex,
                desktopMode = opener?.desktopMode,
                openedByPage = true
            )
        }

        override fun onPromptRequested(prompt: PagePrompt) {
            _uiState.update { it.copy(pendingPrompts = it.pendingPrompts + prompt) }
        }

        override fun onPromptDismissed(promptId: String) {
            resolvePrompt(promptId)
        }
    }

    init {
        restoreInitialState()
    }

    private fun restoreInitialState() {
        val persisted = repository.loadState()
        val initialTabs = persisted.tabs.toMutableList()
        if (initialTabs.isEmpty()) {
            initialTabs.add(createTabModel(BrowserTab.NEW_TAB_URL))
        }

        val layout = persisted.layoutMode
        val validTabIds = initialTabs.map { it.id }.toSet()
        val paneTabIds = persisted.paneTabIds.filter { it in validTabIds }.toMutableList()

        ensurePaneSlotsFilled(layout.paneCount, initialTabs, paneTabIds)

        val activePane = persisted.activePaneIndex.coerceIn(0, layout.paneCount - 1)
        val activeTab = initialTabs.firstOrNull { it.id == paneTabIds.getOrNull(activePane) }

        engine.setEnhancedPrivacy(persisted.settings.enhancedPrivacy)
        _uiState.value = BrowserUiState(
            layoutMode = layout,
            splitRatios = persisted.splitRatios.clamped(),
            tabs = initialTabs,
            paneTabIds = paneTabIds.take(layout.paneCount),
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

    fun setLayoutMode(mode: LayoutMode) {
        val current = _uiState.value
        val tabs = current.tabs.toMutableList()
        val paneIds = current.paneTabIds.toMutableList()
        val previouslyFocusedTabId = current.activeTabId

        ensurePaneSlotsFilled(mode.paneCount, tabs, paneIds)

        val targetPaneIds = paneIds.take(mode.paneCount).toMutableList()
        var newActivePane = if (current.activePaneIndex < mode.paneCount) {
            current.activePaneIndex
        } else {
            0
        }

        // When shrinking layouts (e.g. 2x2 -> 1x1), keep the user's focused tab visible
        // while all other tabs stay open in the tab rail.
        if (previouslyFocusedTabId != null && previouslyFocusedTabId !in targetPaneIds) {
            targetPaneIds[0] = previouslyFocusedTabId
            newActivePane = 0
        } else if (previouslyFocusedTabId != null) {
            newActivePane = targetPaneIds.indexOf(previouslyFocusedTabId).coerceAtLeast(0)
        }

        val activeTab = tabs.firstOrNull { it.id == targetPaneIds.getOrNull(newActivePane) }

        _uiState.value = current.copy(
            layoutMode = mode,
            tabs = tabs,
            paneTabIds = targetPaneIds,
            activePaneIndex = newActivePane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(activeTab)
        )
        syncSessions()
        persistCurrentState()
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

    /** Live resize while a divider is dragged. Call [commitSplitRatios] when the drag ends. */
    fun updateHorizontalSplit(deltaFraction: Float) {
        _uiState.update { state ->
            val updated = state.splitRatios.copy(
                horizontal = state.splitRatios.horizontal + deltaFraction
            ).clamped()
            state.copy(splitRatios = updated)
        }
    }

    fun updateVerticalSplit(deltaFraction: Float) {
        _uiState.update { state ->
            val updated = state.splitRatios.copy(
                vertical = state.splitRatios.vertical + deltaFraction
            ).clamped()
            state.copy(splitRatios = updated)
        }
    }

    /** Saves the split once per drag instead of on every pointer move. */
    fun commitSplitRatios() {
        persistCurrentState()
    }

    fun resetSplitRatios() {
        _uiState.update { it.copy(splitRatios = SplitRatios.DEFAULT) }
        persistCurrentState()
    }

    fun focusPane(paneIndex: Int) {
        val current = _uiState.value
        if (paneIndex !in 0 until current.layoutMode.paneCount) return
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
        val resolved = UrlResolver.resolveInput(state.addressBarInput)
        _uiState.update { it.copy(isAddressBarFocused = false, addressBarInput = resolved) }
        navigatePane(state.activePaneIndex, resolved)
    }

    fun navigatePane(paneIndex: Int, rawInput: String) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        val resolvedUrl = UrlResolver.resolveInput(rawInput)
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
        persistCurrentState()
    }

    fun openNewTab(
        url: String? = null,
        assignToPane: Int? = null,
        desktopMode: Boolean? = null,
        openedByPage: Boolean = false
    ): String {
        val resolved = url?.let { UrlResolver.resolveInput(it) } ?: BrowserTab.NEW_TAB_URL
        val newTab = createTabModel(resolved, desktopMode ?: true).copy(openedByPage = openedByPage)
        // Created before anything else so the engine can hand this exact session back to a
        // page that asked for a new window.
        ensureSessionCreated(newTab)

        val current = _uiState.value
        val targetPane = (assignToPane ?: current.activePaneIndex).coerceIn(0, current.layoutMode.paneCount - 1)
        val updatedTabs = current.tabs + newTab
        val updatedPaneIds = current.paneTabIds.toMutableList()
        while (updatedPaneIds.size < current.layoutMode.paneCount) {
            updatedPaneIds.add(newTab.id)
        }
        updatedPaneIds[targetPane] = newTab.id

        _uiState.value = current.copy(
            tabs = updatedTabs,
            paneTabIds = updatedPaneIds,
            activePaneIndex = targetPane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(newTab)
        )
        syncSessions()
        persistCurrentState()
        return newTab.id
    }

    fun closeTab(tabId: String) {
        val current = _uiState.value
        val closedIndex = current.tabs.indexOfFirst { it.id == tabId }
        if (closedIndex < 0) return

        engine.closeSession(tabId)
        val remainingTabs = current.tabs.filterNot { it.id == tabId }.toMutableList()
        val updatedPaneIds = current.paneTabIds.toMutableList()

        // Refill the closed tab's pane in place. Every other pane keeps its tab, so the rest of
        // the layout does not reshuffle (and its web views do not have to re-attach).
        for (index in updatedPaneIds.indices) {
            if (updatedPaneIds[index] != tabId) continue
            val replacement = current.tabs
                .filter { it.id != tabId && it.id !in updatedPaneIds }
                .minByOrNull { candidate ->
                    val distance = current.tabs.indexOf(candidate) - closedIndex
                    // Prefer the neighbour just after the closed tab, then the one before it
                    abs(distance) * 2 - if (distance > 0) 1 else 0
                }
                ?: createTabModel(BrowserTab.NEW_TAB_URL).also { remainingTabs.add(it) }
            updatedPaneIds[index] = replacement.id
        }
        ensurePaneSlotsFilled(current.layoutMode.paneCount, remainingTabs, updatedPaneIds)

        val finalPaneIds = updatedPaneIds.take(current.layoutMode.paneCount)
        val activePane = current.activePaneIndex.coerceIn(0, current.layoutMode.paneCount - 1)
        val activeTab = remainingTabs.firstOrNull { it.id == finalPaneIds[activePane] }

        _uiState.value = current.copy(
            tabs = remainingTabs,
            paneTabIds = finalPaneIds,
            activePaneIndex = activePane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(activeTab),
            pendingPrompts = current.pendingPrompts.filterNot { it.tabId == tabId }
        )
        syncSessions()
        persistCurrentState()
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
        if (paneIndex !in 0 until current.layoutMode.paneCount) return
        val tab = current.tabs.firstOrNull { it.id == tabId } ?: return

        val updatedPaneIds = current.paneTabIds.toMutableList()
        val existingPaneWithTab = updatedPaneIds.indexOf(tabId)
        if (existingPaneWithTab != -1 && existingPaneWithTab != paneIndex) {
            // Swap cleanly so two GeckoView panes do not fight over the same GeckoSession
            updatedPaneIds[existingPaneWithTab] = updatedPaneIds[paneIndex]
        }
        updatedPaneIds[paneIndex] = tabId

        _uiState.value = current.copy(
            paneTabIds = updatedPaneIds,
            activePaneIndex = paneIndex,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(tab)
        )
        syncSessions()
        persistCurrentState()
    }

    fun swapPanes(firstPaneIndex: Int, secondPaneIndex: Int) {
        val current = _uiState.value
        val count = current.layoutMode.paneCount
        if (firstPaneIndex !in 0 until count || secondPaneIndex !in 0 until count) return
        if (firstPaneIndex == secondPaneIndex) return

        val updated = current.paneTabIds.toMutableList()
        val tmp = updated[firstPaneIndex]
        updated[firstPaneIndex] = updated[secondPaneIndex]
        updated[secondPaneIndex] = tmp

        val newActiveTab = current.tabs.firstOrNull { it.id == updated[current.activePaneIndex] }

        _uiState.value = current.copy(
            paneTabIds = updated,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(newActiveTab)
        )
        syncSessions()
        persistCurrentState()
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
        val count = current.layoutMode.paneCount
        if (count < 2) return
        val from = current.activePaneIndex
        val to = (from + 1) % count
        swapPanes(from, to)
        focusPane(to)
    }

    /** Sizes the first pane to [firstPaneFraction] of the split: 0.5 is even, 0.7 is 70/30. */
    fun setSplitPreset(firstPaneFraction: Float) {
        _uiState.update {
            it.copy(splitRatios = SplitRatios(firstPaneFraction, firstPaneFraction).clamped())
        }
        persistCurrentState()
    }

    fun maximizePane(paneIndex: Int) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        _uiState.value = current.copy(
            layoutMode = LayoutMode.SINGLE_1X1,
            paneTabIds = listOf(tab.id),
            activePaneIndex = 0,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else addressTextFor(tab)
        )
        syncSessions()
        persistCurrentState()
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
        if (tab.isNewTabPage) return
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
        val session = ensureSessionCreated(tab)
        session.setDesktopMode(nextMode)

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
        if (activeTab.isNewTabPage) return
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

    private fun createTabModel(url: String, desktopMode: Boolean = true): BrowserTab {
        return BrowserTab(id = idGenerator(), url = url, desktopMode = desktopMode)
    }

    private fun ensureSessionCreated(tab: BrowserTab): BrowserSessionHandle =
        engine.getSession(tab.id) ?: engine.createSession(
            tabId = tab.id,
            initialUrl = tab.url,
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
        val activeId = state.activeTabId
        state.tabs.forEach { tab ->
            val isVisible = tab.id in visibleIds
            val session = if (isVisible) {
                ensureSessionCreated(tab)
            } else {
                engine.getSession(tab.id) ?: return@forEach
            }
            session.setVisible(isVisible)
            session.setFocusedForExtensions(tab.id == activeId)
        }
    }

    private fun persistCurrentState() {
        val state = _uiState.value
        repository.saveState(
            PersistedBrowserState(
                layoutMode = state.layoutMode,
                splitRatios = state.splitRatios,
                tabs = state.tabs,
                paneTabIds = state.paneTabIds,
                activePaneIndex = state.activePaneIndex,
                bookmarks = state.bookmarks,
                settings = state.settings
            )
        )
    }
}
