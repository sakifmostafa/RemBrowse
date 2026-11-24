package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The active tab's tile stays in view on the rail: when the window leaves it out of sight (a phone
 * held sideways, where the pinned sites can fill the rail), and when another tab becomes the
 * active one. A list scrolled by hand is left where it was put.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ActiveTabInViewUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private lateinit var browserVm: BrowserViewModel

    /** The height the screen is given, or the whole window when null. */
    private val screenHeight = mutableStateOf<Dp?>(null)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(state: PersistedBrowserState) {
        browserVm = BrowserViewModel(engine, FakeBrowserStateRepository(state))
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
        val downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(emptyList()),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    val height = screenHeight.value
                    Box(modifier = if (height != null) Modifier.height(height) else Modifier) {
                        RemBrowseScreen(
                            browserViewModel = browserVm,
                            extensionsViewModel = extVm,
                            downloadsViewModel = downloadsVm,
                            versionName = "test",
                            onPickLocalXpi = {},
                            paneViewport = { tab, modifier -> Box(modifier) { Text("Viewport:${tab.url}") } },
                            extensionPopupContent = { Box(it) }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** [count] tabs, the first in the single pane and active. */
    private fun manyTabs(count: Int): PersistedBrowserState {
        val tabs = (1..count).map { BrowserTab(id = "t$it", url = "https://site$it.example/", title = "Site $it") }
        return PersistedBrowserState(tabs = tabs, paneTabIds = listOf(tabs.first().id))
    }

    /** Whether all of the tile of [tabId] is inside the rail's list. */
    private fun inView(tabId: String): Boolean {
        if (composeRule.onAllNodesWithTag("tab_bar_item_$tabId").fetchSemanticsNodes().isEmpty()) return false
        val list = composeRule.onNodeWithTag("tab_rail_list").getUnclippedBoundsInRoot()
        val tile = composeRule.onNodeWithTag("tab_bar_item_$tabId").getUnclippedBoundsInRoot()
        // A tile kept aside by the list, not on screen, has no bounds
        if (!tile.top.isSpecified || !tile.bottom.isSpecified) return false
        val slack = 0.5.dp
        return tile.top >= list.top - slack && tile.bottom <= list.bottom + slack
    }

    // The scene of TabRailSnapshotTest's rail-05: five pinned sites over the open tabs
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun phoneHeldSideways_withFivePinnedSites_showsTheActiveTab() {
        val tabs = listOf(
            BrowserTab(id = "github", url = "https://github.com/rembrowse/rembrowse", title = "GitHub"),
            BrowserTab(id = "news", url = "https://news.example.com/", title = "The Example Times"),
            BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Telegram"),
            BrowserTab(id = "new")
        )
        val bookmarks = listOf(
            "https://web.telegram.org/a/", "https://github.com/", "https://mail.google.com/",
            "https://www.youtube.com/", "https://calendar.google.com/"
        ).mapIndexed { index, url -> BookmarkItem(id = "b$index", title = "Site $index", url = url, createdAtMillis = 0L) }
        launch(
            PersistedBrowserState(
                layoutMode = LayoutMode.SPLIT_1X2,
                tabs = tabs,
                paneTabIds = listOf("github", "news"),
                bookmarks = bookmarks
            )
        )

        assertTrue("The active tab is in view below the shelf", inView("github"))

        // Another tab chosen elsewhere (Ctrl+Tab, the switcher) comes into view as well
        composeRule.runOnUiThread { browserVm.selectTab("new") }
        composeRule.waitForIdle()
        assertTrue(inView("new"))
    }

    @Test
    fun choosingATabFurtherDown_bringsItIntoView() {
        launch(manyTabs(30))
        assertTrue(inView("t1"))
        assertFalse(inView("t30"))

        composeRule.runOnUiThread { browserVm.selectTab("t30") }
        composeRule.waitForIdle()
        assertTrue(inView("t30"))

        // And back up
        composeRule.runOnUiThread { browserVm.selectTab("t2") }
        composeRule.waitForIdle()
        assertTrue(inView("t2"))
    }

    @Test
    fun aNewTab_atTheEndOfALongList_isInView() {
        launch(manyTabs(30))

        composeRule.onNodeWithTag("new_tab_button").performClick()
        composeRule.waitForIdle()

        val newTab = browserVm.uiState.value.activeTabId!!
        assertEquals(newTab, browserVm.uiState.value.tabs.last().id)
        assertTrue(inView(newTab))
    }

    @Test
    fun aListScrolledByHand_staysWhereItWasPut() {
        launch(manyTabs(30))

        composeRule.onNodeWithTag("tab_rail_list").performScrollToIndex(29)
        composeRule.waitForIdle()

        assertFalse("The active tab is left out of sight", inView("t1"))
        assertTrue(inView("t30"))
    }

    // A phone held sideways, then the keyboard up: the window grows shorter under the active tab
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun theWindowGrowingShorter_keepsTheActiveTabInView() {
        launch(manyTabs(6).let { it.copy(paneTabIds = listOf("t5")) })
        assertTrue(inView("t5"))

        screenHeight.value = 250.dp
        composeRule.waitForIdle()

        assertTrue(inView("t5"))
    }
}
