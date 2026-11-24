package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.TrackedInputStream
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PageMediaType
import com.rembrowser.rembrowse.model.PageMenuAction
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun setLayoutMode_oneAndTwo_putsTheFocusedTabOnTheLeftAndTwoMoreOnTheRight() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1_2)

        val state = viewModel.uiState.value
        assertEquals(LayoutMode.SPLIT_1_2, state.layoutMode)
        assertEquals(3, state.paneTabIds.size)
        assertEquals("tab-1", state.paneTabIds[0])
        assertEquals(
            PaneNode.Split(
                SplitOrientation.ROW,
                0.5f,
                PaneNode.Leaf(state.paneTabIds[0]),
                PaneNode.Split(SplitOrientation.COLUMN, 0.5f, PaneNode.Leaf(state.paneTabIds[1]), PaneNode.Leaf(state.paneTabIds[2]))
            ),
            state.layout
        )
        assertEquals(3, engine.sessions.size)
        assertEquals(state.layout, repository.storedState.layout)
        assertEquals(
            "An older version, which only knows the first four layouts, gets the grid",
            LayoutMode.GRID_2X2,
            repository.storedState.layoutMode
        )
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
    fun openBookmark_showsTheTabUsedLastOnItsSiteInsteadOfOpeningAnother() {
        viewModel.openNewTab("https://web.telegram.org/a/#-1001234567") // tab-2
        viewModel.openNewTab("https://web.telegram.org/a/#-1007654321") // tab-3
        viewModel.openNewTab("https://news.example/") // tab-4
        viewModel.selectTab("tab-2")
        viewModel.selectTab("tab-4")

        // A pinned Telegram at /a/ is the same site (origin) as either chat
        viewModel.openBookmark("https://web.telegram.org/a/")

        val state = viewModel.uiState.value
        assertEquals(4, state.tabs.size)
        assertEquals("tab-2", state.activeTabId)
        assertEquals("https://web.telegram.org/a/#-1001234567", state.activeTab!!.url)
    }

    @Test
    fun openBookmark_anotherSchemeOrPortIsAnotherSite() {
        viewModel.openNewTab("http://web.telegram.org/a/")
        viewModel.openNewTab("https://web.telegram.org:8443/a/")

        viewModel.openBookmark("https://web.telegram.org/a/")

        val state = viewModel.uiState.value
        assertEquals(4, state.tabs.size)
        assertEquals("https://web.telegram.org/a/", state.activeTab!!.url)
    }

    @Test
    fun openBookmark_aPageBookmarkShowsOnlyATabAtThatPageOrBelowIt() {
        viewModel.openNewTab("https://github.com/other") // tab-2
        viewModel.openNewTab("https://news.example/") // tab-3

        // Another page of GitHub is not the bookmarked repository: it opens
        viewModel.openBookmark("https://github.com/org/repo")
        var state = viewModel.uiState.value
        assertEquals(4, state.tabs.size)
        assertEquals("https://github.com/org/repo", state.activeTab!!.url)

        // A page below it is: shown, not opened again
        viewModel.closeTab("tab-4")
        viewModel.openNewTab("https://github.com/org/repo/issues/12") // tab-5
        viewModel.selectTab("tab-3")
        viewModel.openBookmark("https://github.com/org/repo")
        state = viewModel.uiState.value
        assertEquals(4, state.tabs.size)
        assertEquals("tab-5", state.activeTabId)
        assertEquals("https://github.com/org/repo/issues/12", state.activeTab!!.url)
    }

    @Test
    fun openBookmark_onTheActiveTabAtTheBookmarkedPage_changesNothing() {
        viewModel.openNewTab("https://web.telegram.org/a/#-1001234567")
        val chat = viewModel.uiState.value.activeTabId!!
        val loads = engine.sessions.getValue(chat).loadedUrls.toList()

        viewModel.openBookmark("https://web.telegram.org/a/")

        val state = viewModel.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals(chat, state.activeTabId)
        assertEquals("https://web.telegram.org/a/#-1001234567", state.activeTab!!.url)
        assertEquals("The chat is not reloaded", loads, engine.sessions.getValue(chat).loadedUrls)
    }

    @Test
    fun openBookmark_onTheActiveTabElsewhereBelowTheBookmark_goesBackToTheBookmarkedPage() {
        viewModel.openNewTab("https://github.com/org/repo/pulls")
        val repo = viewModel.uiState.value.activeTabId!!

        viewModel.openBookmark("https://github.com/org/repo")

        val state = viewModel.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals(repo, state.activeTabId)
        assertEquals("https://github.com/org/repo", state.activeTab!!.url)
        assertEquals("https://github.com/org/repo", engine.sessions.getValue(repo).loadedUrls.last())
    }

    @Test
    fun openBookmark_prefersATabAlreadyInAPaneOverOneUsedMoreRecentlyInTheBackground() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        viewModel.focusPane(1)
        viewModel.navigatePane(1, "https://github.com/org/repo/issues")
        val inPane = viewModel.uiState.value.paneTabIds[1]
        viewModel.focusPane(0)
        val background = viewModel.openNewTab("https://github.com/org/repo/pulls")
        viewModel.openNewTab("https://news.example/")
        assertTrue(background !in viewModel.uiState.value.paneTabIds)

        viewModel.openBookmark("https://github.com/org/repo")

        val state = viewModel.uiState.value
        assertEquals("The pane showing it is focused", 1, state.activePaneIndex)
        assertEquals(inPane, state.activeTabId)
        assertEquals("https://github.com/org/repo/issues", state.activeTab!!.url)
    }

    @Test
    fun openBookmarkInNewTab_opensANewTabEvenWhenTheSiteIsOpen() {
        viewModel.openNewTab("https://web.telegram.org/a/#-1001234567")
        val chat = viewModel.uiState.value.activeTabId

        viewModel.openBookmarkInNewTab("https://web.telegram.org/a/")

        val state = viewModel.uiState.value
        assertEquals(3, state.tabs.size)
        assertNotEquals(chat, state.activeTabId)
        assertEquals("https://web.telegram.org/a/", state.activeTab!!.url)
    }

    @Test
    fun openHistoryEntry_opensThatPageEvenWithAnotherPageOfTheSiteOpen() {
        viewModel.openNewTab("https://news.example/today")
        val today = viewModel.uiState.value.activeTabId

        viewModel.openHistoryEntry("https://news.example/yesterday")

        val state = viewModel.uiState.value
        assertEquals(3, state.tabs.size)
        assertNotEquals(today, state.activeTabId)
        assertEquals("https://news.example/yesterday", state.activeTab!!.url)
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

        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setAccentColor(AccentColor.TEAL)
        viewModel.setEnhancedPrivacy(true)

        assertEquals(
            BrowserSettings(themeMode = ThemeMode.LIGHT, accent = AccentColor.TEAL, enhancedPrivacy = true),
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
    fun setSwipeNavigation_changesTheSettingAndSavesIt() {
        val repository = FakeBrowserStateRepository()
        val viewModel = BrowserViewModel(FakeBrowserEngine(), repository)
        assertTrue(viewModel.uiState.value.settings.swipeNavigation)

        viewModel.setSwipeNavigation(false)
        assertFalse(viewModel.uiState.value.settings.swipeNavigation)
        assertFalse(repository.storedState.settings.swipeNavigation)

        viewModel.setSwipeNavigation(true)
        assertTrue(viewModel.uiState.value.settings.swipeNavigation)
        assertTrue(repository.storedState.settings.swipeNavigation)
    }

    @Test
    fun setReverseSwipeDirection_changesTheSettingAndSavesIt() {
        val repository = FakeBrowserStateRepository()
        val viewModel = BrowserViewModel(FakeBrowserEngine(), repository)
        assertFalse(viewModel.uiState.value.settings.reverseSwipeDirection)

        viewModel.setReverseSwipeDirection(true)
        assertTrue(viewModel.uiState.value.settings.reverseSwipeDirection)
        assertTrue(repository.storedState.settings.reverseSwipeDirection)

        viewModel.setReverseSwipeDirection(false)
        assertFalse(viewModel.uiState.value.settings.reverseSwipeDirection)
        assertFalse(repository.storedState.settings.reverseSwipeDirection)
    }

    @Test
    fun setPageRendering_changesTheSettingAndSavesIt() {
        val repository = FakeBrowserStateRepository()
        val viewModel = BrowserViewModel(FakeBrowserEngine(), repository)
        assertEquals(PageRendering.STANDARD, viewModel.uiState.value.settings.pageRendering)

        viewModel.setPageRendering(PageRendering.DIRECT)

        assertEquals(PageRendering.DIRECT, viewModel.uiState.value.settings.pageRendering)
        assertEquals(PageRendering.DIRECT, repository.storedState.settings.pageRendering)
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

    /** Reddit beside YouTube, with a third tab waiting in the rail. */
    private fun redditBesideYouTube(): Triple<String, String, String> {
        val reddit = "tab-1"
        viewModel.navigatePane(0, "https://reddit.com/")
        val news = viewModel.openNewTab("https://news.example/")
        val youtube = viewModel.openNewTab("https://youtube.com/")
        viewModel.selectTab(reddit)
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        viewModel.assignTabToPane(1, youtube)
        assertEquals(listOf(reddit, youtube), viewModel.uiState.value.paneTabIds)
        return Triple(reddit, youtube, news)
    }

    @Test
    fun dropOnAnEdge_splitsThatPaneAndShowsTheTabInTheNewOne() {
        val (reddit, youtube, news) = redditBesideYouTube()

        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)

        val state = viewModel.uiState.value
        assertEquals(
            "Reddit keeps the full height; YouTube and the new pane share the right column",
            PaneNode.Split(
                SplitOrientation.ROW,
                0.5f,
                PaneNode.Leaf(reddit),
                PaneNode.Split(SplitOrientation.COLUMN, 0.5f, PaneNode.Leaf(youtube), PaneNode.Leaf(news))
            ),
            state.layout
        )
        assertEquals(listOf(reddit, youtube, news), state.paneTabIds)
        assertEquals("The shape of the One left, two right preset", LayoutMode.SPLIT_1_2, state.layoutMode)
        assertEquals("Focus and the address bar go to the dropped tab", news, state.activeTabId)
        assertEquals("https://news.example/", state.addressBarInput)
        assertTrue(engine.sessions.getValue(news).visibleState)
        assertEquals(state.layout, repository.storedState.layout)
        assertEquals("An older version still finds its fields", listOf(reddit, youtube, news), repository.storedState.paneTabIds)
    }

    @Test
    fun dropOnTheCentre_replacesThePanesTabWhichStaysOpenInTheRail() {
        val (reddit, youtube, news) = redditBesideYouTube()

        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.CENTER)

        val state = viewModel.uiState.value
        assertEquals(listOf(reddit, news), state.paneTabIds)
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertTrue("The replaced tab is still open", state.tabs.any { it.id == youtube })
        assertFalse("...but no longer painted", engine.sessions.getValue(youtube).visibleState)
        assertTrue(engine.sessions.getValue(news).visibleState)
        assertEquals(1, state.activePaneIndex)
    }

    @Test
    fun dropOfATabThatIsAlreadyOnScreen_movesItAndClosesThePaneItLeft() {
        val (reddit, youtube, news) = redditBesideYouTube()
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)

        // From under YouTube to the left of Reddit
        viewModel.dropTabOnPane(news, targetPaneIndex = 0, zone = DropZone.LEFT)
        assertEquals(listOf(news, reddit, youtube), viewModel.uiState.value.paneTabIds)
        assertEquals(3, viewModel.uiState.value.paneCount)

        // Onto the centre of YouTube: it takes that pane over, and its old pane is gone
        viewModel.dropTabOnPane(news, targetPaneIndex = 2, zone = DropZone.CENTER)
        val state = viewModel.uiState.value
        assertEquals(listOf(reddit, news), state.paneTabIds)
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertFalse(engine.sessions.getValue(youtube).visibleState)
        assertEquals(3, state.tabs.size)

        // Dropping a tab on its own pane does nothing
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.TOP)
        assertEquals(state.layout, viewModel.uiState.value.layout)
    }

    @Test
    fun drop_neverMakesMoreThanFourPanes() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        val before = viewModel.uiState.value.layout
        val fifth = viewModel.openNewTab("https://five.example/")
        viewModel.assignTabToPane(0, "tab-1")
        assertEquals(before, viewModel.uiState.value.layout)
        assertFalse(fifth in viewModel.uiState.value.paneTabIds)

        viewModel.dropTabOnPane(fifth, targetPaneIndex = 3, zone = DropZone.RIGHT)
        assertEquals("No room for a fifth pane", before, viewModel.uiState.value.layout)

        // The centre still works when full
        viewModel.dropTabOnPane(fifth, targetPaneIndex = 3, zone = DropZone.CENTER)
        assertEquals(4, viewModel.uiState.value.paneCount)
        assertEquals(fifth, viewModel.uiState.value.paneTabIds[3])

        // And a pane that is already on screen can be moved to another edge
        viewModel.dropTabOnPane(fifth, targetPaneIndex = 0, zone = DropZone.TOP)
        assertEquals(4, viewModel.uiState.value.paneCount)
        assertEquals(fifth, viewModel.uiState.value.paneTabIds[0])

        viewModel.dropTabOnPane("no-such-tab", targetPaneIndex = 0, zone = DropZone.CENTER)
        viewModel.dropTabOnPane(fifth, targetPaneIndex = 9, zone = DropZone.CENTER)
        assertEquals(4, viewModel.uiState.value.paneCount)
    }

    @Test
    fun closePane_collapsesItsSplitAndKeepsTheTabOpen() {
        val (reddit, youtube, news) = redditBesideYouTube()
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)
        viewModel.focusPane(0)

        viewModel.closePane(1)

        var state = viewModel.uiState.value
        assertEquals("The pane below takes the whole column", listOf(reddit, news), state.paneTabIds)
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertTrue(state.tabs.any { it.id == youtube })
        assertFalse(engine.sessions.getValue(youtube).visibleState)
        assertEquals("Focus stays with the page it was on", reddit, state.activeTabId)

        // Closing the focused pane hands focus (and the address bar) to what remains
        viewModel.closePane()
        state = viewModel.uiState.value
        assertEquals(listOf(news), state.paneTabIds)
        assertEquals(news, state.activeTabId)
        assertEquals("https://news.example/", state.addressBarInput)

        // The last pane stays
        viewModel.closePane()
        assertEquals(listOf(news), viewModel.uiState.value.paneTabIds)
    }

    @Test
    fun everySplitHasItsOwnRatio() {
        val (_, _, news) = redditBesideYouTube()
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)
        val saves = repository.saveCount

        viewModel.resizeSplit("1", 0.2f)
        assertEquals(listOf(0.5f, 0.7f), viewModel.uiState.value.layout.splits().map { it.ratio })
        viewModel.resizeSplit("", -0.9f)
        assertEquals(SplitRatios.MIN_RATIO, viewModel.uiState.value.layout.splits()[0].ratio, 0.001f)
        assertEquals("Dragging does not write to storage", saves, repository.saveCount)
        viewModel.commitSplitRatios()
        assertEquals(viewModel.uiState.value.layout, repository.storedState.layout)

        // The presets' sizes follow the outermost divider of each direction
        assertEquals(SplitRatios.MIN_RATIO, viewModel.uiState.value.splitRatios.horizontal, 0.001f)
        assertEquals(0.7f, viewModel.uiState.value.splitRatios.vertical, 0.001f)

        viewModel.resetSplit("1")
        assertEquals(listOf(SplitRatios.MIN_RATIO, 0.5f), viewModel.uiState.value.layout.splits().map { it.ratio })
    }

    @Test
    fun draggingADividerSetsItsSplitToWhereThePointerIs_withinTheLimits() {
        val (_, _, news) = redditBesideYouTube()
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)
        val saves = repository.saveCount

        viewModel.setSplitRatio("1", 0.65f)
        assertEquals(listOf(0.5f, 0.65f), viewModel.uiState.value.layout.splits().map { it.ratio })
        assertEquals(0.65f, viewModel.uiState.value.splitRatios.vertical, 0.001f)

        // The pointer may be anywhere, the divider waits at its limit
        viewModel.setSplitRatio("", 1.4f)
        assertEquals(SplitRatios.MAX_RATIO, viewModel.uiState.value.layout.splits()[0].ratio, 0.001f)
        val atLimit = viewModel.uiState.value
        viewModel.setSplitRatio("", 1.7f)
        assertSame("Nothing to redraw while it waits", atLimit, viewModel.uiState.value)
        viewModel.setSplitRatio("", Float.NaN)
        assertEquals(0.5f, viewModel.uiState.value.layout.splits()[0].ratio, 0.001f)

        viewModel.setSplitRatio("0", 0.3f)
        assertEquals("No split there", listOf(0.5f, 0.65f), viewModel.uiState.value.layout.splits().map { it.ratio })
        assertEquals("Dragging does not write to storage", saves, repository.saveCount)
    }

    @Test
    fun closingATabInACustomLayout_refillsItsPaneAndKeepsTheShape() {
        val (reddit, youtube, news) = redditBesideYouTube()
        viewModel.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)
        val splits = viewModel.uiState.value.layout.splits()

        viewModel.closeTab(youtube)

        val state = viewModel.uiState.value
        assertEquals(3, state.paneCount)
        assertEquals(splits, state.layout.splits())
        assertEquals(reddit, state.paneTabIds[0])
        assertEquals(news, state.paneTabIds[2])
        assertTrue("With no other tab to show, the pane gets a start page", state.tabForPane(1)!!.isNewTabPage)
    }

    private fun threeTabs() = listOf(
        BrowserTab(id = "a", url = "https://a.example/"),
        BrowserTab(id = "b", url = "https://b.example/"),
        BrowserTab(id = "c", url = "https://c.example/")
    )

    @Test
    fun startup_migratesTheFixedLayoutsOfEarlierVersions() {
        // Saved by a version without free-form layouts: no layout tree at all
        repository.storedState = PersistedBrowserState(
            layoutMode = LayoutMode.GRID_2X2,
            splitRatios = SplitRatios(horizontal = 0.3f, vertical = 0.7f),
            tabs = threeTabs(),
            paneTabIds = listOf("b", "a"),
            activePaneIndex = 1
        )
        engine = FakeBrowserEngine()

        val state = newViewModel().uiState.value

        assertEquals(LayoutMode.GRID_2X2, state.layoutMode)
        assertEquals("b", state.paneTabIds[0])
        assertEquals("a", state.paneTabIds[1])
        assertEquals("c", state.paneTabIds[2])
        assertTrue("The fourth pane had no tab: it gets a start page", state.tabForPane(3)!!.isNewTabPage)
        assertEquals("a", state.activeTabId)
        val grid = state.layout as PaneNode.Split
        assertEquals(SplitOrientation.COLUMN, grid.orientation)
        assertEquals(0.7f, grid.ratio, 0.001f)
        assertEquals(0.3f, (grid.first as PaneNode.Split).ratio, 0.001f)
        assertEquals("From now on the layout itself is saved", state.layout, repository.storedState.layout)
    }

    @Test
    fun startup_restoresACustomLayoutAndShowsOnlyItsTabs() {
        val saved = PaneNode.Split(
            SplitOrientation.ROW,
            0.6f,
            PaneNode.Leaf("a"),
            PaneNode.Split(SplitOrientation.COLUMN, 0.3f, PaneNode.Leaf("c"), PaneNode.Leaf("gone"))
        )
        repository.storedState = PersistedBrowserState(
            // What an older version would have made of it; the tree wins
            layoutMode = LayoutMode.GRID_2X2,
            tabs = threeTabs(),
            paneTabIds = listOf("a", "c", "gone"),
            activePaneIndex = 2,
            layout = saved
        )
        engine = FakeBrowserEngine()

        val state = newViewModel().uiState.value

        assertEquals(
            "The pane of a tab that no longer exists is closed",
            PaneNode.Split(SplitOrientation.ROW, 0.6f, PaneNode.Leaf("a"), PaneNode.Leaf("c")),
            state.layout
        )
        assertEquals("c", state.activeTabId)
        assertEquals(0.6f, state.splitRatios.horizontal, 0.001f)
        assertTrue(engine.sessions.getValue("a").visibleState)
        assertTrue(engine.sessions.getValue("c").visibleState)
        assertFalse("Background tabs get no session until shown", engine.sessions.containsKey("b"))
    }

    @Test
    fun startup_fallsBackToASinglePaneWhenNothingOfTheSavedLayoutIsLeft() {
        repository.storedState = PersistedBrowserState(
            tabs = threeTabs(),
            layout = PaneNode.Split(SplitOrientation.ROW, 0.5f, PaneNode.Leaf("x"), PaneNode.Leaf("y"))
        )
        engine = FakeBrowserEngine()

        val state = newViewModel().uiState.value

        assertEquals(listOf("a"), state.paneTabIds)
        assertEquals(3, state.tabs.size)
    }

    @Test
    fun fullscreen_isTrackedForTheVisibleTabThatAskedAndEndsWithThePage() {
        val (reddit, youtube, news) = redditBesideYouTube()
        val layout = viewModel.uiState.value.layout
        viewModel.focusPane(1)

        engine.sessions.getValue(reddit).simulateFullScreen(true)
        var state = viewModel.uiState.value
        assertEquals(reddit, state.fullscreenTabId)
        assertEquals("The layout and focus are left as they were", layout, state.layout)
        assertEquals(youtube, state.activeTabId)

        // Another tab leaving fullscreen is not ours to act on
        engine.sessions.getValue(youtube).simulateFullScreen(false)
        assertEquals(reddit, viewModel.uiState.value.fullscreenTabId)

        // The page leaves by itself (its own button, or Esc)
        engine.sessions.getValue(reddit).simulateFullScreen(false)
        state = viewModel.uiState.value
        assertNull(state.fullscreenTabId)
        assertEquals(layout, state.layout)
        assertEquals(youtube, state.activeTabId)

        // Back: the browser asks the page to leave
        engine.sessions.getValue(reddit).simulateFullScreen(true)
        viewModel.exitFullscreen()
        assertNull(viewModel.uiState.value.fullscreenTabId)
        assertEquals(1, engine.sessions.getValue(reddit).exitFullScreenCount)

        // A tab nobody can see is not allowed to take the window
        viewModel.closePane(1)
        engine.sessions.getValue(youtube).simulateFullScreen(true)
        assertNull(viewModel.uiState.value.fullscreenTabId)
        assertEquals(1, engine.sessions.getValue(youtube).exitFullScreenCount)
        assertTrue(viewModel.uiState.value.tabs.any { it.id == news })
    }

    @Test
    fun fullscreen_endsWhenTheTabClosesOrItsPaneShowsSomethingElse() {
        val (reddit, youtube, news) = redditBesideYouTube()

        engine.sessions.getValue(youtube).simulateFullScreen(true)
        viewModel.closeTab(youtube)
        assertNull("Closed while fullscreen", viewModel.uiState.value.fullscreenTabId)
        assertEquals("The split is still there", 2, viewModel.uiState.value.paneCount)

        val redditSession = engine.sessions.getValue(reddit)
        redditSession.simulateFullScreen(true)
        assertEquals(reddit, viewModel.uiState.value.fullscreenTabId)
        assertEquals("The closed tab's pane was refilled from the rail", listOf(reddit, news), viewModel.uiState.value.paneTabIds)
        viewModel.openNewTab("https://other.example/", assignToPane = 0)
        assertFalse(reddit in viewModel.uiState.value.paneTabIds)
        assertNull("Its pane went on to show another tab", viewModel.uiState.value.fullscreenTabId)
        assertEquals("The page is told, so it does not stay fullscreen unseen", 1, redditSession.exitFullScreenCount)

        engine.sessions.getValue(news).simulateFullScreen(true)
        engine.sessions.getValue(news).simulateCrashLoop()
        assertNull("A crashed page shows nothing", viewModel.uiState.value.fullscreenTabId)
    }

    // --- Page context menu ---

    private fun pageOnScreen(url: String = "https://example.com/"): String {
        viewModel.navigatePane(0, url)
        val tabId = viewModel.uiState.value.activeTabId!!
        engine.sessions.getValue(tabId).simulatePageLoad(url, "Example")
        return tabId
    }

    @Test
    fun pageMenu_opensForAPageOnScreen_andNotForOneInTheBackground() {
        val shown = pageOnScreen()
        engine.sessions.getValue(shown).simulateContextMenu(linkUrl = "https://example.com/a")
        assertEquals(shown, viewModel.pageMenu.value?.tabId)
        assertEquals("https://example.com/a", viewModel.pageMenu.value?.linkUrl)

        viewModel.dismissPageMenu()
        val background = viewModel.openTabInBackground("https://other.example/", shown)
        engine.sessions.getValue(background).simulateContextMenu()
        assertNull(viewModel.pageMenu.value)
    }

    @Test
    fun ctrlClickedLink_opensInTheBackgroundWithoutChangingTheActiveTab() {
        val tabId = pageOnScreen()
        val before = viewModel.uiState.value

        engine.sessions.getValue(tabId).simulateOpenLink("https://example.com/next", inForeground = false)

        val state = viewModel.uiState.value
        assertEquals(before.tabs.size + 1, state.tabs.size)
        assertEquals(tabId, state.activeTabId)
        assertEquals(before.layout, state.layout)
        val opened = state.tabs.single { it.id !in before.tabs.map { t -> t.id } }
        assertEquals("https://example.com/next", opened.url)
    }

    @Test
    fun shiftCtrlClickedLink_opensInTheForegroundAsAPageOpenedTab() {
        val tabId = pageOnScreen()
        val before = viewModel.uiState.value

        engine.sessions.getValue(tabId).simulateOpenLink("https://example.com/next", inForeground = true)

        val state = viewModel.uiState.value
        assertEquals(before.tabs.size + 1, state.tabs.size)
        val opened = state.activeTab!!
        assertNotEquals(tabId, opened.id)
        assertEquals("https://example.com/next", opened.url)
        assertTrue(opened.openedByPage)
        assertEquals(tabId, opened.openerTabId)
    }

    @Test
    fun openLinkRequest_fromAnUnknownTab_isIgnored() {
        val tabId = pageOnScreen()
        val before = viewModel.uiState.value

        engine.sessions.getValue(tabId).simulateOpenLink("https://example.com/x", fromTabId = "no-such-tab")
        engine.sessions.getValue(tabId).simulateOpenLink("https://example.com/x", inForeground = true, fromTabId = "no-such-tab")

        assertEquals(before.tabs.map { it.id }, viewModel.uiState.value.tabs.map { it.id })
        assertEquals(before.activeTabId, viewModel.uiState.value.activeTabId)
    }

    @Test
    fun openLinkRequest_fromATabNotShownInAnyPane_isIgnored() {
        val shown = pageOnScreen()
        val background = viewModel.openTabInBackground("https://other.example/", shown)
        val before = viewModel.uiState.value

        engine.sessions.getValue(background).simulateOpenLink("https://example.com/x")
        engine.sessions.getValue(background).simulateOpenLink("https://example.com/x", inForeground = true)

        assertEquals(before.tabs.map { it.id }, viewModel.uiState.value.tabs.map { it.id })
        assertEquals(shown, viewModel.uiState.value.activeTabId)
    }

    @Test
    fun openLinkRequest_fromAnInternalPage_isIgnored() {
        val web = pageOnScreen()
        viewModel.openSettings()
        val settings = viewModel.uiState.value.activeTab!!
        assertTrue(settings.isInternalPage)
        val before = viewModel.uiState.value

        engine.sessions.getValue(web).simulateOpenLink("https://example.com/x", fromTabId = settings.id)
        engine.sessions.getValue(web).simulateOpenLink("https://example.com/x", inForeground = true, fromTabId = settings.id)

        assertEquals(before.tabs.map { it.id }, viewModel.uiState.value.tabs.map { it.id })
        assertEquals(settings.id, viewModel.uiState.value.activeTabId)
    }

    @Test
    fun pageMenu_closesWhenItsPageGoesAwayOrLeavesTheScreen() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)

        session.simulateContextMenu()
        session.simulatePageStart("https://example.com/next")
        assertNull("The menu was about the page that was there", viewModel.pageMenu.value)

        session.simulateContextMenu(serial = 2)
        viewModel.openNewTab("https://elsewhere.example/")
        assertNull(viewModel.pageMenu.value)
    }

    @Test
    fun openLinkInNewTab_opensItInTheBackgroundNextToItsPage() {
        viewModel.setOpenNewTabsNextToCurrent(true)
        val tabId = pageOnScreen()
        val other = viewModel.openTabInBackground("https://last.example/", null)
        val layoutBefore = viewModel.uiState.value.layout

        engine.sessions.getValue(tabId).simulateContextMenu(linkUrl = "https://example.com/story")
        viewModel.onPageMenuAction(PageMenuAction.OPEN_LINK_IN_NEW_TAB)

        val state = viewModel.uiState.value
        assertNull(viewModel.pageMenu.value)
        assertEquals("The page stays where it is", layoutBefore, state.layout)
        assertEquals(tabId, state.activeTabId)
        val opened = state.tabs[state.tabs.indexOfFirst { it.id == tabId } + 1]
        assertEquals("https://example.com/story", opened.url)
        assertNotEquals(other, opened.id)
        assertEquals("It starts loading at once", listOf("https://example.com/story"), engine.sessions.getValue(opened.id).loadedUrls)
        assertFalse("A page nobody sees is paused", engine.sessions.getValue(opened.id).visibleState)
    }

    @Test
    fun openLinkInSplitView_opensItBesideItsPage_whichKeepsFocus() {
        val tabId = pageOnScreen()
        engine.sessions.getValue(tabId).simulateContextMenu(linkUrl = "https://example.com/story")
        viewModel.onPageMenuAction(PageMenuAction.OPEN_LINK_IN_SPLIT)

        val state = viewModel.uiState.value
        assertEquals(2, state.paneCount)
        assertEquals(tabId, state.paneTabIds[0])
        assertEquals("https://example.com/story", state.tabForPane(1)?.url)
        assertEquals(tabId, state.activeTabId)
    }

    @Test
    fun openImageInNewTab_andThePagesOwnActions() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)

        session.simulateContextMenu(mediaUrl = "https://cdn.example.com/p.jpg", mediaType = PageMediaType.IMAGE)
        viewModel.onPageMenuAction(PageMenuAction.OPEN_MEDIA_IN_NEW_TAB)
        assertTrue(viewModel.uiState.value.tabs.any { it.url == "https://cdn.example.com/p.jpg" })

        session.simulateContextMenu(serial = 2)
        viewModel.onPageMenuAction(PageMenuAction.BACK)
        assertEquals(1, session.goBackCount)

        session.simulateContextMenu(serial = 3)
        viewModel.onPageMenuAction(PageMenuAction.RELOAD)
        assertEquals(1, session.reloadCount)

        session.simulateContextMenu(serial = 4)
        viewModel.onPageMenuAction(PageMenuAction.BOOKMARK)
        assertTrue(viewModel.uiState.value.isBookmarked("https://example.com/"))

        session.simulateContextMenu(serial = 5)
        viewModel.onPageMenuAction(PageMenuAction.FIND_IN_PAGE)
        assertEquals(tabId, viewModel.findInPage.value?.tabId)
    }

    // --- Printing ---

    @Test
    fun printPage_printsTheActiveWebPage() {
        val tabId = pageOnScreen()

        viewModel.printPage()

        assertEquals(1, engine.sessions.getValue(tabId).printCount)
    }

    @Test
    fun printPage_doesNothingForTheStartPageOrSettings() {
        val start = viewModel.uiState.value.activeTabId!!
        assertTrue(viewModel.uiState.value.activeTab!!.isNewTabPage)
        viewModel.printPage()
        assertEquals(0, engine.sessions[start]?.printCount ?: 0)

        val web = pageOnScreen()
        viewModel.openSettings()
        assertTrue(viewModel.uiState.value.activeTab!!.isInternalPage)
        viewModel.printPage()

        assertEquals("Nothing printed anywhere", 0, engine.sessions.values.sumOf { it.printCount })
        assertEquals(0, engine.sessions.getValue(web).printCount)
    }

    @Test
    fun printPage_doesNothingForATerminal() {
        val page = pageOnScreen()
        viewModel.openTerminal()
        assertTrue(viewModel.uiState.value.activeTab!!.isTerminal)

        viewModel.printPage()

        assertEquals(0, engine.sessions.getValue(page).printCount)
    }

    @Test
    fun printPage_withAPaneIndexPrintsThatPanesPage() {
        val first = pageOnScreen()
        viewModel.setLayoutMode(com.rembrowser.rembrowse.model.LayoutMode.SPLIT_1X2)
        viewModel.navigatePane(1, "https://other.example/")
        val second = viewModel.uiState.value.tabForPane(1)!!.id
        assertNotEquals(first, second)
        engine.sessions.getValue(second).simulatePageLoad("https://other.example/", "Other")

        viewModel.printPage(1)

        assertEquals(1, engine.sessions.getValue(second).printCount)
        assertEquals(0, engine.sessions.getValue(first).printCount)
    }

    @Test
    fun printPageMenuAction_printsThePageTheMenuWasOpenedOn() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)
        session.simulateContextMenu()

        viewModel.onPageMenuAction(PageMenuAction.PRINT)

        assertEquals(1, session.printCount)
        assertNull(viewModel.pageMenu.value)
    }

    // --- Terminal tabs ---

    @Test
    fun aTerminal_takesOverAnUntouchedStartPage_andHasNoEngineSession() {
        val startPage = viewModel.uiState.value.activeTabId!!
        val terminal = viewModel.openTerminal()
        assertEquals("The start page became the terminal", startPage, terminal)
        val tab = viewModel.uiState.value.tabs.single()
        assertEquals("rem://terminal", tab.url)
        assertTrue(tab.isTerminal)
        assertEquals("Terminal", tab.displayTitle)
        assertNull("Drawn by the app, not the web engine", engine.sessions[terminal])
    }

    @Test
    fun aTerminal_opensBesideAWebPage_andFollowsTheHostPickedInIt() {
        viewModel.navigatePane(0, "https://example.com/")
        val page = viewModel.uiState.value.activeTabId!!
        val terminal = viewModel.openTerminal()
        assertNotEquals(page, terminal)
        assertEquals(terminal, viewModel.uiState.value.activeTabId)

        viewModel.showTerminalTarget(terminal, TerminalTarget.Address("root", "10.0.0.2", 2222))
        assertEquals("rem://terminal/ssh/root@10.0.0.2:2222", viewModel.uiState.value.activeTab!!.url)
        assertEquals("rem://terminal/ssh/root@10.0.0.2:2222", repository.storedState.tabs.first { it.id == terminal }.url)

        viewModel.setTerminalTitle(terminal, "root@box: ~")
        assertEquals("root@box: ~", viewModel.uiState.value.activeTab!!.displayTitle)
        // Only terminals take a terminal title
        viewModel.setTerminalTitle(page, "nope")
        assertNotEquals("nope", viewModel.uiState.value.tabs.first { it.id == page }.title)
    }

    @Test
    fun typingATerminalAddress_opensATerminal() {
        viewModel.navigatePane(0, "https://example.com/")
        viewModel.navigatePane(0, "rem://terminal/host/abc")
        val tab = viewModel.uiState.value.activeTab!!
        assertEquals("rem://terminal/host/abc", tab.url)
        assertEquals("The page stays in its own tab", 2, viewModel.uiState.value.tabs.size)
    }

    @Test
    fun popupClosingItself_closesAndTheOpenerComesBackInItsPane() {
        val openerId = viewModel.uiState.value.activeTabId!!
        engine.sessions.getValue(openerId).simulateNewTab("https://accounts.example/signin")
        val popupId = viewModel.uiState.value.activeTabId!!
        assertNotEquals(openerId, popupId)
        assertEquals("The popup shows in the opener's pane", popupId, viewModel.uiState.value.tabForPane(0)!!.id)

        engine.sessions.getValue(popupId).simulateCloseRequest()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.none { it.id == popupId })
        assertFalse(engine.sessions.containsKey(popupId))
        assertEquals(openerId, state.tabForPane(0)!!.id)
        assertEquals(openerId, state.activeTabId)
    }

    @Test
    fun popupInASplitPane_closesAndTheOpenerIsBackThere() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        val openerId = viewModel.uiState.value.paneTabIds[1]
        viewModel.focusPane(1)
        engine.sessions.getValue(openerId).simulateNewTab("https://accounts.example/signin")
        val popupId = viewModel.uiState.value.tabForPane(1)!!.id
        assertNotEquals(openerId, popupId)

        engine.sessions.getValue(popupId).simulateCloseRequest()

        val state = viewModel.uiState.value
        assertEquals(openerId, state.tabForPane(1)!!.id)
        assertEquals(1, state.activePaneIndex)
        assertEquals(openerId, state.activeTabId)
        assertTrue(state.tabs.none { it.id == popupId })
    }

    @Test
    fun closeRequestedByATheUserOpenedTab_isIgnored() {
        val userTab = viewModel.openNewTab("https://user.example/")
        assertFalse(viewModel.uiState.value.tabs.first { it.id == userTab }.openedByPage)

        engine.sessions.getValue(userTab).simulateCloseRequest()

        assertTrue(viewModel.uiState.value.tabs.any { it.id == userTab })
        assertTrue(engine.sessions.containsKey(userTab))
        assertEquals(userTab, viewModel.uiState.value.activeTabId)
    }

    @Test
    fun popupWhoseOpenerWasClosed_justCloses() {
        val firstId = viewModel.uiState.value.activeTabId!!
        val other = viewModel.openNewTab("https://other.example/")
        viewModel.selectTab(firstId)
        engine.sessions.getValue(firstId).simulateNewTab("https://popup.example/")
        val popupId = viewModel.uiState.value.activeTabId!!
        // The opener sits in the background now; close it
        viewModel.closeTab(firstId)
        assertTrue(viewModel.uiState.value.tabs.none { it.id == firstId })

        engine.sessions.getValue(popupId).simulateCloseRequest()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.none { it.id == popupId })
        assertTrue(state.tabs.any { it.id == other })
        assertTrue("A tab is still shown", state.activeTab != null)
    }

    /** The active tab opens a page tab (a target=_blank link); returns (opener id, page tab id). */
    private fun pageOpensATab(url: String = "https://popup.example/"): Pair<String, String> {
        val openerId = viewModel.uiState.value.activeTabId!!
        engine.sessions.getValue(openerId).simulateNewTab(url)
        return openerId to viewModel.uiState.value.activeTabId!!
    }

    @Test
    fun aTabAPageOpened_canNavigateBackToItsOpener_thoughItHasNoHistory() {
        val (openerId, popupId) = pageOpensATab()
        val state = viewModel.uiState.value
        val popup = state.tabs.first { it.id == popupId }

        assertEquals(openerId, popup.openerTabId)
        assertTrue(popup.openedByPage)
        assertFalse(popup.canGoBack)
        assertTrue(state.canNavigateBack(popup))
    }

    @Test
    fun goBack_inAPageOpenedTabWithNoHistory_closesItAndTheOpenerIsBackAndActive() {
        val (openerId, popupId) = pageOpensATab()

        viewModel.goBack()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.none { it.id == popupId })
        assertFalse(engine.sessions.containsKey(popupId))
        assertEquals(openerId, state.tabForPane(0)!!.id)
        assertEquals(openerId, state.activeTabId)
        assertEquals("The opener's own history was not touched", 0, engine.sessions.getValue(openerId).goBackCount)
    }

    @Test
    fun goBack_inASplitPane_returnsTheOpenerToThatPane() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        val openerId = viewModel.uiState.value.paneTabIds[1]
        viewModel.focusPane(1)
        engine.sessions.getValue(openerId).simulateNewTab("https://popup.example/")
        val popupId = viewModel.uiState.value.tabForPane(1)!!.id

        viewModel.goBack(1)

        val state = viewModel.uiState.value
        assertEquals(openerId, state.tabForPane(1)!!.id)
        assertEquals(1, state.activePaneIndex)
        assertTrue(state.tabs.none { it.id == popupId })
    }

    @Test
    fun goBack_whenTheOpenerIsShownInAnotherPane_focusesThatPane() {
        viewModel.setLayoutMode(LayoutMode.SPLIT_1X2)
        val openerId = viewModel.uiState.value.paneTabIds[0]
        val other = viewModel.uiState.value.paneTabIds[1]
        viewModel.focusPane(0)
        engine.sessions.getValue(openerId).simulateNewTab("https://popup.example/")
        // The popup took the opener's pane; show the opener again in pane 1, then go back from pane 0
        val popupId = viewModel.uiState.value.tabForPane(0)!!.id
        viewModel.assignTabToPane(1, openerId)

        viewModel.goBack(0)

        val state = viewModel.uiState.value
        assertTrue(state.tabs.none { it.id == popupId })
        assertEquals("Focus follows the opener", state.paneIndexOf(openerId), state.activePaneIndex)
        assertTrue(state.tabs.any { it.id == other })
    }

    @Test
    fun whenTheOpenerWasClosed_canNavigateBackIsFalse_andGoBackJustCallsTheSession() {
        val other = viewModel.openNewTab("https://other.example/")
        val firstId = viewModel.uiState.value.tabs.first { it.id != other }.id
        viewModel.selectTab(firstId)
        val (openerId, popupId) = pageOpensATab()
        viewModel.closeTab(openerId)

        val state = viewModel.uiState.value
        assertTrue(state.tabs.any { it.id == popupId })
        assertFalse(state.canNavigateBack(state.tabs.first { it.id == popupId }))

        viewModel.goBack()

        assertTrue("Not closed", viewModel.uiState.value.tabs.any { it.id == popupId })
        assertEquals(1, engine.sessions.getValue(popupId).goBackCount)
    }

    @Test
    fun goBack_withHistory_callsTheSessionAndDoesNotCloseThePageOpenedTab() {
        val (_, popupId) = pageOpensATab()
        engine.sessions.getValue(popupId).simulatePageLoad("https://popup.example/next", "Next", canGoBack = true)

        assertTrue(viewModel.uiState.value.canNavigateBack(viewModel.uiState.value.activeTab))
        viewModel.goBack()

        assertTrue(viewModel.uiState.value.tabs.any { it.id == popupId })
        assertEquals(popupId, viewModel.uiState.value.activeTabId)
        assertEquals(1, engine.sessions.getValue(popupId).goBackCount)
    }

    @Test
    fun aTabTheUserOpened_neverClosesOnGoBack() {
        val userTab = viewModel.openNewTab("https://user.example/")
        val tab = viewModel.uiState.value.tabs.first { it.id == userTab }
        assertNull(tab.openerTabId)
        assertFalse(viewModel.uiState.value.canNavigateBack(tab))

        viewModel.goBack()

        assertTrue(viewModel.uiState.value.tabs.any { it.id == userTab })
        assertEquals(1, engine.sessions.getValue(userTab).goBackCount)
    }

    @Test
    fun canNavigateBack_isFalseWithoutATab() {
        assertFalse(viewModel.uiState.value.canNavigateBack(null))
    }

    // --- Print preview ---

    private fun pdfFile(name: String = "page.pdf", content: String = "%PDF-1.7 laid out"): java.io.File =
        tempFolder.newFile(name).also { it.writeText(content) }

    @Test
    fun printReady_showsAPreviewForAPageOnScreen_andClosesTheFindBarAndPageMenu() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)
        session.simulateContextMenu(linkUrl = "https://example.com/a")
        viewModel.openFindInPage()
        session.simulateContextMenu()
        assertTrue(viewModel.pageMenu.value != null)
        assertTrue(viewModel.findInPage.value != null)
        val pdf = pdfFile()

        session.simulatePrintReady(pdf.path, "Example")

        val preview = viewModel.printPreview.value!!
        assertEquals(tabId, preview.tabId)
        assertEquals(pdf.path, preview.pdfPath)
        assertEquals("Example", preview.documentName)
        assertEquals("https://example.com/", preview.pageUrl)
        assertNull(viewModel.pageMenu.value)
        assertNull(viewModel.findInPage.value)
        assertTrue(pdf.exists())
    }

    @Test
    fun printReady_deletesTheFileForATabInTheBackgroundOrUnknown() {
        val shown = pageOnScreen()
        val background = viewModel.openTabInBackground("https://other.example/", shown)
        val bgPdf = pdfFile("bg.pdf")
        val missingPdf = pdfFile("unknown.pdf")

        engine.sessions.getValue(background).simulatePrintReady(bgPdf.path)
        assertNull(viewModel.printPreview.value)
        assertFalse(bgPdf.exists())

        // A tab the view model does not know
        engine.sessions.getValue(shown).simulatePrintReady(missingPdf.path, forTabId = "no-such-tab")
        assertNull(viewModel.printPreview.value)
        assertFalse(missingPdf.exists())
    }

    @Test
    fun printReady_deletesTheFileForATabUnderAnotherTabsFullscreen() {
        val (reddit, youtube, _) = redditBesideYouTube()
        engine.sessions.getValue(reddit).simulatePageLoad("https://reddit.com/", "Reddit")
        engine.sessions.getValue(youtube).simulatePageLoad("https://youtube.com/", "YouTube")
        engine.sessions.getValue(reddit).simulateFullScreen(true)
        val underFullscreen = pdfFile("under.pdf")

        engine.sessions.getValue(youtube).simulatePrintReady(underFullscreen.path)

        assertNull(viewModel.printPreview.value)
        assertFalse(underFullscreen.exists())

        val own = pdfFile("own.pdf")
        engine.sessions.getValue(reddit).simulatePrintReady(own.path)
        assertEquals(reddit, viewModel.printPreview.value?.tabId)
        assertTrue(own.exists())
    }

    @Test
    fun printReady_aNewPreviewReplacesTheOldOneAndDeletesItsFile() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)
        val first = pdfFile("first.pdf")
        val second = pdfFile("second.pdf")

        session.simulatePrintReady(first.path)
        val firstSerial = viewModel.printPreview.value!!.serial
        session.simulatePrintReady(second.path)

        val preview = viewModel.printPreview.value!!
        assertEquals(second.path, preview.pdfPath)
        assertTrue(preview.serial > firstSerial)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun dismissPrintPreview_clearsItAndDeletesTheFile() {
        val tabId = pageOnScreen()
        val pdf = pdfFile()
        engine.sessions.getValue(tabId).simulatePrintReady(pdf.path)

        viewModel.dismissPrintPreview()

        assertNull(viewModel.printPreview.value)
        assertFalse(pdf.exists())
    }

    @Test
    fun savePrintPreviewAsPdf_handsTheFileOverAsADownloadAndCleansUp() {
        val tabId = pageOnScreen()
        val content = "%PDF-1.7 laid out \u00e9 bytes"
        val pdf = pdfFile(content = content)
        val expected = pdf.readBytes()
        engine.sessions.getValue(tabId).simulatePrintReady(pdf.path)
        var request: DownloadRequest? = null
        var bytes: ByteArray? = null
        viewModel.downloadHandler = {
            request = it
            bytes = it.body.readBytes()
        }

        assertTrue(viewModel.savePrintPreviewAsPdf("My page"))

        val sent = request!!
        assertNull(sent.tabId)
        assertEquals("application/pdf", sent.mimeType)
        assertTrue(sent.skipConfirmation)
        assertEquals("My page.pdf", sent.fileName)
        assertEquals("https://example.com/", sent.url)
        assertEquals(expected.size.toLong(), sent.contentLength)
        assertTrue(expected.contentEquals(bytes!!))
        assertNull(viewModel.printPreview.value)
        assertFalse(pdf.exists())
    }

    @Test
    fun savePrintPreviewAsPdf_failsAndKeepsThePreviewWithoutAHandlerOrWithoutTheFile() {
        val tabId = pageOnScreen()
        val pdf = pdfFile()
        engine.sessions.getValue(tabId).simulatePrintReady(pdf.path)

        viewModel.downloadHandler = null
        assertFalse(viewModel.savePrintPreviewAsPdf("x"))
        assertTrue(viewModel.printPreview.value != null)
        assertTrue(pdf.exists())

        var calls = 0
        viewModel.downloadHandler = { calls++ }
        pdf.delete()
        assertFalse(viewModel.savePrintPreviewAsPdf("x"))
        assertTrue(viewModel.printPreview.value != null)
        assertEquals(0, calls)
    }

    @Test
    fun takePrintPreviewForPrinter_clearsItButLeavesTheFile() {
        val tabId = pageOnScreen()
        val pdf = pdfFile()
        engine.sessions.getValue(tabId).simulatePrintReady(pdf.path)

        val taken = viewModel.takePrintPreviewForPrinter()

        assertEquals(pdf.path, taken?.pdfPath)
        assertNull(viewModel.printPreview.value)
        assertTrue(pdf.exists())
        assertNull(viewModel.takePrintPreviewForPrinter())
    }

    @Test
    fun closeTab_keepsThePrintPreview_aReceiptPopupClosesItselfOnceItHasPrinted() {
        pageOnScreen()
        val popup = viewModel.openNewTab("https://shop.example/receipt")
        engine.sessions.getValue(popup).simulatePageLoad("https://shop.example/receipt", "Receipt")
        val pdf = pdfFile()
        // window.print(); window.close(): the page is let go once the PDF is made, and closes
        engine.sessions.getValue(popup).simulatePrintReady(pdf.path, requestedByPage = true)
        viewModel.closeTab(popup)

        assertEquals(popup, viewModel.printPreview.value?.tabId)
        assertTrue(pdf.exists())
    }

    @Test
    fun pagePrint_whileThePrintScreenIsOpen_isIgnoredAndItsFileDeleted() {
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)
        val shown = pdfFile("shown.pdf")
        val fromPage = pdfFile("page.pdf")
        session.simulatePrintReady(shown.path)

        session.simulatePrintReady(fromPage.path, requestedByPage = true)

        assertEquals(shown.path, viewModel.printPreview.value?.pdfPath)
        assertTrue(shown.exists())
        assertFalse(fromPage.exists())
    }

    @Test
    fun pagePrint_rightAfterThePrintScreenCloses_isIgnored_butTheUsersOwnPrintIsNot() {
        var now = 1_000_000L
        engine = FakeBrowserEngine()
        repository = FakeBrowserStateRepository()
        viewModel = BrowserViewModel(
            engine = engine,
            repository = repository,
            idGenerator = { "tab-${++idCounter}" },
            clock = { now }
        )
        val tabId = pageOnScreen()
        val session = engine.sessions.getValue(tabId)
        session.simulatePrintReady(pdfFile("first.pdf").path)
        viewModel.dismissPrintPreview()

        // A page printing in a loop cannot reopen the screen at once
        now += 1_000
        val looped = pdfFile("looped.pdf")
        session.simulatePrintReady(looped.path, requestedByPage = true)
        assertNull(viewModel.printPreview.value)
        assertFalse(looped.exists())

        // The user's own Print command is never held back
        val own = pdfFile("own.pdf")
        session.simulatePrintReady(own.path)
        assertEquals(own.path, viewModel.printPreview.value?.pdfPath)
        viewModel.dismissPrintPreview()

        // A little later the page may print again
        now += 5_000
        val later = pdfFile("later.pdf")
        session.simulatePrintReady(later.path, requestedByPage = true)
        assertEquals(later.path, viewModel.printPreview.value?.pdfPath)
    }

    @Test
    fun savePrintPreviewAsPdf_keepsTheTypedNameInsideDownloads() {
        val tabId = pageOnScreen()
        engine.sessions.getValue(tabId).simulatePrintReady(pdfFile().path)
        var request: DownloadRequest? = null
        viewModel.downloadHandler = { request = it }

        assertTrue(viewModel.savePrintPreviewAsPdf("../../etc/My: page"))

        val name = request!!.fileName
        assertFalse(name, name.contains('/') || name.contains(':'))
        assertTrue(name, name.endsWith(".pdf"))
    }

    @Test
    fun shutdown_dismissesThePrintPreviewAndDeletesItsFile() {
        val tabId = pageOnScreen()
        val pdf = pdfFile()
        engine.sessions.getValue(tabId).simulatePrintReady(pdf.path)

        viewModel.shutdown()

        assertNull(viewModel.printPreview.value)
        assertFalse(pdf.exists())
    }
}
