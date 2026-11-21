package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.FindResult
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The find bar on a laptop-sized window: opening it, searching, moving through matches, closing it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class FindInPageUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launchOnAWebPage(): String {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/", "Example")
        }
        composeRule.waitForIdle()
        return tab
    }

    @Test
    fun findInPage_searchesMovesThroughMatchesAndCloses() {
        val tab = launchOnAWebPage()
        val page = engine.sessions.getValue(tab)

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_find_in_page").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("find_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("find_input").assertIsFocused()
        // Nothing to move through yet
        composeRule.onNodeWithTag("find_next").performClick()
        assertTrue(engine.sessions.getValue(tab).finds.isEmpty())

        composeRule.onNodeWithTag("find_input").performTextInput("cat")
        assertEquals(Triple("cat", false, false), page.finds.last())
        composeRule.runOnUiThread { page.answerFind(FindResult("cat", found = true, current = 1, total = 3)) }
        composeRule.onNodeWithTag("find_status").assertTextEquals("1/3")

        // Enter for the next match, Shift+Enter for the one before
        composeRule.onNodeWithTag("find_input").performKeyInput { pressKey(Key.Enter) }
        assertEquals(Triple("cat", false, false), page.finds.last())
        composeRule.onNodeWithTag("find_input").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        assertEquals(Triple("cat", true, false), page.finds.last())

        composeRule.onNodeWithTag("find_match_case").assertIsOff().performClick()
        composeRule.onNodeWithTag("find_match_case").assertIsOn()
        assertEquals(Triple("cat", false, true), page.finds.last())

        composeRule.runOnUiThread { page.answerFind(FindResult("cat", found = false, current = 0, total = 0)) }
        composeRule.onNodeWithTag("find_status").assertTextEquals("No results")

        // Esc closes it, and the page's highlighting goes with it
        composeRule.onNodeWithTag("find_input").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("find_bar").assertDoesNotExist()
        assertNull(browserVm.findInPage.value)
        assertEquals(1, page.findClearCount)
    }

    @Test
    fun theCloseButtonClosesIt_andTheStartPageOffersNoFind() {
        val tab = launchOnAWebPage()

        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.onNodeWithTag("find_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("find_close").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("find_bar").assertDoesNotExist()
        assertEquals(1, engine.sessions.getValue(tab).findClearCount)

        // A new tab's start page has nothing to search
        composeRule.runOnUiThread { browserVm.openNewTab() }
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_find_in_page").assertIsNotEnabled()
    }
}
