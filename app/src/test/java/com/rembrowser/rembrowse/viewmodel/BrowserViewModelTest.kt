package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.SplitRatios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        viewModel = BrowserViewModel(
            engine = engine,
            repository = repository,
            idGenerator = { "tab-${++idCounter}" }
        )
    }

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
        // Only the visible 1x1 tab is marked visible; the other 3 wait in the tab bar
        assertTrue(engine.sessions.getValue(focusedTabId).visibleState)
        assertEquals(1, engine.sessions.values.count { it.visibleState })
    }

    @Test
    fun focusPane_in2x2ModeKeepsAllVisiblePanesActiveSoNoneGoDark() {
        viewModel.setLayoutMode(LayoutMode.GRID_2X2)
        // Open a 5th background tab to verify non-pane tabs are inactive
        val pane0Id = viewModel.uiState.value.paneTabIds[0]
        viewModel.openNewTab("https://example.org", assignToPane = 0)
        // Now pane0Id is in the tab bar (hidden), while 4 panes are visible
        val visibleIds = viewModel.uiState.value.paneTabIds.toSet()

        viewModel.focusPane(1)

        visibleIds.forEach { id ->
            assertTrue("Visible pane tab $id must remain visible", engine.sessions.getValue(id).visibleState)
        }
        assertFalse("Hidden tab bar tab should not be marked visible", engine.sessions.getValue(pane0Id).visibleState)
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

        // Collapse to 1x1 and pick another tab from the tab bar
        viewModel.setLayoutMode(LayoutMode.SINGLE_1X1)
        val tab1Id = viewModel.uiState.value.tabs.first().id
        viewModel.selectTab(tab1Id)
        assertEquals(tab1Id, viewModel.uiState.value.activeTabId)
        assertTrue(engine.sessions.getValue(tab1Id).visibleState)
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
    fun closeTab_closesLastTabReplacesWithFreshHomeTab() {
        val originalId = viewModel.uiState.value.tabs.first().id
        viewModel.closeTab(originalId)

        val state = viewModel.uiState.value
        assertEquals(1, state.tabs.size)
        assertNotEquals(originalId, state.tabs.first().id)
        assertFalse(engine.sessions.containsKey(originalId))
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
}
