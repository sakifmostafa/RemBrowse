package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SettingsSection
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
 * A mouse's middle button and right-click on the tabs, the tab switcher's cards, pinned sites,
 * bookmarks and history, with a long press doing the right-click's work on a touch screen. A
 * plain click still does what it did.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class MouseAffordancesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private lateinit var browserVm: BrowserViewModel
    private val chrome = BrowserChromeState()

    private val gmail = BookmarkItem(id = "mail", title = "Gmail", url = "https://mail.google.com/", createdAtMillis = 0L)
    private val news = BrowserTab(id = "news", url = "https://news.example/", title = "News")
    private val chat = BrowserTab(id = "chat", url = "https://chat.example/", title = "Chat")
    private val docs = BrowserTab(id = "docs", url = "https://docs.example/", title = "Docs")

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(tabs: List<BrowserTab> = listOf(news, chat, docs), bookmarks: List<BookmarkItem> = listOf(gmail)) {
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(tabs = tabs, paneTabIds = listOf(tabs.first().id), bookmarks = bookmarks)
            )
        )
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
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> Box(modifier) { Text("Viewport:${tab.url}") } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.click(button: MouseButton) {
        performMouseInput {
            moveTo(center)
            press(button)
            release(button)
        }
        composeRule.waitForIdle()
    }

    private val tabIds get() = browserVm.uiState.value.tabs.map { it.id }

    // --- the tab rail ---

    @Test
    fun railTab_middleClickClosesIt_andAPlainClickStillSelectsIt() {
        launch()
        composeRule.onNodeWithTag("tab_bar_item_chat").click(MouseButton.Primary)
        assertEquals("chat", browserVm.uiState.value.activeTabId)
        assertEquals(listOf("news", "chat", "docs"), tabIds)

        composeRule.onNodeWithTag("tab_bar_item_docs").click(MouseButton.Tertiary)
        assertEquals(listOf("news", "chat"), tabIds)
        assertEquals("Closing another tab leaves the focused one", "chat", browserVm.uiState.value.activeTabId)
        composeRule.onNodeWithTag("tab_bar_item_docs").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_bar_close_chat").assertDoesNotExist()
    }

    @Test
    fun railTab_rightClickStillOpensItsMenu() {
        launch()
        composeRule.onNodeWithTag("tab_bar_item_docs").click(MouseButton.Secondary)
        composeRule.onNodeWithTag("tab_bar_close_docs").assertIsDisplayed()
        assertEquals(3, tabIds.size)
    }

    @Test
    fun pinnedSite_middleClickOpensItInANewTab() {
        launch()
        composeRule.onNodeWithTag("pinned_site_mail").click(MouseButton.Tertiary)

        val state = browserVm.uiState.value
        assertEquals(4, state.tabs.size)
        assertEquals("https://mail.google.com/", state.activeTab!!.url)
    }

    // --- the tab switcher ---

    private fun openSwitcher() {
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
    }

    @Test
    fun switcherCard_rightClickOpensItsMenu_whichClosesTheTabOrTheOthers() {
        launch()
        openSwitcher()
        composeRule.onNodeWithTag("switcher_tab_chat").click(MouseButton.Secondary)
        composeRule.onNodeWithTag("switcher_menu_close_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("switcher_menu_close_others_chat").performClick()

        // As on the rail: the background tabs close, the one on screen (news) stays
        assertEquals(listOf("news", "chat"), tabIds)
        // The switcher stays open on what is left
        composeRule.onNodeWithTag("switcher_tab_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("switcher_tab_docs").assertDoesNotExist()
    }

    @Test
    fun switcherCard_longPressOpensTheSameMenu_forTouch() {
        launch()
        openSwitcher()
        composeRule.onNodeWithTag("switcher_tab_docs").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("switcher_menu_close_docs").performClick()

        assertEquals(listOf("news", "chat"), tabIds)
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()
    }

    @Test
    fun switcherCard_middleClickClosesIt_andAPlainClickStillShowsIt() {
        launch()
        openSwitcher()
        composeRule.onNodeWithTag("switcher_tab_news").click(MouseButton.Tertiary)
        assertEquals(listOf("chat", "docs"), tabIds)
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()

        composeRule.onNodeWithTag("switcher_tab_docs").click(MouseButton.Primary)
        assertEquals("docs", browserVm.uiState.value.activeTabId)
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
    }

    // --- bookmarks ---

    private fun openBookmarks() {
        composeRule.runOnUiThread { chrome.showBookmarks = true }
        composeRule.waitForIdle()
    }

    @Test
    fun bookmark_menuOpensItInANewTab_evenWhenItsSiteIsOpen() {
        val mailTab = BrowserTab(id = "inbox", url = "https://mail.google.com/", title = "Inbox")
        launch(tabs = listOf(news, mailTab))
        openBookmarks()
        composeRule.onNodeWithTag("bookmark_item_mail").click(MouseButton.Secondary)
        composeRule.onNodeWithTag("bookmark_menu_open_mail").assertIsDisplayed()
        composeRule.onNodeWithTag("bookmark_menu_new_tab_mail").performClick()

        val state = browserVm.uiState.value
        assertEquals(3, state.tabs.size)
        assertEquals("https://mail.google.com/", state.activeTab!!.url)
        assertFalse("A new tab, not the open one", state.activeTabId == "inbox")
        composeRule.onNodeWithTag("bookmarks_dialog").assertDoesNotExist()
    }

    @Test
    fun bookmark_middleClickOpensANewTab_andLongPressOffersRemove() {
        val mailTab = BrowserTab(id = "inbox", url = "https://mail.google.com/", title = "Inbox")
        launch(tabs = listOf(news, mailTab))
        openBookmarks()
        composeRule.onNodeWithTag("bookmark_item_mail").click(MouseButton.Tertiary)
        assertEquals(3, browserVm.uiState.value.tabs.size)

        openBookmarks()
        composeRule.onNodeWithTag("bookmark_item_mail").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("bookmark_menu_remove_mail").performClick()
        assertTrue(browserVm.uiState.value.bookmarks.isEmpty())
        composeRule.onNodeWithText("No bookmarks yet").assertIsDisplayed()
    }

    @Test
    fun bookmark_plainClickStillOpensItsOpenTab() {
        val mailTab = BrowserTab(id = "inbox", url = "https://mail.google.com/", title = "Inbox")
        launch(tabs = listOf(news, mailTab))
        openBookmarks()
        composeRule.onNodeWithTag("bookmark_item_mail").click(MouseButton.Primary)
        assertEquals("inbox", browserVm.uiState.value.activeTabId)
        assertEquals(2, browserVm.uiState.value.tabs.size)
    }

    // --- history ---

    @Test
    fun historyEntry_rightClickOffersOpenAndRemove() {
        launch()
        composeRule.runOnUiThread {
            engine.sessions.getValue("news").simulatePageLoad("https://news.example/", "Morning news", canGoBack = false)
            browserVm.openSettings(SettingsSection.HISTORY)
        }
        composeRule.waitForIdle()
        val row = composeRule.onNodeWithTag("history_entry_https://news.example/")
        row.click(MouseButton.Secondary)
        composeRule.onNodeWithTag("history_menu_open_https://news.example/").assertIsDisplayed()
        composeRule.onNodeWithTag("history_menu_remove_https://news.example/").performClick()

        assertTrue(browserVm.history.value.none { it.url == "https://news.example/" })
        composeRule.onNodeWithTag("history_entry_https://news.example/").assertDoesNotExist()
    }

    @Test
    fun historyEntry_middleClickOpensIt() {
        launch()
        composeRule.runOnUiThread {
            engine.sessions.getValue("news").simulatePageLoad("https://news.example/", "Morning news", canGoBack = false)
            browserVm.openSettings(SettingsSection.HISTORY)
        }
        composeRule.waitForIdle()
        val before = tabIds.size
        composeRule.onNodeWithTag("history_entry_https://news.example/").click(MouseButton.Tertiary)

        assertEquals(before + 1, tabIds.size)
        assertEquals("https://news.example/", browserVm.uiState.value.activeTab!!.url)
    }

    @Test
    fun switcherCard_heldWithTheMouse_isStillAClick_notTheMenu() {
        launch()
        openSwitcher()
        composeRule.onNodeWithTag("switcher_tab_docs").performMouseInput {
            moveTo(center)
            press(MouseButton.Primary)
        }
        // Well past the long-press time
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithTag("switcher_menu_close_docs").assertDoesNotExist()
        composeRule.onNodeWithTag("switcher_tab_docs").performMouseInput { release(MouseButton.Primary) }
        composeRule.waitForIdle()

        assertEquals("docs", browserVm.uiState.value.activeTabId)
        assertEquals(3, tabIds.size)
    }

    @Test
    fun switcherCard_heldWithAFinger_opensTheMenu_andTheLiftDoesNotSelectIt() {
        launch()
        openSwitcher()
        composeRule.onNodeWithTag("switcher_tab_docs").performTouchInput { longClick(center) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("switcher_menu_close_docs").assertIsDisplayed()
        assertEquals("news", browserVm.uiState.value.activeTabId)
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()
    }

    @Test
    fun historyEntry_heldWithAFinger_opensTheMenu_butAQuickTapOpensThePage() {
        launch()
        composeRule.runOnUiThread {
            engine.sessions.getValue("news").simulatePageLoad("https://news.example/", "Morning news", canGoBack = false)
            browserVm.openSettings(SettingsSection.HISTORY)
        }
        composeRule.waitForIdle()
        val row = composeRule.onNodeWithTag("history_entry_https://news.example/")
        val before = tabIds.size
        row.performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_menu_remove_https://news.example/").assertIsDisplayed()
        assertEquals("Only the menu", before, tabIds.size)

        composeRule.onNodeWithTag("history_menu_open_https://news.example/").performClick()
        assertEquals(before + 1, tabIds.size)
    }
}
