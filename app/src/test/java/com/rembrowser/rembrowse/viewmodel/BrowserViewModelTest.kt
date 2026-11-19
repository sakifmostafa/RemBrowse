package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PersistedBrowserState
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
}
