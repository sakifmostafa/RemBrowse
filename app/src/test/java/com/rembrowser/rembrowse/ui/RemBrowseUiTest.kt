package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
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

/** Drives the real Compose UI headlessly on a laptop-sized window, with fake engine and network. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class RemBrowseUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val extensionManager = FakeExtensionManager()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(extensionManager, FakeAmoApiClient(), uiScope)
    private val chrome = BrowserChromeState()

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch() {
        composeRule.setContent {
            // Site icons fall back to monograms so the tests never touch the network
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier ->
                            Box(modifier = modifier) { Text(text = "Viewport:${tab.url}") }
                        },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    private fun loadPage(tabId: String, url: String, title: String) {
        engine.sessions.getValue(tabId).simulatePageLoad(url, title, canGoBack = false)
    }

    @Test
    fun splitViewMenu_switchesLayoutsAndKeepsEveryTabInTheRail() {
        launch()

        // Starts with a single pane showing the start page
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertDoesNotExist()
        composeRule.onNodeWithTag("new_tab_page").assertIsDisplayed()

        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_1x2").performClick()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_2").assertDoesNotExist()
        composeRule.onNodeWithTag("split_divider_vertical").assertIsDisplayed()

        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_2x2").performClick()
        for (pane in 0..3) composeRule.onNodeWithTag("browser_pane_$pane").assertIsDisplayed()
        composeRule.onNodeWithTag("split_divider_horizontal").assertIsDisplayed()

        // Back to one pane: all four tabs stay open in the rail
        val allTabIds = browserVm.uiState.value.tabs.map { it.id }
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_1x1").performClick()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertDoesNotExist()
        allTabIds.forEach { id -> composeRule.onNodeWithTag("tab_bar_item_$id").assertExists() }

        // Clicking another tab in the rail shows it and marks it selected
        val target = allTabIds.last()
        composeRule.onNodeWithTag("tab_bar_item_$target").performClick()
        assertEquals(target, browserVm.uiState.value.activeTabId)
        composeRule.onNodeWithTag("tab_bar_item_$target").assertIsSelected()
    }

    @Test
    fun addressBar_showsTheTidiedAddressOfTheActivePage() {
        launch()
        val tabId = browserVm.uiState.value.activeTabId!!

        loadPage(tabId, "https://www.archdaily.com/", "ArchDaily")

        composeRule.onNodeWithTag("omnibox_input").assert(hasText("https://www.archdaily.com"))
        composeRule.onNodeWithText("Viewport:https://www.archdaily.com/").assertIsDisplayed()
        composeRule.onNodeWithTag("new_tab_page").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pressingEnterInTheAddressBar_navigatesWithoutOpeningAnythingElse() {
        launch()

        composeRule.onNodeWithTag("omnibox_input").performClick()
        composeRule.onNodeWithTag("omnibox_input").performTextInput("mozilla.org")
        // A hardware Enter key: press and release, exactly as a laptop keyboard sends it
        composeRule.onNodeWithTag("omnibox_input").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals("https://mozilla.org", browserVm.uiState.value.activeTab?.url)
        assertFalse("The tab switcher must stay closed", chrome.showTabSwitcher)
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        assertFalse("The address bar gives up focus", browserVm.uiState.value.isAddressBarFocused)
    }

    @Test
    fun bookmarkButton_savesThePageAndPinsItToTheRail() {
        launch()
        loadPage(browserVm.uiState.value.activeTabId!!, "https://github.com/", "GitHub")

        composeRule.onNodeWithTag("bookmark_button").performClick()

        assertEquals(listOf("https://github.com/"), browserVm.uiState.value.bookmarks.map { it.url })
        composeRule.onNodeWithContentDescription("Remove bookmark").assertIsDisplayed()
    }

    @Test
    fun moreMenu_opensTheExtensionsManager() {
        launch()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extensions").performClick()

        composeRule.onNodeWithTag("extensions_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("No extensions yet").assertIsDisplayed()
        composeRule.onNodeWithTag("extensions_tab_discover_amo").assertIsDisplayed()
    }

    @Test
    fun removingAnExtension_asksForConfirmationFirst() {
        extensionManager.installedExtensions.value = listOf(
            InstalledExtension(id = "blocker@example", name = "Blocker", version = "1.0", description = "", enabled = true)
        )
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extensions").performClick()

        composeRule.onNodeWithContentDescription("Remove Blocker").performClick()
        assertEquals("Nothing is removed until confirmed", 1, extensionManager.installedExtensions.value.size)

        composeRule.onNodeWithTag("confirm_remove_extension").performClick()
        assertTrue(extensionManager.installedExtensions.value.isEmpty())
    }

    @Test
    fun tabSwitcher_searchesAndSelectsATab() {
        browserVm.openNewTab("https://github.com/")
        browserVm.openNewTab("https://www.youtube.com/")
        val tabs = browserVm.uiState.value.tabs
        loadPage(tabs[1].id, "https://github.com/", "GitHub")
        loadPage(tabs[2].id, "https://www.youtube.com/", "YouTube")
        launch()

        composeRule.onNodeWithTag("tab_switcher_button").performClick()
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()
        tabs.forEach { composeRule.onNodeWithTag("switcher_tab_${it.id}").assertExists() }

        composeRule.onNodeWithTag("switcher_search").performTextInput("git")
        composeRule.onNodeWithTag("switcher_tab_${tabs[1].id}").assertIsDisplayed()
        composeRule.onNodeWithTag("switcher_tab_${tabs[2].id}").assertDoesNotExist()

        composeRule.onNodeWithTag("switcher_tab_${tabs[1].id}").performClick()
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        assertEquals(tabs[1].id, browserVm.uiState.value.activeTabId)
    }

    @Test
    fun commandPalette_runsCommandsAndOpensTypedAddresses() {
        launch()

        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette").assertIsDisplayed()
        composeRule.onNodeWithTag("command_palette_input").performTextInput("settings")
        composeRule.onNodeWithTag("palette_settings").performClick()
        composeRule.onNodeWithTag("command_palette").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_dialog").assertIsDisplayed()

        composeRule.runOnUiThread {
            chrome.showSettings = false
            chrome.showCommandPalette = true
        }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("mozilla.org")
        composeRule.onNodeWithTag("palette_go").performClick()
        assertEquals("https://mozilla.org", browserVm.uiState.value.activeTab?.url)
    }

    @Test
    fun settings_changeThemeAccentAndPrivacy() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()

        composeRule.onNodeWithTag("setting_dark_theme").performClick()
        assertFalse(browserVm.uiState.value.settings.useDarkTheme)

        composeRule.onNodeWithTag("accent_teal").performClick()
        assertEquals(AccentColor.TEAL, browserVm.uiState.value.settings.accent)
        composeRule.onNodeWithTag("accent_teal").assertIsSelected()

        composeRule.onNodeWithTag("settings_section_privacy").performClick()
        composeRule.onNodeWithTag("setting_enhanced_privacy").performClick()
        assertTrue(browserVm.uiState.value.settings.enhancedPrivacy)
        assertEquals(true, engine.enhancedPrivacy)
    }

    @Test
    fun settings_adBlockerSwitchTurnsTheDefaultBlockerOffAndOn() {
        extensionManager.installedExtensions.value = listOf(
            InstalledExtension(
                id = DefaultAdBlocker.ID,
                name = "AdGuard AdBlocker",
                version = "5.0",
                description = "",
                enabled = true
            )
        )
        launch()
        composeRule.runOnUiThread { chrome.showSettings = true }
        composeRule.onNodeWithTag("settings_section_privacy").performClick()

        composeRule.onNodeWithTag("setting_ad_blocker").assertIsOn()
        composeRule.onNodeWithTag("setting_ad_blocker").performClick()
        assertFalse(extensionManager.installedExtensions.value.single().enabled)
        composeRule.onNodeWithTag("setting_ad_blocker").assertIsOff()

        composeRule.onNodeWithTag("setting_ad_blocker").performClick()
        assertTrue(extensionManager.installedExtensions.value.single().enabled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pressingEnterInTabSearch_opensTheMatchAndLeavesTheSwitcherClosed() {
        browserVm.openNewTab("https://github.com/")
        val tabs = browserVm.uiState.value.tabs
        loadPage(tabs[1].id, "https://github.com/", "GitHub")
        browserVm.selectTab(tabs[0].id)
        launch()

        composeRule.onNodeWithTag("tab_switcher_button").performClick()
        composeRule.onNodeWithTag("switcher_search").performClick()
        composeRule.onNodeWithTag("switcher_search").performTextInput("git")
        composeRule.onNodeWithTag("switcher_search").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals(tabs[1].id, browserVm.uiState.value.activeTabId)
        assertFalse("Enter must not reopen the switcher", chrome.showTabSwitcher)
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
    }

    @Test
    fun pageConfirmDialog_reportsTheUsersChoiceAndCloses() {
        launch()
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://shop.example/cart", "Cart")
        var answer: Boolean? = null
        composeRule.runOnUiThread {
            engine.sessions.getValue(tabId).simulatePrompt(
                PagePrompt.Confirm(
                    id = "confirm-1",
                    tabId = tabId,
                    title = null,
                    message = "Empty your cart?",
                    onResult = { answer = it },
                    onDismiss = { answer = false }
                )
            )
        }

        composeRule.onNodeWithText("shop.example asks").assertIsDisplayed()
        composeRule.onNodeWithText("Empty your cart?").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(true, answer)
        composeRule.onNodeWithText("Empty your cart?").assertDoesNotExist()
        assertTrue(browserVm.uiState.value.pendingPrompts.isEmpty())
    }

    @Test
    fun railContextMenu_closesATab() {
        browserVm.openNewTab("https://one.example/")
        launch()
        val closing = browserVm.uiState.value.tabs.first().id

        // Long press (or a secondary click) opens the tab's menu
        composeRule.onNodeWithTag("tab_bar_item_$closing").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("tab_bar_close_$closing").performClick()

        assertFalse(browserVm.uiState.value.tabs.any { it.id == closing })
        assertEquals(LayoutMode.SINGLE_1X1, browserVm.uiState.value.layoutMode)
    }
}
