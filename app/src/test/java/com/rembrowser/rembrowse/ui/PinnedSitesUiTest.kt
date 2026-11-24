package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The shelf of pinned sites at the top of the tab rail, on a laptop-sized window. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class PinnedSitesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private lateinit var browserVm: BrowserViewModel

    private val telegram = BookmarkItem(id = "tg", title = "Telegram", url = "https://web.telegram.org/a/", createdAtMillis = 0L)
    private val gmail = BookmarkItem(id = "mail", title = "Gmail", url = "https://mail.google.com/", createdAtMillis = 0L)
    private val repo = BookmarkItem(id = "repo", title = "Repo", url = "https://github.com/org/repo", createdAtMillis = 0L)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    /** Opens the browser on [tabs], the first in the single pane, with [bookmarks] pinned. */
    private fun launch(tabs: List<BrowserTab>, bookmarks: List<BookmarkItem> = listOf(telegram, gmail)) {
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
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> Box(modifier) { Text("Viewport:${tab.url}") } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    private fun openDot(bookmark: BookmarkItem) =
        composeRule.onNodeWithTag("pinned_open_dot_${bookmark.id}", useUnmergedTree = true)

    @Test
    fun pinnedSite_showsTheTabAlreadyOpenOnItsSiteInsteadOfOpeningAnother() {
        launch(
            listOf(
                BrowserTab(id = "news", url = "https://news.example/", title = "News"),
                BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Telegram")
            )
        )

        composeRule.onNodeWithTag("pinned_site_tg").performClick()

        assertEquals("chat", browserVm.uiState.value.activeTabId)
        assertEquals(2, browserVm.uiState.value.tabs.size)
        composeRule.onNodeWithText("Viewport:https://web.telegram.org/a/#-1001234567").assertIsDisplayed()
    }

    @Test
    fun pinnedSite_withNoTabOnItsSite_opensInANewTabBesideAPage() {
        launch(listOf(BrowserTab(id = "news", url = "https://news.example/", title = "News")))

        composeRule.onNodeWithTag("pinned_site_mail").performClick()

        val state = browserVm.uiState.value
        assertEquals(2, state.tabs.size)
        assertEquals("https://mail.google.com/", state.activeTab!!.url)
    }

    @Test
    fun pinnedSite_menuOffersOpenInANewTabEvenWhenTheSiteIsOpen() {
        launch(
            listOf(
                BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Telegram"),
                BrowserTab(id = "news", url = "https://news.example/", title = "News")
            )
        )

        composeRule.onNodeWithTag("pinned_site_tg").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithText("Remove bookmark").assertIsDisplayed()
        composeRule.onNodeWithText("Open in new tab").performClick()

        val state = browserVm.uiState.value
        assertEquals(3, state.tabs.size)
        assertEquals("https://web.telegram.org/a/", state.activeTab!!.url)
        assertTrue(state.activeTabId != "chat")
    }

    @Test
    fun pinnedSite_menuOpenShowsTheOpenTab() {
        launch(
            listOf(
                BrowserTab(id = "news", url = "https://news.example/", title = "News"),
                BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Telegram")
            )
        )

        composeRule.onNodeWithTag("pinned_site_tg").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Open").performClick()

        assertEquals("chat", browserVm.uiState.value.activeTabId)
        assertEquals(2, browserVm.uiState.value.tabs.size)
    }

    @Test
    fun openDot_appearsWhileATabOnTheSiteIsOpenAndTheNameSaysSo() {
        launch(listOf(BrowserTab(id = "news", url = "https://news.example/", title = "News")))

        openDot(telegram).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Telegram, pinned").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Gmail, pinned").assertIsDisplayed()

        // Another page of the same origin counts as the site being open
        composeRule.runOnUiThread { browserVm.openNewTab("https://web.telegram.org/a/#-1001234567") }
        val chat = browserVm.uiState.value.activeTabId!!
        openDot(telegram).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Telegram, pinned, open").assertIsDisplayed()
        openDot(gmail).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Gmail, pinned").assertIsDisplayed()

        composeRule.runOnUiThread { browserVm.closeTab(chat) }
        openDot(telegram).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Telegram, pinned").assertIsDisplayed()
    }

    @Test
    fun openDot_needsTheSameOrigin() {
        launch(
            listOf(
                // Another scheme, another port, another host of the same domain: other sites
                BrowserTab(id = "plain", url = "http://web.telegram.org/a/", title = "Plain"),
                BrowserTab(id = "port", url = "https://web.telegram.org:8443/a/", title = "Port"),
                BrowserTab(id = "host", url = "https://telegram.org/", title = "Host")
            )
        )

        openDot(telegram).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Telegram, pinned").assertIsDisplayed()
    }

    @Test
    fun shelf_holdsThePinsAboveTheTabsWithRoomBetween() {
        launch(listOf(BrowserTab(id = "news", url = "https://news.example/", title = "News")))

        val rail = composeRule.onNodeWithTag("tab_rail").getUnclippedBoundsInRoot()
        val shelf = composeRule.onNodeWithTag("pinned_shelf").assertIsDisplayed().getUnclippedBoundsInRoot()
        val firstTab = composeRule.onNodeWithTag("tab_bar_item_news").getUnclippedBoundsInRoot()
        val pin = composeRule.onNodeWithTag("pinned_site_tg").fetchSemanticsNode()

        // Inset within the rail, one 48dp touch target per site
        assertEquals(48.dp, shelf.width)
        assertEquals(4.dp, shelf.left - rail.left)
        assertEquals(48.dp * 2, shelf.height)
        assertTrue(pin.touchBoundsInRoot.height >= 44f && pin.touchBoundsInRoot.width >= 44f)
        // The first tab starts a group of its own
        assertTrue(firstTab.top - shelf.bottom >= 12.dp)
    }

    @Test
    fun shelf_isNotShownWithoutPins() {
        launch(listOf(BrowserTab(id = "news", url = "https://news.example/", title = "News")), bookmarks = emptyList())

        composeRule.onNodeWithTag("pinned_shelf").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_bar_item_news").assertIsDisplayed()
    }

    @Test
    fun bookmarksDialog_showsTheTabAtTheBookmarkedPageAndNotAnotherPageOfTheSite() {
        launch(
            listOf(
                BrowserTab(id = "news", url = "https://news.example/", title = "News"),
                BrowserTab(id = "other", url = "https://github.com/other", title = "Other"),
                BrowserTab(id = "issues", url = "https://github.com/org/repo/issues/12", title = "Issue 12")
            ),
            bookmarks = listOf(repo)
        )

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_bookmarks").performClick()
        composeRule.onNodeWithText("Repo").performClick()

        composeRule.onNodeWithTag("bookmarks_dialog").assertDoesNotExist()
        assertEquals("issues", browserVm.uiState.value.activeTabId)
        assertEquals(3, browserVm.uiState.value.tabs.size)

        // With only another page of the site open, the bookmarked page opens
        composeRule.runOnUiThread { browserVm.closeTab("issues") }
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_bookmarks").performClick()
        composeRule.onNodeWithText("Repo").performClick()

        val state = browserVm.uiState.value
        assertEquals(3, state.tabs.size)
        assertEquals("https://github.com/org/repo", state.activeTab!!.url)
    }

    @Test
    fun pinnedSite_onTheActiveTabElsewhereBelowIt_goesBackToThePinnedPage() {
        launch(listOf(BrowserTab(id = "pulls", url = "https://github.com/org/repo/pulls", title = "Pulls")), bookmarks = listOf(repo))

        composeRule.onNodeWithTag("pinned_site_repo").performClick()

        assertEquals("pulls", browserVm.uiState.value.activeTabId)
        assertEquals("https://github.com/org/repo", engine.sessions.getValue("pulls").loadedUrls.last())
        composeRule.onNodeWithText("Viewport:https://github.com/org/repo").assertIsDisplayed()

        // Clicked again on the pinned page itself, nothing happens
        val loads = engine.sessions.getValue("pulls").loadedUrls.size
        composeRule.onNodeWithTag("pinned_site_repo").performClick()
        assertEquals(loads, engine.sessions.getValue("pulls").loadedUrls.size)
        assertEquals(1, browserVm.uiState.value.tabs.size)
    }

    @Test
    fun openDot_clearsWhenTheTabLeavesTheBookmarkedPath() {
        launch(listOf(BrowserTab(id = "issues", url = "https://github.com/org/repo/issues/12", title = "Issue 12")), bookmarks = listOf(repo))
        openDot(repo).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Repo, pinned, open").assertIsDisplayed()

        composeRule.runOnUiThread {
            engine.sessions.getValue("issues").simulatePageLoad("https://github.com/org/repository", "Another repo")
        }

        openDot(repo).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Repo, pinned").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pinnedSite_tooltipShowsTheNameOnly() {
        launch(listOf(BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Chat")))

        composeRule.onNodeWithTag("pinned_site_tg").performMouseInput { moveTo(center) }
        // Inside the 1.5s a plain tooltip stays up
        composeRule.mainClock.advanceTimeBy(500)

        // The tooltip's text is composed only while it is up
        composeRule.onNodeWithText("Telegram").assertExists()
        composeRule.onNodeWithText("Telegram, pinned, open").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Telegram, pinned, open").assertIsDisplayed()
    }
}
