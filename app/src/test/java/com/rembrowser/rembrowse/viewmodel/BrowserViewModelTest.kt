package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.TrackedInputStream
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SplitRatios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserViewModelTest {

    private lateinit var engine: FakeBrowserEngine
    private lateinit var repository: FakeBrowserStateRepository
    private lateinit var viewModel: BrowserViewModel
    private var idCounter = 0

    @Before
    fun setUp() {
        idCounter = 0
        engine = FakeBrowserEngine()
        repository = FakeBrowserStateRepository()
        viewModel = newViewModel()
    }

    private fun newViewModel() = BrowserViewModel(
        engine = engine,
        repository = repository,
        idGenerator = { "tab-${++idCounter}" }
    )

    private fun alert(id: String, tabId: String) =
        PagePrompt.Alert(id = id, tabId = tabId, title = null, message = "Hello", onDismiss = {})

    @Test
    fun init_createsDefaultSinglePaneTabWhenStorageIsEmpty() {
        val state = viewModel.uiState.value
        assertEquals(LayoutMode.SINGLE_1X1, state.layoutMode)
        assertEquals(1, state.tabs.size)
        assertEquals(listOf("tab-1"), state.paneTabIds)
        assertEquals(0, state.activePaneIndex)
        assertTrue(engine.sessions.containsKey("tab-1"))
    }

    @Test
    fun init_startsOnTheNativeStartPageWithAnEmptyAddressBar() {
        val state = viewModel.uiState.value
        assertTrue(state.activeTab!!.isNewTabPage)
        assertEquals("New tab", state.activeTab!!.displayTitle)
        assertEquals("", state.addressBarInput)
    }

    @Test
    fun init_onlyCreatesEngineSessionsForTabsShownInAPane() {
        repository.storedState = PersistedBrowserState(
            tabs = listOf(
                BrowserTab(id = "a", url = "https://a.example"),
                BrowserTab(id = "b", url = "https://b.example"),
                BrowserTab(id = "c", url = "https://c.example")
            ),
            paneTabIds = listOf("b")
        )
        engine = FakeBrowserEngine()
        viewModel = newViewModel()

        // Restoring three tabs must not load three pages at launch
        assertEquals(setOf("b"), engine.sessions.keys)
        assertEquals("https://b.example", viewModel.uiState.value.addressBarInput)

        // A background tab gets its session the first time it is shown
        viewModel.selectTab("c")
        assertEquals(setOf("b", "c"), engine.sessions.keys)
        assertTrue(engine.sessions.getValue("c").visibleState)
        assertFalse(engine.sessions.getValue("b").visibleState)
    }

    @Test
    fun setLayoutMode_expandsPanesAndCreatesTabsForNewSlots() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)

        val state = viewModel.uiState.value
        assertEquals(LayoutMode.GRID_2X2, state.layoutMode)
        assertEquals(4, state.paneTabIds.size)
        assertEquals(4, state.tabs.size)
        assertEquals(4, engine.sessions.size)
        assertEquals("tab-1", state.paneTabIds[0])
    }

    @Test
    fun setLayoutMode_shrinkingPreservesExistingTabsAndKeepsFocusedTabVisible() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        viewModel.focusPane(2)
        val focusedTabId = viewModel.uiState.value.activeTabId!!

        viewModel.setLayoutMode(LayoutMode.SINGLE_1X1)
        val state = viewModel.uiState.value
        assertEquals(LayoutMode.SINGLE_1X1, state.layoutMode)
        assertEquals(4, state.tabs.size)
        assertEquals(0, state.activePaneIndex)
        assertEquals(focusedTabId, state.paneTabIds[0])
        // Only the visible 1x1 tab is marked visible; the other 3 wait in the tab rail
        assertTrue(engine.sessions.getValue(focusedTabId).visibleState)
        assertEquals(1, engine.sessions.values.count { it.visibleState })
    }

    @Test
    fun focusPane_in2x2ModeKeepsAllVisiblePanesActiveSoNoneGoDark() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        // Open a 5th tab to verify non-pane tabs are inactive
        val pane0Id = viewModel.uiState.value.paneTabIds[0]
        viewModel.openNewTab("https://example.org", assignToPane = 0)
        // Now pane0Id is in the background, while 4 panes are visible
        val visibleIds = viewModel.uiState.value.paneTabIds.toSet()

        viewModel.focusPane(1)

        visibleIds.forEach { id ->
            assertTrue("Visible pane tab $id must remain visible", engine.sessions.getValue(id).visibleState)
        }
        assertFalse("Background tab should not be marked visible", engine.sessions.getValue(pane0Id).visibleState)
        assertTrue(engine.sessions.getValue(viewModel.uiState.value.paneTabIds[1]).focusedForExtensionsState)
        assertFalse(engine.sessions.getValue(viewModel.uiState.value.paneTabIds[0]).focusedForExtensionsState)
    }

    @Test
    fun selectTab_focusesExistingPaneOrShowsTabInActivePane() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        val tab3Id = viewModel.uiState.value.paneTabIds[2]
        viewModel.focusPane(0)

        // Selecting a tab already visible in pane 2 focuses pane 2
        viewModel.selectTab(tab3Id)
        assertEquals(2, viewModel.uiState.value.activePaneIndex)

        // Collapse to 1x1 and pick another tab from the tab rail
        viewModel.setLayoutMode(LayoutMode.SINGLE_1X1)
        val tab1Id = viewModel.uiState.value.tabs.first().id
        viewModel.selectTab(tab1Id)
        assertEquals(tab1Id, viewModel.uiState.value.activeTabId)
        assertTrue(engine.sessions.getValue(tab1Id).visibleState)
    }

    @Test
    fun selectAdjacentTab_cyclesThroughTabsAndWrapsAround() {
        viewModel.openNewTab("https://one.example")
        viewModel.openNewTab("https://two.example")
        val ids = viewModel.uiState.value.tabs.map { it.id }
        assertEquals(ids[2], viewModel.uiState.value.activeTabId)

        viewModel.selectAdjacentTab(1)
        assertEquals(ids[0], viewModel.uiState.value.activeTabId)

        viewModel.selectAdjacentTab(-1)
        assertEquals(ids[2], viewModel.uiState.value.activeTabId)
    }

    @Test
    fun splitRatios_updateAndClampWithinBoundsAndReset() {
        viewModel.updateHorizontalSplit(0.45f)
        assertEquals(SplitRatios.MAX_RATIO, viewModel.uiState.value.splitRatios.horizontal, 0.001f)

        viewModel.updateVerticalSplit(-0.45f)
        assertEquals(SplitRatios.MIN_RATIO, viewModel.uiState.value.splitRatios.vertical, 0.001f)

        viewModel.resetSplitRatios()
        assertEquals(0.5f, viewModel.uiState.value.splitRatios.horizontal, 0.001f)
        assertEquals(0.5f, viewModel.uiState.value.splitRatios.vertical, 0.001f)
    }

    @Test
    fun splitRatios_areSavedOncePerDragNotOnEveryPointerMove() {
        val savesBefore = repository.saveCount

        repeat(30) { viewModel.updateHorizontalSplit(0.005f) }
        assertEquals("Dragging must not write to storage", savesBefore, repository.saveCount)

        viewModel.commitSplitRatios()
        assertEquals(savesBefore + 1, repository.saveCount)
        assertEquals(0.65f, repository.storedState.splitRatios.horizontal, 0.001f)
    }

    @Test
    fun addressBarSubmission_navigatesActivePaneAndResolvesUrls() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        viewModel.focusPane(1)
        viewModel.onAddressBarChange("mozilla.org")
        viewModel.submitAddressBar()

        val pane1TabId = viewModel.uiState.value.paneTabIds[1]
        val session = engine.sessions.getValue(pane1TabId)
        assertEquals("https://mozilla.org", session.loadedUrls.last())
        assertEquals("https://mozilla.org", viewModel.uiState.value.tabForPane(1)?.url)
    }

    @Test
    fun addressBarSubmission_ignoresBlankInput() {
        viewModel.onAddressBarFocusChange(true)
        viewModel.onAddressBarChange("   ")
        viewModel.submitAddressBar()

        val session = engine.sessions.getValue("tab-1")
        assertEquals("No navigation for an empty address", 1, session.loadedUrls.size)
        assertFalse(viewModel.uiState.value.isAddressBarFocused)
        assertEquals("", viewModel.uiState.value.addressBarInput)
    }

    @Test
    fun swapPanes_exchangesAssignedTabsAndUpdatesFocus() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        val firstId = viewModel.uiState.value.paneTabIds[0]
        val secondId = viewModel.uiState.value.paneTabIds[1]

        viewModel.swapPanes(0, 1)

        assertEquals(secondId, viewModel.uiState.value.paneTabIds[0])
        assertEquals(firstId, viewModel.uiState.value.paneTabIds[1])
    }

    @Test
    fun splitCurrentTab_opensItBesideABackgroundTabOnTheRequestedSide() {
        engine.sessions.getValue("tab-1").simulatePageLoad("https://docs.example/", "Docs")
        viewModel.openNewTab("https://notes.example/")
        val notes = viewModel.uiState.value.activeTabId!!

        viewModel.splitCurrentTab(currentOnLeft = true)
        var state = viewModel.uiState.value
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertEquals("The current tab stays on the left", notes, state.paneTabIds[0])
        assertEquals("An existing tab fills the other side", "tab-1", state.paneTabIds[1])
        assertEquals("Focus stays with the page you were on", notes, state.activeTabId)
        assertEquals("No extra tab is created when one is available", 2, state.tabs.size)

        // Asking for the other side moves it without changing which page has focus
        viewModel.splitCurrentTab(currentOnLeft = false)
        state = viewModel.uiState.value
        assertEquals(listOf("tab-1", notes), state.paneTabIds)
        assertEquals(notes, state.activeTabId)
        assertEquals(1, state.activePaneIndex)
    }

    @Test
    fun splitCurrentTab_opensAStartPageWhenThereIsNoOtherTab() {
        engine.sessions.getValue("tab-1").simulatePageLoad("https://docs.example/", "Docs")

        viewModel.splitCurrentTab(currentOnLeft = true)

        val state = viewModel.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals("tab-1", state.paneTabIds[0])
        assertTrue(state.tabForPane(1)!!.isNewTabPage)
        assertEquals("tab-1", state.activeTabId)
    }

    @Test
    fun swapFocusedPane_exchangesThePagesAndFocusFollowsTheOneYouWereOn() {
        viewModel.splitCurrentTab(currentOnLeft = true)
        val left = viewModel.uiState.value.paneTabIds[0]
        val right = viewModel.uiState.value.paneTabIds[1]

        viewModel.swapFocusedPane()

        val state = viewModel.uiState.value
        assertEquals(listOf(right, left), state.paneTabIds)
        assertEquals(left, state.activeTabId)
        assertEquals(1, state.activePaneIndex)
    }

    @Test
    fun splitPresets_setAndSaveTheSizeOfTheFirstPane() {
        viewModel.splitCurrentTab(currentOnLeft = true)

        viewModel.setSplitPreset(0.7f)
        assertEquals(0.7f, viewModel.uiState.value.splitRatios.horizontal, 0.001f)
        assertEquals(0.7f, repository.storedState.splitRatios.horizontal, 0.001f)

        viewModel.setSplitPreset(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.splitRatios.horizontal, 0.001f)
    }

    @Test
    fun maximizePane_switchesTo1x1ShowingChosenPaneTab() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        val pane2TabId = viewModel.uiState.value.paneTabIds[2]

        viewModel.maximizePane(2)

        val state = viewModel.uiState.value
        assertEquals(LayoutMode.SINGLE_1X1, state.layoutMode)
        assertEquals(pane2TabId, state.paneTabIds[0])
        assertEquals(0, state.activePaneIndex)
    }

    @Test
    fun closeTab_closesLastTabReplacesWithFreshStartPageTab() {
        val originalId = viewModel.uiState.value.tabs.first().id
        viewModel.closeTab(originalId)

        val state = viewModel.uiState.value
        assertEquals(1, state.tabs.size)
        assertNotEquals(originalId, state.tabs.first().id)
        assertTrue(state.tabs.first().isNewTabPage)
        assertFalse(engine.sessions.containsKey(originalId))
    }

    @Test
    fun closeTab_inSplitViewRefillsThatPaneWithoutMovingTheOthers() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        val before = viewModel.uiState.value.paneTabIds

        viewModel.closeTab(before[1])

        val after = viewModel.uiState.value.paneTabIds
        assertEquals(4, after.size)
        assertEquals("Pane 0 keeps its tab", before[0], after[0])
        assertEquals("Pane 2 keeps its tab", before[2], after[2])
        assertEquals("Pane 3 keeps its tab", before[3], after[3])
        assertFalse(before[1] in after)
        assertEquals(4, after.toSet().size)
    }

    @Test
    fun closeTab_showsTheNeighbouringTabNext() {
        viewModel.openNewTab("https://one.example")
        viewModel.openNewTab("https://two.example")
        viewModel.openNewTab("https://three.example")
        val ids = viewModel.uiState.value.tabs.map { it.id }
        viewModel.selectTab(ids[1])

        viewModel.closeTab(ids[1])

        assertEquals("The tab after the closed one takes its place", ids[2], viewModel.uiState.value.activeTabId)
    }

    @Test
    fun closeOtherTabs_keepsTheChosenTabAndAnythingOnScreen() {
        viewModel.openNewTab("https://one.example")
        viewModel.openNewTab("https://two.example")
        val keep = viewModel.uiState.value.tabs.first().id
        val visible = viewModel.uiState.value.activeTabId!!

        viewModel.closeOtherTabs(keep)

        assertEquals(setOf(keep, visible), viewModel.uiState.value.tabs.map { it.id }.toSet())
    }

    @Test
    fun newTabRequestedByPage_opensInTheOpenersPaneAndKeepsItsMode() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        val openerId = viewModel.uiState.value.paneTabIds[1]
        viewModel.toggleDesktopMode(1)
        viewModel.focusPane(0)

        engine.sessions.getValue(openerId).simulateNewTab("https://popup.example/login")

        val state = viewModel.uiState.value
        val popup = state.tabForPane(1)!!
        assertEquals("https://popup.example/login", popup.url)
        assertFalse("The popup inherits the opener's mobile mode", popup.desktopMode)
        assertEquals(1, state.activePaneIndex)
        // The engine hands this exact session back to the page, so it must already exist
        assertTrue(engine.sessions.containsKey(popup.id))
        assertTrue(state.tabs.any { it.id == openerId })
    }

    @Test
    fun blankPopupOpenedByPage_showsWebContentNotTheStartPage() {
        // Sign-in flows often open an empty window first and fill it in afterwards
        engine.sessions.getValue("tab-1").simulateNewTab("about:blank")

        val popup = viewModel.uiState.value.activeTab!!
        assertEquals("about:blank", popup.url)
        assertFalse("The start page must not cover a page-controlled popup", popup.isNewTabPage)

        // ...whereas a tab the user opens is the start page
        viewModel.openNewTab()
        assertTrue(viewModel.uiState.value.activeTab!!.isNewTabPage)
    }

    @Test
    fun pageStart_dropsThePreviousPagesTitle() {
        val session = engine.sessions.getValue("tab-1")
        session.simulatePageLoad("https://first.example/", "First page")
        assertEquals("First page", viewModel.uiState.value.activeTab!!.displayTitle)

        session.simulatePageStart("https://second.example/untitled")

        // Until the new page reports a title, show its host rather than the stale title
        assertEquals("second.example", viewModel.uiState.value.activeTab!!.displayTitle)
    }

    @Test
    fun securityState_followsTheEngineAndResetsWhenNavigating() {
        val session = engine.sessions.getValue("tab-1")
        session.simulatePageLoad("https://bank.example/", "Bank")
        session.simulateSecurityChange(true)
        assertTrue(viewModel.uiState.value.activeTab!!.isSecure)

        viewModel.navigatePane(0, "http://plain.example")
        assertFalse(viewModel.uiState.value.activeTab!!.isSecure)
    }

    @Test
    fun toggleDesktopMode_updatesSessionAndTabState() {
        val initialMode = viewModel.uiState.value.activeTab!!.desktopMode
        viewModel.toggleDesktopMode(0)

        val updatedMode = viewModel.uiState.value.activeTab!!.desktopMode
        assertEquals(!initialMode, updatedMode)
        assertEquals(updatedMode, engine.sessions.getValue("tab-1").desktopModeEnabled)
    }

    @Test
    fun bookmarks_toggleAddsAndRemovesWithoutDuplicates() {
        engine.sessions.getValue("tab-1").simulatePageLoad("https://developer.android.com", "Android Developers")

        viewModel.toggleBookmarkForActiveTab()
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        assertTrue(viewModel.uiState.value.isActiveTabBookmarked)

        viewModel.toggleBookmarkForActiveTab()
        assertEquals(0, viewModel.uiState.value.bookmarks.size)
        assertFalse(viewModel.uiState.value.isActiveTabBookmarked)
    }

    @Test
    fun bookmarks_theStartPageCannotBeBookmarked() {
        viewModel.toggleBookmarkForActiveTab()
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }

    @Test
    fun openBookmark_reusesAnEmptyTabOtherwiseOpensANewOne() {
        viewModel.openBookmark("https://docs.example")
        assertEquals(1, viewModel.uiState.value.tabs.size)
        assertEquals("https://docs.example", viewModel.uiState.value.activeTab!!.url)

        viewModel.openBookmark("https://news.example")
        assertEquals(2, viewModel.uiState.value.tabs.size)
        assertEquals("https://news.example", viewModel.uiState.value.activeTab!!.url)
    }

    @Test
    fun pagePrompts_areQueuedAndOnlyShownForTabsOnScreen() {
        viewModel.openNewTab("https://front.example")
        val backgroundId = "tab-1"
        val frontId = viewModel.uiState.value.activeTabId!!

        engine.sessions.getValue(backgroundId).simulatePrompt(alert("p-background", backgroundId))
        assertNull("A background tab must not interrupt", viewModel.uiState.value.visiblePrompt)

        engine.sessions.getValue(frontId).simulatePrompt(alert("p-front", frontId))
        assertEquals("p-front", viewModel.uiState.value.visiblePrompt?.id)

        viewModel.resolvePrompt("p-front")
        assertNull(viewModel.uiState.value.visiblePrompt)

        // Switching to the background tab now surfaces its waiting prompt
        viewModel.selectTab(backgroundId)
        assertEquals("p-background", viewModel.uiState.value.visiblePrompt?.id)

        // The page can withdraw a prompt, and closing a tab drops its prompts
        engine.sessions.getValue(backgroundId).simulatePromptWithdrawn("p-background")
        assertTrue(viewModel.uiState.value.pendingPrompts.isEmpty())
    }

    @Test
    fun closeTab_dropsThatTabsPendingPrompts() {
        engine.sessions.getValue("tab-1").simulatePrompt(alert("p1", "tab-1"))
        viewModel.closeTab("tab-1")
        assertTrue(viewModel.uiState.value.pendingPrompts.isEmpty())
    }

    @Test
    fun crashedTab_isFlaggedUntilItLoadsAgain() {
        val session = engine.sessions.getValue("tab-1")
        session.simulatePageLoad("https://heavy.example/", "Heavy")
        session.simulateCrashLoop()
        assertTrue(viewModel.uiState.value.activeTab!!.crashed)

        viewModel.reloadOrStop()
        assertEquals(1, session.reloadCount)
    }

    @Test
    fun openSettings_usesOneTabWithItsOwnAddressAndNoEngineSession() {
        engine.sessions.getValue("tab-1").simulatePageLoad("https://example.org/", "Example")

        viewModel.openSettings()
        var state = viewModel.uiState.value
        val settingsTab = state.activeTab!!
        assertEquals(2, state.tabs.size)
        assertEquals("rem://settings/appearance", settingsTab.url)
        assertEquals("rem://settings/appearance", state.addressBarInput)
        assertEquals("Settings", settingsTab.displayTitle)
        assertTrue(settingsTab.isInternalPage)
        assertFalse(settingsTab.isWebPage)
        // The app draws the page: the web engine is not involved at all
        assertFalse(engine.sessions.containsKey(settingsTab.id))
        assertEquals(setOf("tab-1"), engine.sessions.keys)

        // Changing section changes the address; asking again reuses the tab
        viewModel.showSettingsSection(settingsTab.id, SettingsSection.PRIVACY)
        assertEquals("rem://settings/privacy", viewModel.uiState.value.addressBarInput)
        viewModel.selectTab("tab-1")
        viewModel.openSettings(SettingsSection.EXTENSIONS)
        state = viewModel.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals(settingsTab.id, state.activeTabId)
        assertEquals("rem://settings/extensions", state.activeTab!!.url)

        // Nothing to reload, go back to, bookmark or switch to a mobile site on a settings page
        viewModel.reloadOrStop()
        viewModel.goBack()
        viewModel.goForward()
        viewModel.toggleBookmarkForActiveTab()
        viewModel.toggleDesktopMode()
        assertFalse(engine.sessions.containsKey(settingsTab.id))
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }

    @Test
    fun openSettings_reusesAnUntouchedStartPageAndClosesItsSession() {
        val startPageSession = engine.sessions.getValue("tab-1")

        viewModel.openSettings(SettingsSection.SEARCH)

        val state = viewModel.uiState.value
        assertEquals("The empty tab became the settings page", 1, state.tabs.size)
        assertEquals("rem://settings/search", state.activeTab!!.url)
        assertTrue(startPageSession.isClosed)
        assertTrue(engine.sessions.isEmpty())

        // A real engine reports about:blank for a session that is opening or closing. Arriving
        // late, it must not turn the settings page back into the start page.
        startPageSession.simulatePageLoad("about:blank", "", canGoBack = false)
        assertEquals("rem://settings/search", viewModel.uiState.value.activeTab!!.url)
        assertEquals("rem://settings/search", viewModel.uiState.value.addressBarInput)
        assertTrue(viewModel.uiState.value.activeTab!!.isSettingsPage)
    }

    @Test
    fun openSettings_keepsAStartPageThatHasHistoryBehindIt() {
        // The user went back to the start page: its tab still holds a page to go forward to
        engine.sessions.getValue("tab-1").simulatePageLoad("https://example.org/", "Example")
        engine.sessions.getValue("tab-1").simulateHistoryMove("about:blank", canGoBack = false, canGoForward = true)
        assertTrue(viewModel.uiState.value.activeTab!!.isNewTabPage)

        viewModel.openSettings()

        assertEquals(2, viewModel.uiState.value.tabs.size)
        assertFalse(engine.sessions.getValue("tab-1").isClosed)
    }

    @Test
    fun settingsAddress_typedOverAWebPageOpensSettingsInItsOwnTab() {
        val webSession = engine.sessions.getValue("tab-1")
        webSession.simulatePageLoad("https://example.org/", "Example")

        viewModel.onAddressBarChange("rem://settings/search")
        viewModel.submitAddressBar()

        val state = viewModel.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals("rem://settings/search", state.activeTab!!.url)
        assertEquals(SettingsSection.SEARCH, SettingsSection.fromUrl(state.activeTab!!.url))
        // The page that was open is untouched and the engine never saw the rem:// address
        assertEquals("https://example.org/", state.tabs.first { it.id == "tab-1" }.url)
        assertFalse(webSession.isClosed)
        assertTrue(webSession.loadedUrls.none { it.startsWith("rem://") })

        // An unknown section falls back to the first one rather than a broken page
        assertEquals(SettingsSection.APPEARANCE, SettingsSection.fromUrl("rem://settings/nonsense"))
        assertEquals(SettingsSection.APPEARANCE, SettingsSection.fromUrl("rem://something-else"))
        assertEquals(SettingsSection.PRIVACY, SettingsSection.fromUrl(" REM://Settings/Privacy/ "))
    }

    @Test
    fun settingsTab_canBrowseTheWebAndGetsAVisibleSessionWhenItDoes() {
        engine.sessions.getValue("tab-1").simulatePageLoad("https://example.org/", "Example")
        viewModel.openSettings()
        val tabId = viewModel.uiState.value.activeTabId!!
        assertFalse(engine.sessions.containsKey(tabId))

        viewModel.onAddressBarChange("mozilla.org")
        viewModel.submitAddressBar()

        val tab = viewModel.uiState.value.activeTab!!
        assertEquals(tabId, tab.id)
        assertEquals("https://mozilla.org", tab.url)
        assertTrue(tab.isWebPage)
        val session = engine.sessions.getValue(tabId)
        assertEquals("https://mozilla.org", session.loadedUrls.last())
        assertTrue(session.loadedUrls.none { it.startsWith("rem://") })
        // Without these the page would load into a session that never paints
        assertTrue(session.visibleState)
        assertTrue(session.focusedForExtensionsState)
    }

    @Test
    fun pagesAndExtensionsCannotOpenInternalPages() {
        val opener = engine.sessions.getValue("tab-1")
        opener.simulatePageLoad("https://example.org/", "Example")

        opener.simulateNewTab("rem://settings/extensions")
        opener.simulateNewTab("  REM://settings")

        var state = viewModel.uiState.value
        assertEquals("No tab is opened for the request", 1, state.tabs.size)
        assertTrue(state.tabs.none { it.isInternalPage })
        assertEquals(setOf("tab-1"), engine.sessions.keys)

        // A page that navigates itself to a rem:// address fails to load, and the engine
        // reports the failed address. The tab must stay the web tab it is.
        opener.simulatePageStart("rem://settings/privacy")
        opener.simulatePageLoad("rem://settings/privacy", "Problem loading page")
        state = viewModel.uiState.value
        assertEquals("https://example.org/", state.activeTab!!.url)
        assertEquals("https://example.org/", state.addressBarInput)
        assertTrue(state.activeTab!!.isWebPage)
    }

    @Test
    fun restoredSettingsTab_isShownWithoutAnEngineSession() {
        repository.storedState = PersistedBrowserState(
            tabs = listOf(
                BrowserTab(id = "web", url = "https://example.org/"),
                BrowserTab(id = "settings", url = "rem://settings/extensions")
            ),
            paneTabIds = listOf("settings")
        )
        engine = FakeBrowserEngine()
        viewModel = newViewModel()

        val state = viewModel.uiState.value
        assertEquals("rem://settings/extensions", state.activeTab!!.url)
        assertEquals("rem://settings/extensions", state.addressBarInput)
        assertTrue(engine.sessions.isEmpty())
    }

    @Test
    fun searchEngine_isGoogleUntilAnotherIsChosen() {
        assertEquals(SearchEngine.GOOGLE, viewModel.uiState.value.settings.searchEngine)
        // Settings saved before the engine could be chosen, or naming one that no longer exists
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromNameOrDefault(""))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromNameOrDefault("ALTAVISTA"))

        viewModel.onAddressBarChange("kotlin flow")
        viewModel.submitAddressBar()

        assertEquals("https://www.google.com/search?q=kotlin+flow", viewModel.uiState.value.activeTab!!.url)
    }

    @Test
    fun searchEngine_choiceIsUsedForTypedSearchesAndSaved() {
        viewModel.setSearchEngine(SearchEngine.DUCKDUCKGO)
        viewModel.onAddressBarChange("kotlin flow")
        viewModel.submitAddressBar()

        assertEquals("https://duckduckgo.com/?q=kotlin+flow", viewModel.uiState.value.activeTab!!.url)
        assertEquals(SearchEngine.DUCKDUCKGO, repository.storedState.settings.searchEngine)

        // Addresses are never sent to the search engine
        viewModel.onAddressBarChange("example.com")
        viewModel.submitAddressBar()
        assertEquals("https://example.com", viewModel.uiState.value.activeTab!!.url)
    }

    @Test
    fun settings_arePersistedAndPrivacyIsForwardedToTheEngine() {
        // Standard protection by default: the enhanced level is something the user opts into
        assertEquals(false, engine.enhancedPrivacy)

        viewModel.setDarkTheme(false)
        viewModel.setAccentColor(AccentColor.TEAL)
        viewModel.setEnhancedPrivacy(true)

        assertEquals(
            BrowserSettings(useDarkTheme = false, accent = AccentColor.TEAL, enhancedPrivacy = true),
            repository.storedState.settings
        )
        assertEquals(true, engine.enhancedPrivacy)

        // A new browser instance starts from the saved settings
        engine = FakeBrowserEngine()
        val restored = newViewModel()
        assertEquals(AccentColor.TEAL, restored.uiState.value.settings.accent)
        assertEquals(true, engine.enhancedPrivacy)
    }

    @Test
    fun startup_restoresTheLastSessionUnlessToldToStartFresh() {
        val bookmark = BookmarkItem(id = "b1", title = "Docs", url = "https://docs.example/", createdAtMillis = 0L)
        val lastSession = PersistedBrowserState(
            layoutMode = LayoutMode.SPLIT_1X2,
            tabs = listOf(
                BrowserTab(id = "a", url = "https://a.example/"),
                BrowserTab(id = "b", url = "https://b.example/"),
                BrowserTab(id = "c", url = "https://c.example/")
            ),
            paneTabIds = listOf("b", "c"),
            activePaneIndex = 1,
            bookmarks = listOf(bookmark),
            settings = BrowserSettings(accent = AccentColor.GREEN)
        )

        // Default: everything comes back
        repository.storedState = lastSession
        engine = FakeBrowserEngine()
        var state = newViewModel().uiState.value
        assertEquals(listOf("a", "b", "c"), state.tabs.map { it.id })
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertEquals("c", state.activeTabId)

        // "Open a new tab": one start page in a single pane, nothing loaded from last time
        repository.storedState = lastSession.copy(
            settings = lastSession.settings.copy(restoreTabsOnStartup = false)
        )
        engine = FakeBrowserEngine()
        state = newViewModel().uiState.value
        assertEquals(1, state.tabs.size)
        assertTrue(state.activeTab!!.isNewTabPage)
        assertEquals(LayoutMode.SINGLE_1X1, state.layoutMode)
        assertEquals("", state.addressBarInput)
        assertTrue(engine.sessions.values.none { session -> session.loadedUrls.any { it.contains("example") } })
        // What is not a tab is kept
        assertEquals(listOf(bookmark), state.bookmarks)
        assertEquals(AccentColor.GREEN, state.settings.accent)
        assertFalse(state.settings.restoreTabsOnStartup)
        assertEquals("The old tabs are gone from storage too", 1, repository.storedState.tabs.size)
    }

    @Test
    fun newTabs_goToTheEndOrNextToTheCurrentTab() {
        viewModel.openNewTab("https://b.example")
        viewModel.openNewTab("https://c.example")
        viewModel.selectTab("tab-1")

        viewModel.openNewTab("https://end.example")
        assertEquals(listOf("tab-1", "tab-2", "tab-3", "tab-4"), viewModel.uiState.value.tabs.map { it.id })

        viewModel.setOpenNewTabsNextToCurrent(true)
        assertTrue(repository.storedState.settings.openNewTabsNextToCurrent)
        viewModel.selectTab("tab-1")
        viewModel.openNewTab("https://next.example")
        assertEquals(
            listOf("tab-1", "tab-5", "tab-2", "tab-3", "tab-4"),
            viewModel.uiState.value.tabs.map { it.id }
        )
        assertEquals("tab-5", viewModel.uiState.value.activeTabId)

        // A window a page opens lands beside the page that opened it, even when that page
        // is not the tab on screen
        engine.sessions.getValue("tab-3").simulateNewTab("https://popup.example/")
        assertEquals(
            listOf("tab-1", "tab-5", "tab-2", "tab-3", "tab-6", "tab-4"),
            viewModel.uiState.value.tabs.map { it.id }
        )
        viewModel.selectTab("tab-2")
        engine.sessions.getValue("tab-2").simulateNewTab("https://popup2.example/")
        assertEquals(
            listOf("tab-1", "tab-5", "tab-2", "tab-7", "tab-3", "tab-6", "tab-4"),
            viewModel.uiState.value.tabs.map { it.id }
        )
    }

    @Test
    fun desktopSiteDefault_appliesToNewTabsAndToTabsWithNoSiteYet() {
        viewModel.openNewTab("https://loaded.example")
        engine.sessions.getValue("tab-2").simulatePageLoad("https://loaded.example/", "Loaded")
        viewModel.selectTab("tab-1")
        assertTrue(viewModel.uiState.value.tabs.all { it.desktopMode })

        viewModel.setDesktopSiteByDefault(false)

        val tabs = viewModel.uiState.value.tabs.associateBy { it.id }
        assertFalse("The empty start page follows the setting", tabs.getValue("tab-1").desktopMode)
        assertFalse(engine.sessions.getValue("tab-1").desktopModeEnabled)
        assertTrue("A tab already on a site keeps its own choice", tabs.getValue("tab-2").desktopMode)
        assertTrue(engine.sessions.getValue("tab-2").desktopModeEnabled)
        assertFalse(repository.storedState.settings.desktopSiteByDefault)

        val newTabId = viewModel.openNewTab("https://mobile.example")
        assertFalse(viewModel.uiState.value.tabs.first { it.id == newTabId }.desktopMode)
        assertFalse(engine.sessions.getValue(newTabId).desktopModeEnabled)

        // The per-tab switch still works on top of the default
        viewModel.toggleDesktopMode()
        assertTrue(viewModel.uiState.value.activeTab!!.desktopMode)

        // A window opened by a page keeps asking for what its opener asks for
        engine.sessions.getValue("tab-2").simulateNewTab("https://popup.example/")
        assertTrue(viewModel.uiState.value.tabs.last().desktopMode)

        // And a fresh start uses the saved default for its first tab
        repository.storedState = PersistedBrowserState(settings = repository.storedState.settings)
        engine = FakeBrowserEngine()
        assertFalse(newViewModel().uiState.value.activeTab!!.desktopMode)
    }

    @Test
    fun downloads_areHandedToWhoeverSavesFilesOrReleasedWhenNobodyDoes() {
        val session = engine.sessions.getValue("tab-1")

        val dropped = session.simulateDownload("https://example.org/a.zip", "a.zip", ByteArray(4))
        assertTrue("Without a handler the connection is let go", (dropped.body as TrackedInputStream).isClosed)

        val received = mutableListOf<DownloadRequest>()
        viewModel.downloadHandler = { received += it }
        val kept = session.simulateDownload("https://example.org/b.zip", "b.zip", ByteArray(4))

        assertEquals(listOf(kept), received)
        assertEquals("tab-1", kept.tabId)
        assertFalse("The handler owns the body now", (kept.body as TrackedInputStream).isClosed)

        assertTrue(viewModel.uiState.value.settings.askBeforeDownloading)
        viewModel.setAskBeforeDownloading(false)
        assertFalse(repository.storedState.settings.askBeforeDownloading)
    }

    @Test
    fun closeTabLeftBehindByDownload_closesOnlyATabThatWasOpenedForTheFile() {
        val opener = engine.sessions.getValue("tab-1")
        opener.simulatePageLoad("https://example.org/", "Example")

        // A link with target=_blank that turns out to be a file: its tab never loads a page
        opener.simulateNewTab("https://example.org/files/report.pdf")
        val downloadTab = viewModel.uiState.value.activeTab!!
        val fromNewTab = engine.sessions.getValue(downloadTab.id)
            .simulateDownload("https://example.org/files/report.pdf", "report.pdf")
        assertEquals(2, viewModel.uiState.value.tabs.size)

        viewModel.closeTabLeftBehindByDownload(fromNewTab)
        assertEquals(listOf("tab-1"), viewModel.uiState.value.tabs.map { it.id })
        assertEquals("tab-1", viewModel.uiState.value.activeTabId)

        // The same when the link redirects: the engine reports the new session's blank page
        // and the start of a load, but the file arrives from a different address and no
        // document is ever shown
        opener.simulateNewTab("https://example.org/get?id=7")
        val redirectedTab = viewModel.uiState.value.activeTab!!
        val redirected = engine.sessions.getValue(redirectedTab.id)
        redirected.simulateHistoryMove("about:blank", canGoBack = false, canGoForward = false)
        redirected.simulatePageStart("https://example.org/get?id=7")
        val fromCdn = redirected.simulateDownload("https://cdn.example.net/builds/app.zip", "app.zip")
        viewModel.closeTabLeftBehindByDownload(fromCdn)
        assertEquals(listOf("tab-1"), viewModel.uiState.value.tabs.map { it.id })

        // A download started from an ordinary page leaves that page alone
        val fromPage = opener.simulateDownload("https://example.org/files/report.pdf", "report.pdf")
        viewModel.closeTabLeftBehindByDownload(fromPage)
        assertEquals(listOf("tab-1"), viewModel.uiState.value.tabs.map { it.id })

        // So does one from a pop-up that did load a page of its own
        opener.simulateNewTab("https://popup.example/")
        val popup = viewModel.uiState.value.activeTab!!
        engine.sessions.getValue(popup.id).simulatePageLoad("https://popup.example/", "Pop-up", canGoBack = false)
        val fromPopup = engine.sessions.getValue(popup.id).simulateDownload("https://popup.example/x.zip", "x.zip")
        viewModel.closeTabLeftBehindByDownload(fromPopup)
        assertEquals(2, viewModel.uiState.value.tabs.size)

        // A request whose tab is already gone is simply ignored
        viewModel.closeTab(popup.id)
        viewModel.closeTabLeftBehindByDownload(fromPopup)
        assertEquals(listOf("tab-1"), viewModel.uiState.value.tabs.map { it.id })
    }
}
