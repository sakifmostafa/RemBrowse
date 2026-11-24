package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Go to address popup (Ctrl+G): the address bar lifted into the middle of the window. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class GoToAddressUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val chrome = BrowserChromeState()
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

    private fun launch() {
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
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    private fun launchOnAWebPage(): String {
        launch()
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/", "Example")
        }
        composeRule.waitForIdle()
        return tab
    }

    private fun open() {
        composeRule.runOnUiThread { chrome.toggleGoToAddress() }
        composeRule.waitForIdle()
    }

    @Test
    fun opensWithTheCurrentAddress_andEnterGoesThereInTheSameTab() {
        val tab = launchOnAWebPage()
        open()

        composeRule.onNodeWithTag("go_to_address").assertIsDisplayed()
        composeRule.onNodeWithTag("go_to_address_input").assertTextEquals("https://example.com/")

        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("rust-lang.org")
        composeRule.onNodeWithTag("go_to_address_input").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("go_to_address").assertDoesNotExist()
        assertFalse(chrome.showGoToAddress)
        val active = browserVm.uiState.value.activeTab!!
        assertEquals(tab, active.id)
        assertEquals("https://rust-lang.org", active.url.trimEnd('/'))
        assertEquals(1, browserVm.uiState.value.tabs.size)
    }

    @Test
    fun onTheStartPage_theFieldIsEmpty() {
        launch()
        open()
        composeRule.onNodeWithTag("go_to_address").assertIsDisplayed()
        // The hint shows in an empty field; what is typed is empty
        val typed = composeRule.onNodeWithTag("go_to_address_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("", typed)
    }

    @Test
    fun typing_offersTheTypedAddressAndOpenTabs() {
        launchOnAWebPage()
        open()
        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("example")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("palette_go").assertIsDisplayed()
        composeRule.onNodeWithTag("palette_tab-${browserVm.uiState.value.activeTabId}").assertIsDisplayed()
    }

    @Test
    fun escCloses_andToggleTwiceCloses() {
        launchOnAWebPage()
        open()
        composeRule.onNodeWithTag("go_to_address").assertIsDisplayed()
        composeRule.onNodeWithTag("go_to_address_input").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("go_to_address").assertDoesNotExist()
        assertFalse(chrome.showGoToAddress)

        open()
        composeRule.onNodeWithTag("go_to_address").assertIsDisplayed()
        open()
        composeRule.onNodeWithTag("go_to_address").assertDoesNotExist()
        assertFalse(chrome.showGoToAddress)
    }

    @Test
    fun altEnter_opensTheAddressInANewTab() {
        launchOnAWebPage()
        val before = browserVm.uiState.value.tabs.size
        open()
        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("rust-lang.org")
        composeRule.onNodeWithTag("go_to_address_input").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("go_to_address").assertDoesNotExist()
        assertEquals(before + 1, browserVm.uiState.value.tabs.size)
        assertEquals("https://rust-lang.org", browserVm.uiState.value.activeTab!!.url.trimEnd('/'))
    }

    private fun assertAbove(upper: String, lower: String) {
        fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("$upper should be above $lower", top(upper) < top(lower))
    }

    @Test
    fun typingTer_offersNewTerminalFirst_withItsShortcut_andEnterOpensIt() {
        launchOnAWebPage()
        val before = browserVm.uiState.value.tabs.size
        open()
        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        // Its own command would only open it again, so it is not offered here
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("address")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("palette_go").assertIsDisplayed()
        composeRule.onNodeWithTag("palette_go_to_address").assertDoesNotExist()
        // Nor Search the web, which would drop what was typed for the toolbar's address bar
        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("search")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("palette_go").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("palette_search").assertDoesNotExist()

        composeRule.onNodeWithTag("go_to_address_input").performTextClearance()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("ter")
        composeRule.waitForIdle()

        // First and highlighted, drawn as in the palette, with the web search just below
        composeRule.onNodeWithTag("palette_new_terminal").assertIsDisplayed().assertIsSelected()
        listOf("Ctrl", "Shift", "T").forEach { key ->
            composeRule.onNode(hasText(key) and hasAnyAncestor(hasTestTag("palette_new_terminal")), useUnmergedTree = true)
                .assertIsDisplayed()
        }
        assertAbove("palette_new_terminal", "palette_go")

        composeRule.onNodeWithTag("go_to_address_input").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("go_to_address").assertDoesNotExist()
        assertEquals(before + 1, browserVm.uiState.value.tabs.size)
        assertTrue(browserVm.uiState.value.activeTab!!.isTerminal)
    }

    @Test
    fun thePalette_ranksTypedTextTheSameWay() {
        launchOnAWebPage()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("command_palette_input").performTextInput("ter")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("palette_new_terminal").assertIsDisplayed().assertIsSelected()
        assertAbove("palette_new_terminal", "palette_go")
    }
}
