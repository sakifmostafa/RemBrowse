package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.SearchEngine
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
    private val downloadStorage = FakeDownloadStorage()
    private val downloadsVm = DownloadsViewModel(
        storage = downloadStorage,
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        // Unconfined: a download runs to its end inside the call that starts it
        ioDispatcher = Dispatchers.Unconfined,
        askBeforeDownloading = { browserVm.uiState.value.settings.askBeforeDownloading },
        onRequestSettled = browserVm::closeTabLeftBehindByDownload
    ).also { browserVm.downloadHandler = it::onDownloadRequested }
    private val openedDownloads = mutableListOf<DownloadItem>()
    private val filePickerRequests = mutableListOf<PagePrompt.FilePicker>()
    private var canOpenDownloads = true

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
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        onPickFiles = { filePickerRequests += it },
                        onOpenDownload = { item ->
                            openedDownloads += item
                            canOpenDownloads
                        },
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
    fun splitView_keepsItsControlsInTheSplitMenuAndShowsNoBarAboveThePanes() {
        val first = browserVm.uiState.value.activeTabId!!
        loadPage(first, "https://archinspire.co/", "Architecture")
        browserVm.openNewTab("https://notion.so/project-notes")
        val notes = browserVm.uiState.value.activeTabId!!
        loadPage(notes, "https://notion.so/project-notes", "Project Notes")
        launch()

        // Not split yet: the menu offers ways to split, and nothing about a split
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_swap").assertDoesNotExist()
        composeRule.onNodeWithTag("layout_1x1").assertDoesNotExist()
        composeRule.onNodeWithTag("split_left").performClick()

        // The current tab went to the right pane, as asked. No row of tabs or buttons is added
        // above the panes: the split's controls are all in the menu.
        assertEquals(notes, browserVm.uiState.value.paneTabIds[1])
        composeRule.onNodeWithTag("split_strip").assertDoesNotExist()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
        // The one address bar at the top shows the focused pane's page...
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("https://notion.so/project-notes"))

        // ...and follows focus when the other page's tab is picked in the rail
        composeRule.onNodeWithTag("tab_bar_item_$first").performClick()
        assertEquals(0, browserVm.uiState.value.activePaneIndex)
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("https://archinspire.co"))

        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_preset_50").assertIsSelected()
        composeRule.onNodeWithTag("layout_1x2").assertIsSelected()
        composeRule.onNodeWithTag("split_preset_70").performClick()
        assertEquals(0.7f, browserVm.uiState.value.splitRatios.horizontal, 0.001f)

        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_preset_70").assertIsSelected()
        composeRule.onNodeWithTag("split_preset_50").performClick()
        assertEquals(0.5f, browserVm.uiState.value.splitRatios.horizontal, 0.001f)

        val before = browserVm.uiState.value.paneTabIds
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_swap").performClick()
        assertEquals(before.reversed(), browserVm.uiState.value.paneTabIds)
        assertEquals("Focus stays with the page, not the position", first, browserVm.uiState.value.activeTabId)

        // Stacked is the same split turned on its side; the presets size it too
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_2x1").performClick()
        assertEquals(LayoutMode.SPLIT_2X1, browserVm.uiState.value.layoutMode)
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_2x1").assertIsSelected()
        composeRule.onNodeWithTag("split_preset_70").performClick()
        assertEquals(0.7f, browserVm.uiState.value.splitRatios.vertical, 0.001f)

        // With four panes there is no single divider to size, so the presets are not offered
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_2x2").performClick()
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_preset_50").assertDoesNotExist()
        composeRule.onNodeWithTag("split_swap").assertIsDisplayed()

        composeRule.onNodeWithTag("layout_1x1").performClick()
        assertEquals(LayoutMode.SINGLE_1X1, browserVm.uiState.value.layoutMode)
        assertEquals("Closing the split keeps the focused page", first, browserVm.uiState.value.activeTabId)
        assertEquals("Every tab is still open", 4, browserVm.uiState.value.tabs.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun splitView_theTopAddressBarNavigatesTheFocusedPaneOnly() {
        loadPage(browserVm.uiState.value.activeTabId!!, "https://archinspire.co/", "Architecture")
        browserVm.splitCurrentTab(currentOnLeft = true)
        launch()
        val left = browserVm.uiState.value.paneTabIds[0]
        val right = browserVm.uiState.value.paneTabIds[1]

        composeRule.onNodeWithTag("tab_bar_item_$right").performClick()
        assertEquals(1, browserVm.uiState.value.activePaneIndex)
        composeRule.onNodeWithTag("omnibox_input").performClick()
        composeRule.onNodeWithTag("omnibox_input").performTextInput("mozilla.org")
        composeRule.onNodeWithTag("omnibox_input").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        val state = browserVm.uiState.value
        assertEquals("https://mozilla.org", state.tabs.first { it.id == right }.url)
        assertEquals("The other pane is untouched", "https://archinspire.co/", state.tabs.first { it.id == left }.url)
        assertEquals(LayoutMode.SPLIT_1X2, state.layoutMode)
        assertFalse(chrome.showTabSwitcher)
    }

    @Test
    fun splitView_closingTheSplitKeepsWhicheverPaneIsFocused() {
        loadPage(browserVm.uiState.value.activeTabId!!, "https://archinspire.co/", "Architecture")
        browserVm.splitCurrentTab(currentOnLeft = true)
        launch()
        val right = browserVm.uiState.value.paneTabIds[1]

        composeRule.onNodeWithTag("tab_bar_item_$right").performClick()
        assertEquals(1, browserVm.uiState.value.activePaneIndex)
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_1x1").performClick()

        assertEquals(LayoutMode.SINGLE_1X1, browserVm.uiState.value.layoutMode)
        assertEquals(right, browserVm.uiState.value.activeTabId)
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

    private fun toolbarExtension(id: String, name: String, badge: String? = null, enabled: Boolean = true) =
        InstalledExtension(
            id = id,
            name = name,
            version = "1.0",
            description = "",
            enabled = enabled,
            hasBrowserAction = true,
            badgeText = badge
        )

    @Test
    fun extensionButtons_sitAtTheBottomOfTheRailAndOpenTheirPopup() {
        extensionManager.installedExtensions.value = listOf(
            toolbarExtension("vpn@example", "Proton VPN"),
            toolbarExtension(DefaultAdBlocker.ID, DefaultAdBlocker.NAME, badge = "12"),
            toolbarExtension("off@example", "Switched off", enabled = false)
        )
        launch()

        // At the foot of the rail, below the tabs and the new-tab button
        val rail = composeRule.onNodeWithTag("tab_rail").getUnclippedBoundsInRoot()
        val newTab = composeRule.onNodeWithTag("new_tab_button").getUnclippedBoundsInRoot()
        val section = composeRule.onNodeWithTag("rail_extensions").getUnclippedBoundsInRoot()
        val vpn = composeRule.onNodeWithTag("rail_extension_vpn@example")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(vpn.top > newTab.bottom)
        assertTrue(rail.bottom - section.bottom <= 8.dp)
        assertTrue(section.top - newTab.bottom > 200.dp)

        // A badge is read out with the extension's name; a switched-off extension has no button
        composeRule.onNodeWithContentDescription("AdGuard AdBlocker (12)").assertIsDisplayed()
        composeRule.onNodeWithTag("rail_extension_off@example").assertDoesNotExist()

        composeRule.onNodeWithTag("rail_extension_vpn@example").performClick()
        assertEquals(listOf("vpn@example"), extensionManager.triggeredActionIds)
        composeRule.onNodeWithTag("extension_popup_page").assertIsDisplayed()
        composeRule.onNodeWithTag("rail_extension_vpn@example").assertIsSelected()

        composeRule.onNodeWithContentDescription("Close Proton VPN").performClick()
        composeRule.onNodeWithTag("extension_popup_page").assertDoesNotExist()

        // With the rail on screen the More menu does not repeat them
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extensions").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_extension_vpn@example").assertDoesNotExist()
    }

    @Test
    fun extensionButtons_beyondFourAreListedInAMenu() {
        extensionManager.installedExtensions.value = (1..6).map { toolbarExtension("e$it@example", "Extension $it") }
        launch()

        (1..3).forEach { composeRule.onNodeWithTag("rail_extension_e$it@example").assertIsDisplayed() }
        composeRule.onNodeWithTag("rail_extension_e4@example").assertDoesNotExist()

        composeRule.onNodeWithTag("rail_extensions_more").performClick()
        composeRule.onNodeWithTag("menu_extension_e4@example").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_extension_e5@example").performClick()
        assertEquals(listOf("e5@example"), extensionManager.triggeredActionIds)

        // Its button is marked while the popup of an extension in the menu is open
        composeRule.onNodeWithTag("rail_extensions_more").assertIsSelected()
    }

    // A phone in landscape, then the same with the keyboard up: the tabs keep their room and
    // every extension stays reachable
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun extensionButtons_onAShortWindow_leaveRoomForTheTabs() {
        assertShortRailKeepsTabsAndExtensions()
    }

    @Test
    @Config(qualifiers = "w800dp-h250dp-land-mdpi")
    fun extensionButtons_onAVeryShortWindow_leaveRoomForTheTabs() {
        assertShortRailKeepsTabsAndExtensions()
    }

    private fun assertShortRailKeepsTabsAndExtensions() {
        browserVm.openNewTab("https://one.example/")
        browserVm.openNewTab("https://two.example/")
        extensionManager.installedExtensions.value = (1..4).map { toolbarExtension("e$it@example", "Extension $it") }
        launch()

        val newTab = composeRule.onNodeWithTag("new_tab_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        val section = composeRule.onNodeWithTag("rail_extensions").getUnclippedBoundsInRoot()
        assertTrue(newTab.bottom - newTab.top >= 40.dp)
        assertTrue(newTab.bottom <= section.top)
        val firstTab = browserVm.uiState.value.tabs.first().id
        composeRule.onNodeWithTag("tab_bar_item_$firstTab").assertIsDisplayed()

        // Extensions without a button of their own are in the overflow menu
        val onRail = (1..4).filter { i ->
            composeRule.onAllNodesWithTag("rail_extension_e$i@example").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(onRail.size < 4)
        composeRule.onNodeWithTag("rail_extensions_more").performClick()
        (1..4).filterNot { it in onRail }.forEach { i ->
            composeRule.onNodeWithTag("menu_extension_e$i@example").assertIsDisplayed()
        }
    }

    @Test
    fun extensionButtons_manageExtensionsFromTheContextMenu() {
        extensionManager.installedExtensions.value = listOf(toolbarExtension("vpn@example", "Proton VPN"))
        launch()

        composeRule.onNodeWithTag("rail_extension_vpn@example")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Manage extensions").performClick()

        composeRule.onNodeWithTag("extensions_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/extensions"))
    }

    @Test
    fun moreMenu_opensTheExtensionsManager() {
        launch()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extensions").performClick()

        // Extensions live in the settings page, which opens in a tab with its own address
        composeRule.onNodeWithTag("settings_page").assertIsDisplayed()
        composeRule.onNodeWithTag("extensions_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/extensions"))
        composeRule.onNodeWithText("No extensions yet").assertIsDisplayed()

        // The Firefox Add-ons catalog is one tab away
        composeRule.onNodeWithTag("extensions_tab_discover_amo").performClick()
        composeRule.onNodeWithTag("extensions_search").assertIsDisplayed()
    }

    @Test
    fun addressBar_keepsItsCaretAtTheStartWhenThePageChangesUnderIt() {
        launch()
        val caret = SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange.Zero)

        // A text field scrolls to its caret. Left at the end of a long address, it would show
        // the tail of the path and hide the site the page comes from.
        loadPage(
            browserVm.uiState.value.activeTabId!!,
            "https://example.org/a/long/path/that/does/not/fit/in/the/address/bar/of/a/narrow/window",
            "Example"
        )
        composeRule.onNodeWithTag("omnibox_input").assert(caret)

        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.EXTENSIONS) }
        composeRule.onNodeWithTag("omnibox_input")
            .assert(hasText("rem://settings/extensions"))
            .assert(caret)
    }

    @Test
    fun settingsPage_opensInATabAndEachSectionHasItsOwnAddress() {
        loadPage(browserVm.uiState.value.activeTabId!!, "https://example.org/", "Example")
        launch()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()

        composeRule.onNodeWithTag("settings_page").assertIsDisplayed()
        assertEquals("Settings opened in a tab of its own", 2, browserVm.uiState.value.tabs.size)
        assertEquals("Settings", browserVm.uiState.value.activeTab?.displayTitle)
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/appearance"))
        composeRule.onNodeWithTag("settings_section_appearance").assertIsSelected()

        composeRule.onNodeWithTag("settings_section_search").performClick()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/search"))
        composeRule.onNodeWithTag("search_engine_google").assertIsSelected()
        composeRule.onNodeWithTag("search_engine_duckduckgo").performClick()
        assertEquals(SearchEngine.DUCKDUCKGO, browserVm.uiState.value.settings.searchEngine)
        composeRule.onNodeWithTag("search_engine_duckduckgo").assertIsSelected()

        // Asking for settings again goes back to the same tab instead of opening another
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()
        assertEquals(2, browserVm.uiState.value.tabs.size)
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
        composeRule.onNodeWithTag("settings_page").assertIsDisplayed()

        composeRule.runOnUiThread { chrome.showCommandPalette = true }
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
    fun settingsTabsSection_changesStartupNewTabPositionAndDesktopSites() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TABS) }
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/tabs"))
        composeRule.onNodeWithTag("settings_section_tabs").assertIsSelected()

        composeRule.onNodeWithTag("setting_startup_restore").assertIsSelected()
        composeRule.onNodeWithTag("setting_startup_new_tab").performClick()
        assertFalse(browserVm.uiState.value.settings.restoreTabsOnStartup)
        composeRule.onNodeWithTag("setting_startup_new_tab").assertIsSelected()

        composeRule.onNodeWithTag("setting_new_tab_end").assertIsSelected()
        composeRule.onNodeWithTag("setting_new_tab_next").performClick()
        assertTrue(browserVm.uiState.value.settings.openNewTabsNextToCurrent)
        composeRule.onNodeWithTag("setting_new_tab_next").assertIsSelected()

        composeRule.onNodeWithTag("setting_desktop_default").assertIsOn()
        composeRule.onNodeWithTag("setting_desktop_default").performClick()
        assertFalse(browserVm.uiState.value.settings.desktopSiteByDefault)
        composeRule.onNodeWithTag("setting_desktop_default").assertIsOff()
    }

    @Test
    fun download_asksFirstThenIsSavedAnnouncedListedAndOpened() {
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://example.org/", "Example")
        launch()

        composeRule.runOnUiThread {
            engine.sessions.getValue(tabId).simulateDownload(
                url = "https://example.org/files/report.pdf",
                fileName = "report.pdf",
                bytes = ByteArray(2048),
                mimeType = "application/pdf"
            )
        }

        // Asked first, naming the file, its size and where it comes from
        composeRule.onNodeWithTag("download_prompt").assertIsDisplayed()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB · from example.org").assertIsDisplayed()
        assertTrue(downloadStorage.saved.isEmpty())

        composeRule.onNodeWithTag("download_confirm").performClick()
        composeRule.onNodeWithTag("download_prompt").assertDoesNotExist()
        assertEquals(2048, downloadStorage.saved.getValue("report.pdf").size)

        // Announced over the page, with a way to open it
        composeRule.onNodeWithTag("download_notice").assertIsDisplayed()
        composeRule.onNodeWithText("Downloaded report.pdf").assertIsDisplayed()
        composeRule.onNodeWithTag("download_notice_action").performClick()
        assertEquals(listOf("report.pdf"), openedDownloads.map { it.fileName })
        composeRule.onNodeWithTag("download_notice").assertDoesNotExist()

        // And listed under Settings → Downloads
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_downloads").performClick()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/downloads"))
        val id = downloadsVm.uiState.value.items.single().id
        composeRule.onNodeWithTag("download_item_$id").assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB · example.org").assertIsDisplayed()

        // Nothing on the device can show it: said plainly instead of failing silently
        canOpenDownloads = false
        composeRule.onNodeWithTag("download_open_$id").performClick()
        composeRule.onNodeWithText("No app on this device can open report.pdf.").assertIsDisplayed()

        composeRule.onNodeWithTag("download_remove_$id").performClick()
        composeRule.onNodeWithTag("download_item_$id").assertDoesNotExist()
        composeRule.onNodeWithText("No downloads yet").assertIsDisplayed()
        assertTrue("Removing an entry leaves the file", "report.pdf" in downloadStorage.saved)
    }

    @Test
    fun download_canBeDeclinedOrSavedWithoutAsking_andItsNoticeLeavesByItself() {
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://example.org/", "Example")
        launch()

        val declined = composeRule.runOnUiThread<com.rembrowser.rembrowse.model.DownloadRequest> {
            engine.sessions.getValue(tabId).simulateDownload("https://example.org/setup.exe", "setup.exe", ByteArray(16))
        }
        composeRule.onNodeWithTag("download_decline").performClick()
        composeRule.onNodeWithTag("download_prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("download_notice").assertDoesNotExist()
        assertTrue(downloadStorage.saved.isEmpty())
        assertTrue((declined.body as com.rembrowser.rembrowse.fakes.TrackedInputStream).isClosed)

        // With asking turned off in Settings, a file is saved as soon as it is offered
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        composeRule.onNodeWithTag("setting_ask_before_download").assertIsOn()
        composeRule.onNodeWithTag("setting_ask_before_download").performClick()
        composeRule.onNodeWithTag("setting_ask_before_download").assertIsOff()
        composeRule.runOnUiThread {
            engine.sessions.getValue(tabId).simulateDownload("https://example.org/a.zip", "a.zip", ByteArray(10))
        }
        composeRule.onNodeWithTag("download_prompt").assertDoesNotExist()
        assertTrue("a.zip" in downloadStorage.saved)
        composeRule.onNodeWithTag("download_notice").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(7_000)
        composeRule.onNodeWithTag("download_notice").assertDoesNotExist()

        composeRule.onNodeWithTag("downloads_clear").performClick()
        composeRule.onNodeWithText("No downloads yet").assertIsDisplayed()
        assertTrue(downloadsVm.uiState.value.items.isEmpty())
    }

    @Test
    fun download_fromALinkThatOpenedItsOwnTab_closesThatEmptyTabOnceItIsOver() {
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://example.org/", "Example")
        launch()

        composeRule.runOnUiThread {
            engine.sessions.getValue(tabId).simulateNewTab("https://example.org/files/big.zip")
            val newTabId = browserVm.uiState.value.activeTabId!!
            engine.sessions.getValue(newTabId)
                .simulateDownload("https://example.org/files/big.zip", "big.zip", ByteArray(8))
        }
        assertEquals("Still there while the question is open", 2, browserVm.uiState.value.tabs.size)

        composeRule.onNodeWithTag("download_confirm").performClick()

        assertEquals(listOf(tabId), browserVm.uiState.value.tabs.map { it.id })
        assertTrue("big.zip" in downloadStorage.saved)
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
        browserVm.openSettings(SettingsSection.PRIVACY)
        launch()

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
    fun uploadButton_opensTheSystemFilePickerOnceInsteadOfADialog() {
        launch()
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://drive.example/", "Drive")
        composeRule.runOnUiThread {
            engine.sessions.getValue(tabId).simulatePrompt(
                PagePrompt.FilePicker(
                    id = "upload-1",
                    tabId = tabId,
                    title = null,
                    multiple = true,
                    mimeTypes = listOf("*/*"),
                    onResult = {},
                    onDismiss = {}
                )
            )
        }
        composeRule.waitForIdle()

        assertEquals(listOf("upload-1"), filePickerRequests.map { it.id })
        assertTrue(filePickerRequests.single().multiple)
        composeRule.onNodeWithText("drive.example asks").assertDoesNotExist()

        // Recomposing (a new frame, another state change) does not open it again
        composeRule.runOnUiThread { browserVm.onAddressBarChange("drive") }
        composeRule.waitForIdle()
        assertEquals(1, filePickerRequests.size)

        // Once answered, the request is gone
        composeRule.runOnUiThread { browserVm.resolvePrompt("upload-1") }
        assertTrue(browserVm.uiState.value.pendingPrompts.isEmpty())
    }

    @Test
    fun history_listsVisitedPages_searchesOpensAndClears() {
        launch()
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://news.example/", "Morning news")
        loadPage(tabId, "https://github.com/", "GitHub")

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_history").performClick()

        composeRule.onNodeWithTag("history_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("rem://settings/history"))
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Morning news").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()

        // Search narrows the list
        composeRule.onNodeWithTag("history_search").performTextInput("news")
        composeRule.onNodeWithText("GitHub").assertDoesNotExist()

        // Removing one entry
        composeRule.onNodeWithContentDescription("Remove Morning news from history").performClick()
        assertEquals(listOf("https://github.com/"), browserVm.history.value.map { it.url })

        // Clearing asks first
        composeRule.onNodeWithTag("history_clear").performClick()
        composeRule.onNodeWithText("Clear browsing history?").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").performClick()
        assertTrue(browserVm.history.value.isEmpty())
    }

    @Test
    fun history_selectingAPageOpensIt() {
        launch()
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://news.example/", "Morning news")
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.HISTORY) }

        composeRule.onNodeWithTag("history_entry_https://news.example/").performClick()

        val active = browserVm.uiState.value.activeTab!!
        assertEquals("https://news.example/", active.url)
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
