package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.util.UrlResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

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
                        tab.copy(
                            url = update.url ?: tab.url,
                            title = update.title?.takeIf { it.isNotBlank() } ?: tab.title,
                            isLoading = update.isLoading ?: tab.isLoading,
                            progress = update.progress ?: tab.progress,
                            canGoBack = update.canGoBack ?: tab.canGoBack,
                            canGoForward = update.canGoForward ?: tab.canGoForward
                        )
                    }
                }
                val newState = state.copy(tabs = updatedTabs)
                val syncedInput = if (!newState.isAddressBarFocused && newState.activeTabId == tabId && update.url != null) {
                    update.url
                } else {
                    newState.addressBarInput
                }
                newState.copy(addressBarInput = syncedInput)
            }
            if (update.url != null || update.title != null) {
                persistCurrentState()
            }
        }

        override fun onNewTabRequested(url: String) {
            openNewTab(url = url, assignToPane = _uiState.value.activePaneIndex)
        }
    }

    init {
        restoreInitialState()
    }

    private fun restoreInitialState() {
        val persisted = repository.loadState()
        val initialTabs = persisted.tabs.toMutableList()
        if (initialTabs.isEmpty()) {
            initialTabs.add(createTabModel(BrowserTab.DEFAULT_HOME_URL))
        }

        val layout = persisted.layoutMode
        val validTabIds = initialTabs.map { it.id }.toSet()
        val paneTabIds = persisted.paneTabIds.filter { it in validTabIds }.toMutableList()

        ensurePaneSlotsFilled(layout.paneCount, initialTabs, paneTabIds)

        initialTabs.forEach { tab ->
            ensureSessionCreated(tab)
        }

        val activePane = persisted.activePaneIndex.coerceIn(0, layout.paneCount - 1)
        val activeUrl = initialTabs.firstOrNull { it.id == paneTabIds.getOrNull(activePane) }?.url
            ?: BrowserTab.DEFAULT_HOME_URL

        _uiState.value = BrowserUiState(
            layoutMode = layout,
            splitRatios = persisted.splitRatios.clamped(),
            tabs = initialTabs,
            paneTabIds = paneTabIds.take(layout.paneCount),
            activePaneIndex = activePane,
            bookmarks = persisted.bookmarks,
            addressBarInput = activeUrl
        )
        syncActiveSessionFlags()
        persistCurrentState()
    }

    fun setLayoutMode(mode: LayoutMode) {
        val current = _uiState.value
        val tabs = current.tabs.toMutableList()
        val paneIds = current.paneTabIds.toMutableList()
        val previouslyFocusedTabId = current.activeTabId

        ensurePaneSlotsFilled(mode.paneCount, tabs, paneIds)
        tabs.forEach { ensureSessionCreated(it) }

        val targetPaneIds = paneIds.take(mode.paneCount).toMutableList()
        var newActivePane = if (current.activePaneIndex < mode.paneCount) {
            current.activePaneIndex
        } else {
            0
        }

        // When shrinking layouts (e.g. 2x2 -> 1x1), keep the user's focused tab visible
        // while all other tabs remain available in the top tab bar.
        if (previouslyFocusedTabId != null && previouslyFocusedTabId !in targetPaneIds) {
            targetPaneIds[0] = previouslyFocusedTabId
            newActivePane = 0
        } else if (previouslyFocusedTabId != null) {
            newActivePane = targetPaneIds.indexOf(previouslyFocusedTabId).coerceAtLeast(0)
        }

        val activeTabUrl = tabs.firstOrNull { it.id == targetPaneIds.getOrNull(newActivePane) }?.url
            ?: current.addressBarInput

        _uiState.value = current.copy(
            layoutMode = mode,
            tabs = tabs,
            paneTabIds = targetPaneIds,
            activePaneIndex = newActivePane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else activeTabUrl
        )
        syncActiveSessionFlags()
        persistCurrentState()
    }

    fun selectTab(tabId: String) {
        val current = _uiState.value
        if (current.tabs.none { it.id == tabId }) return
        val existingPane = current.paneTabIds.indexOf(tabId)
        if (existingPane in 0 until current.layoutMode.paneCount) {
            focusPane(existingPane)
        } else {
            assignTabToPane(current.activePaneIndex, tabId)
        }
    }

    fun updateHorizontalSplit(deltaFraction: Float) {
        _uiState.update { state ->
            val updated = state.splitRatios.copy(
                horizontal = state.splitRatios.horizontal + deltaFraction
            ).clamped()
            state.copy(splitRatios = updated)
        }
        persistCurrentState()
    }

    fun updateVerticalSplit(deltaFraction: Float) {
        _uiState.update { state ->
            val updated = state.splitRatios.copy(
                vertical = state.splitRatios.vertical + deltaFraction
            ).clamped()
            state.copy(splitRatios = updated)
        }
        persistCurrentState()
    }

    fun resetSplitRatios() {
        _uiState.update { it.copy(splitRatios = SplitRatios.DEFAULT) }
        persistCurrentState()
    }

    fun focusPane(paneIndex: Int) {
        val current = _uiState.value
        if (paneIndex !in 0 until current.layoutMode.paneCount) return
        val paneTab = current.tabForPane(paneIndex)
        _uiState.value = current.copy(
            activePaneIndex = paneIndex,
            addressBarInput = if (current.isAddressBarFocused) {
                current.addressBarInput
            } else {
                paneTab?.url ?: current.addressBarInput
            }
        )
        syncActiveSessionFlags()
        persistCurrentState()
    }

    fun onAddressBarChange(input: String) {
        _uiState.update { it.copy(addressBarInput = input) }
    }

    fun onAddressBarFocusChange(focused: Boolean) {
        _uiState.update { state ->
            val input = if (!focused) state.activeTab?.url ?: state.addressBarInput else state.addressBarInput
            state.copy(isAddressBarFocused = focused, addressBarInput = input)
        }
    }

    fun submitAddressBar() {
        val state = _uiState.value
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
                if (it.id == tab.id) it.copy(url = resolvedUrl) else it
            }
            val updatedAddress = if (paneIndex == state.activePaneIndex && !state.isAddressBarFocused) {
                resolvedUrl
            } else {
                state.addressBarInput
            }
            state.copy(tabs = updatedTabs, addressBarInput = updatedAddress)
        }

        session.loadUrl(resolvedUrl)
        persistCurrentState()
    }

    fun openNewTab(url: String? = null, assignToPane: Int? = null): String {
        val resolved = url?.let { UrlResolver.resolveInput(it) } ?: BrowserTab.DEFAULT_HOME_URL
        val newTab = createTabModel(resolved)
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
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else newTab.url
        )
        syncActiveSessionFlags()
        persistCurrentState()
        return newTab.id
    }

    fun closeTab(tabId: String) {
        val current = _uiState.value
        if (current.tabs.none { it.id == tabId }) return

        engine.closeSession(tabId)
        val remainingTabs = current.tabs.filterNot { it.id == tabId }.toMutableList()

        if (remainingTabs.isEmpty()) {
            val freshTab = createTabModel(BrowserTab.DEFAULT_HOME_URL)
            remainingTabs.add(freshTab)
            ensureSessionCreated(freshTab)
        }

        val updatedPaneIds = current.paneTabIds.toMutableList()
        for (index in updatedPaneIds.indices) {
            if (updatedPaneIds[index] == tabId) {
                val unassigned = remainingTabs.firstOrNull { candidate ->
                    candidate.id !in updatedPaneIds
                }
                updatedPaneIds[index] = unassigned?.id ?: remainingTabs.first().id
            }
        }
        ensurePaneSlotsFilled(current.layoutMode.paneCount, remainingTabs, updatedPaneIds)
        remainingTabs.forEach { ensureSessionCreated(it) }

        val finalPaneIds = updatedPaneIds.take(current.layoutMode.paneCount)
        val activePane = current.activePaneIndex.coerceIn(0, current.layoutMode.paneCount - 1)
        val activeUrl = remainingTabs.firstOrNull { it.id == finalPaneIds[activePane] }?.url
            ?: BrowserTab.DEFAULT_HOME_URL

        _uiState.value = current.copy(
            tabs = remainingTabs,
            paneTabIds = finalPaneIds,
            activePaneIndex = activePane,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else activeUrl
        )
        syncActiveSessionFlags()
        persistCurrentState()
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
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else tab.url
        )
        syncActiveSessionFlags()
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

        val newActiveUrl = current.tabs.firstOrNull { it.id == updated[current.activePaneIndex] }?.url
            ?: current.addressBarInput

        _uiState.value = current.copy(
            paneTabIds = updated,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else newActiveUrl
        )
        syncActiveSessionFlags()
        persistCurrentState()
    }

    fun maximizePane(paneIndex: Int) {
        val current = _uiState.value
        val tab = current.tabForPane(paneIndex) ?: return
        _uiState.value = current.copy(
            layoutMode = LayoutMode.SINGLE_1X1,
            paneTabIds = listOf(tab.id),
            activePaneIndex = 0,
            addressBarInput = if (current.isAddressBarFocused) current.addressBarInput else tab.url
        )
        syncActiveSessionFlags()
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
        val existing = current.bookmarks.firstOrNull {
            it.url.equals(activeTab.url, ignoreCase = true)
        }
        val updatedBookmarks = if (existing != null) {
            current.bookmarks.filterNot { it.id == existing.id }
        } else {
            val title = activeTab.title.ifBlank { UrlResolver.extractDisplayHost(activeTab.url) }
            listOf(
                BookmarkItem(
                    id = idGenerator(),
                    title = title,
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

    fun openBookmarkInActivePane(url: String) {
        navigatePane(_uiState.value.activePaneIndex, url)
    }

    fun shutdown() {
        persistCurrentState()
        engine.closeAll()
    }

    private fun createTabModel(url: String): BrowserTab {
        return BrowserTab(
            id = idGenerator(),
            url = url,
            title = UrlResolver.extractDisplayHost(url),
            desktopMode = true
        )
    }

    private fun ensureSessionCreated(tab: BrowserTab) =
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
                val newTab = createTabModel(BrowserTab.DEFAULT_HOME_URL)
                tabs.add(newTab)
                paneTabIds.add(newTab.id)
            }
        }
    }

    private fun syncActiveSessionFlags() {
        val state = _uiState.value
        val visibleIds = state.paneTabIds.take(state.layoutMode.paneCount).toSet()
        val activeId = state.activeTabId
        state.tabs.forEach { tab ->
            val session = engine.getSession(tab.id) ?: return@forEach
            session.setVisible(tab.id in visibleIds)
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
                bookmarks = state.bookmarks
            )
        )
    }
}
