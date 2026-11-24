package com.rembrowser.rembrowse.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.text.TextRange
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
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.DropZone
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.ThemeMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Drives the real Compose UI headlessly on a laptop-sized window, with fake engine and network. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
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
    private var backDispatcher: OnBackPressedDispatcher? = null

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(
        viewport: @Composable (BrowserTab, Modifier) -> Unit = { tab, modifier ->
            Box(modifier = modifier) { Text(text = "Viewport:${tab.url}") }
        }
    ) {
        composeRule.setContent {
            // Site icons fall back to monograms so the tests never touch the network
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
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
                        paneViewport = viewport,
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
        composeRule.onNodeWithContentDescription("uBlock Origin (12)").assertIsDisplayed()
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
    fun moreMenu_printRow_isEnabledOnAWebPage_andPrintsIt() {
        val tabId = browserVm.uiState.value.activeTabId!!
        loadPage(tabId, "https://example.org/", "Example")
        launch()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_print").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, engine.sessions.getValue(tabId).printCount)
        composeRule.onNodeWithTag("menu_print").assertDoesNotExist()
    }

    @Test
    fun moreMenu_printRow_isDisabledOnTheNewTabPage() {
        launch()
        composeRule.onNodeWithTag("new_tab_page").assertIsDisplayed()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_print").performScrollTo().assertIsNotEnabled().performClick()

        assertEquals(0, engine.sessions.values.sumOf { it.printCount })
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
    fun commandPalette_showsTheNewTerminalShortcut() {
        launch()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("new terminal")
        composeRule.onNodeWithTag("palette_new_terminal").assertIsDisplayed()
        // The palette draws each key of a shortcut in its own cap
        listOf("Ctrl", "Shift", "T").forEach { key ->
            composeRule.onNode(
                hasText(key) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("palette_new_terminal")),
                useUnmergedTree = true
            ).assertIsDisplayed()
        }
    }

    @Test
    fun settings_changeThemeAccentAndPrivacy() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()

        // Light, dark, or as the device is set (the default), one at a time
        composeRule.onNodeWithTag("setting_dark_theme").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_system").assertIsSelected()
        composeRule.onNodeWithTag("theme_light").performClick()
        assertEquals(ThemeMode.LIGHT, browserVm.uiState.value.settings.themeMode)
        composeRule.onNodeWithTag("theme_light").assertIsSelected()
        composeRule.onNodeWithTag("theme_system").assertIsNotSelected()
        composeRule.onNodeWithTag("theme_dark").performClick()
        assertEquals(ThemeMode.DARK, browserVm.uiState.value.settings.themeMode)
        composeRule.onNodeWithTag("theme_dark").assertIsSelected()
        composeRule.onNodeWithTag("theme_light").assertIsNotSelected()
        composeRule.onNodeWithTag("theme_system").performClick()
        assertEquals(ThemeMode.SYSTEM, browserVm.uiState.value.settings.themeMode)

        composeRule.onNodeWithTag("accent_teal").performClick()
        assertEquals(AccentColor.TEAL, browserVm.uiState.value.settings.accent)
        composeRule.onNodeWithTag("accent_teal").assertIsSelected()

        composeRule.onNodeWithTag("settings_section_privacy").performClick()
        composeRule.onNodeWithTag("setting_enhanced_privacy").performClick()
        assertTrue(browserVm.uiState.value.settings.enhancedPrivacy)
        assertEquals(true, engine.enhancedPrivacy)
    }

    @Test
    fun settingsTabsSection_swipeNavigationSwitchTurnsTwoFingerSwipeOffAndOn() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TABS) }

        composeRule.onNodeWithTag("setting_swipe_navigation").performScrollTo().assertIsOn()
        composeRule.onNodeWithTag("setting_swipe_navigation").performClick()
        assertFalse(browserVm.uiState.value.settings.swipeNavigation)
        composeRule.onNodeWithTag("setting_swipe_navigation").assertIsOff()

        composeRule.onNodeWithTag("setting_swipe_navigation").performClick()
        assertTrue(browserVm.uiState.value.settings.swipeNavigation)
    }

    @Test
    fun settingsTabsSection_reverseSwipeRow_showsOnlyWhileSwipingIsOn_andTogglesTheSetting() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TABS) }

        composeRule.onNodeWithTag("setting_reverse_swipe").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("setting_reverse_swipe").performClick()
        assertTrue(browserVm.uiState.value.settings.reverseSwipeDirection)
        composeRule.onNodeWithTag("setting_reverse_swipe").assertIsOn()

        composeRule.onNodeWithTag("setting_swipe_navigation").performScrollTo().performClick()
        assertFalse(browserVm.uiState.value.settings.swipeNavigation)
        composeRule.onNodeWithTag("setting_reverse_swipe").assertDoesNotExist()

        composeRule.onNodeWithTag("setting_swipe_navigation").performClick()
        composeRule.onNodeWithTag("setting_reverse_swipe").performScrollTo().assertIsOn()
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
    fun toolbarBack_isEnabledForAPageOpenedTab_andReturnsToTheOpener() {
        val openerId = browserVm.uiState.value.activeTabId!!
        loadPage(openerId, "https://example.org/", "Example")
        launch()
        composeRule.onNodeWithContentDescription("Back").assertIsNotEnabled()

        composeRule.runOnUiThread { engine.sessions.getValue(openerId).simulateNewTab("https://example.org/popup") }
        composeRule.waitForIdle()
        val popupId = browserVm.uiState.value.activeTabId!!
        assertTrue(popupId != openerId)
        composeRule.onNodeWithContentDescription("Back").assertIsEnabled()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(openerId, browserVm.uiState.value.activeTabId)
        assertTrue(browserVm.uiState.value.tabs.none { it.id == popupId })
        composeRule.onNodeWithContentDescription("Back").assertIsNotEnabled()
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
                name = DefaultAdBlocker.NAME,
                version = "1.61",
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

    /** Reddit beside YouTube, and a third tab (returned) that is only in the rail. */
    private fun redditBesideYouTubeWithNewsInTheRail(): String {
        val reddit = browserVm.uiState.value.activeTabId!!
        loadPage(reddit, "https://reddit.com/", "Reddit")
        val news = browserVm.openNewTab("https://news.example/")
        loadPage(news, "https://news.example/", "News")
        val youtube = browserVm.openNewTab("https://youtube.com/")
        loadPage(youtube, "https://youtube.com/", "YouTube")
        browserVm.selectTab(reddit)
        browserVm.setLayoutMode(LayoutMode.SPLIT_1X2)
        browserVm.assignTabToPane(1, youtube)
        assertEquals(listOf(reddit, youtube), browserVm.uiState.value.paneTabIds)
        return news
    }

    /** A point of pane [paneIndex], in the coordinates of the rail tile of [tabId]. */
    private fun pointInPane(tabId: String, paneIndex: Int, fractionX: Float, fractionY: Float): Offset {
        val tile = composeRule.onNodeWithTag("tab_bar_item_$tabId").getUnclippedBoundsInRoot()
        val pane = composeRule.onNodeWithTag("browser_pane_$paneIndex").getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        return Offset(
            (pane.left + pane.width * fractionX - tile.left).value * density,
            (pane.top + pane.height * fractionY - tile.top).value * density
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun draggingARailTabWithTheMouse_ontoAPanesEdgeAddsAThirdPane() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        launch()
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        val redditPane = composeRule.onNodeWithTag("browser_pane_0").getUnclippedBoundsInRoot()
        val bottomOfYouTube = pointInPane(news, paneIndex = 1, fractionX = 0.5f, fractionY = 0.9f)

        // Press on the tile and move: the landing spot is shown while the button is held
        composeRule.onNodeWithTag("tab_bar_item_$news").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
            moveTo(bottomOfYouTube)
        }
        composeRule.onNodeWithTag("tab_drag_ghost", useUnmergedTree = true).assertIsDisplayed()
        val youtubePane = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        val highlight = composeRule.onNodeWithTag("pane_drop_highlight").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals("The lower half of the YouTube pane lights up", youtubePane.bottom, highlight.bottom)
        assertEquals(youtubePane.height / 2, highlight.height)
        assertEquals(youtubePane.width, highlight.width)
        composeRule.onNodeWithContentDescription("Split below, pane 2").assertIsDisplayed()
        assertEquals("Nothing changes until the tab is let go", 2, browserVm.uiState.value.paneCount)

        composeRule.onNodeWithTag("tab_bar_item_$news").performMouseInput { release() }
        composeRule.waitForIdle()

        val state = browserVm.uiState.value
        assertEquals(listOf(reddit, youtube, news), state.paneTabIds)
        assertEquals("The drop is not also a click or a context menu", news, state.activeTabId)
        composeRule.onNodeWithTag("pane_drop_highlight").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_drag_ghost", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("tab_bar_close_$news").assertDoesNotExist()
        for (pane in 0..2) composeRule.onNodeWithTag("browser_pane_$pane").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_3").assertDoesNotExist()
        composeRule.onNodeWithText("Viewport:https://news.example/").assertIsDisplayed()

        // Reddit kept its full height; the right column is shared
        assertEquals(redditPane, composeRule.onNodeWithTag("browser_pane_0").getUnclippedBoundsInRoot())
        val upper = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        val lower = composeRule.onNodeWithTag("browser_pane_2").getUnclippedBoundsInRoot()
        assertEquals(upper.left, lower.left)
        assertTrue(lower.top > upper.bottom)
        assertTrue(upper.height < redditPane.height / 2)
        // Each split has its own divider
        composeRule.onNodeWithTag("split_divider_vertical").assertIsDisplayed()
        composeRule.onNodeWithTag("split_divider_horizontal").assertIsDisplayed()
        composeRule.onNodeWithTag("omnibox_input").assert(hasText("https://news.example"))

        // The pane's tab menu closes the pane again; the tab stays in the rail
        composeRule.onNodeWithTag("tab_bar_item_$youtube").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("tab_bar_close_pane_$youtube").performClick()
        assertEquals(listOf(reddit, news), browserVm.uiState.value.paneTabIds)
        composeRule.onNodeWithTag("tab_bar_item_$youtube").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_2").assertDoesNotExist()
    }

    @Test
    fun draggingARailTabByTouch_startsAfterALongPressAndDropsOnThePane() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        launch()
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        val centreOfReddit = pointInPane(news, paneIndex = 0, fractionX = 0.5f, fractionY = 0.5f)
        val rightOfYouTube = pointInPane(news, paneIndex = 1, fractionX = 0.95f, fractionY = 0.5f)

        // A long press alone still opens the tab's menu, as before
        composeRule.onNodeWithTag("tab_bar_item_$news").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            // A finger never rests perfectly still
            moveBy(Offset(1f, 0f))
        }
        composeRule.onNodeWithTag("tab_bar_close_$news").assertIsDisplayed()

        // Moving on from there picks the tab up, and the menu gets out of the way
        composeRule.onNodeWithTag("tab_bar_item_$news").performTouchInput {
            moveTo(center + Offset(60f, 0f))
            moveTo(rightOfYouTube)
        }
        composeRule.onNodeWithTag("tab_bar_close_$news").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Split right, pane 2").assertIsDisplayed()

        // The centre of a pane replaces what it shows
        composeRule.onNodeWithTag("tab_bar_item_$news").performTouchInput { moveTo(centreOfReddit) }
        composeRule.onNodeWithContentDescription("Show here, pane 1").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_bar_item_$news").performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(listOf(news, youtube), browserVm.uiState.value.paneTabIds)
        assertTrue("The replaced tab stays open", browserVm.uiState.value.tabs.any { it.id == reddit })
        composeRule.onNodeWithTag("tab_bar_item_$reddit").assertIsDisplayed()
        composeRule.onNodeWithTag("pane_drop_highlight").assertDoesNotExist()
    }

    @Test
    fun aQuickSwipeOnARailTab_isNotADrag() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        launch()
        val before = browserVm.uiState.value.layout
        val bottomOfYouTube = pointInPane(news, paneIndex = 1, fractionX = 0.5f, fractionY = 0.9f)

        composeRule.onNodeWithTag("tab_bar_item_$news").performTouchInput {
            down(center)
            moveTo(center + Offset(60f, 0f))
            moveTo(bottomOfYouTube)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(before, browserVm.uiState.value.layout)
        composeRule.onNodeWithTag("pane_drop_highlight").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFullLayout_offersOnlyTheCentreOfAPane() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        browserVm.setLayoutMode(LayoutMode.GRID_2X2)
        val shown = browserVm.uiState.value.activeTabId!!
        val extra = browserVm.openNewTab("https://five.example/")
        browserVm.assignTabToPane(browserVm.uiState.value.activePaneIndex, shown)
        launch()
        assertEquals(4, browserVm.uiState.value.paneCount)
        assertFalse(extra in browserVm.uiState.value.paneTabIds)
        val edge = pointInPane(extra, paneIndex = 3, fractionX = 0.97f, fractionY = 0.5f)

        composeRule.onNodeWithTag("tab_bar_item_$extra").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
            moveTo(edge)
        }
        composeRule.onNodeWithContentDescription("Show here, pane 4").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_bar_item_$extra").performMouseInput { release() }
        composeRule.waitForIdle()

        assertEquals(4, browserVm.uiState.value.paneCount)
        assertEquals(extra, browserVm.uiState.value.paneTabIds[3])
        assertTrue(browserVm.uiState.value.tabs.any { it.id == news })
    }

    @Test
    fun splitMenu_closesTheFocusedPaneOfACustomLayout() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        browserVm.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM)
        launch()

        // Three panes are none of the presets, and there is no single divider to size
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("split_preset_50").assertDoesNotExist()
        composeRule.onNodeWithTag("split_close_pane").performClick()

        assertEquals("The focused pane (the dropped tab) is gone", listOf(reddit, youtube), browserVm.uiState.value.paneTabIds)
        assertEquals(3, browserVm.uiState.value.tabs.size)
        composeRule.onNodeWithTag("browser_pane_2").assertDoesNotExist()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
    }

    @Test
    fun pageFullscreen_fromASplitFillsTheWindowAndComesBackToTheSameSplit() {
        redditBesideYouTubeWithNewsInTheRail()
        browserVm.setSplitPreset(0.7f)
        launch()
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        val layout = browserVm.uiState.value.layout
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val redditPane = composeRule.onNodeWithTag("browser_pane_0").getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("tab_bar_item_$youtube").performClick()
        assertEquals(1, browserVm.uiState.value.activePaneIndex)

        // The video in the left pane goes fullscreen
        composeRule.runOnUiThread { engine.sessions.getValue(reddit).simulateFullScreen(true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_rail").assertDoesNotExist()
        composeRule.onNodeWithTag("omnibox_input").assertDoesNotExist()
        composeRule.onNodeWithTag("split_button").assertDoesNotExist()
        composeRule.onNodeWithTag("split_divider_vertical").assertDoesNotExist()
        composeRule.onNodeWithTag("browser_pane_1").assertDoesNotExist()
        composeRule.onNodeWithText("Viewport:https://youtube.com/").assertDoesNotExist()
        val fullscreenPane = composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(window.width, fullscreenPane.width)
        assertEquals(window.height, fullscreenPane.height)
        composeRule.onNodeWithText("Viewport:https://reddit.com/").assertIsDisplayed()
        assertFalse(
            "The other page waits behind it, paused (its view is kept, so it comes back at once)",
            engine.sessions.getValue(youtube).visibleState
        )

        // Back leaves fullscreen: the page is asked to, and the window is as it was
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, engine.sessions.getValue(reddit).exitFullScreenCount)
        assertNull(browserVm.uiState.value.fullscreenTabId)
        assertEquals("Same layout, same sizes", layout, browserVm.uiState.value.layout)
        assertEquals("Same focus", youtube, browserVm.uiState.value.activeTabId)
        composeRule.onNodeWithTag("tab_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("omnibox_input").assertIsDisplayed()
        composeRule.onNodeWithTag("split_divider_vertical").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
        composeRule.onNodeWithText("Viewport:https://youtube.com/").assertIsDisplayed()
        assertTrue("…and runs again", engine.sessions.getValue(youtube).visibleState)
        assertEquals(redditPane, composeRule.onNodeWithTag("browser_pane_0").getUnclippedBoundsInRoot())

        // The page leaving by itself (Esc, its own button) does the same
        composeRule.runOnUiThread { engine.sessions.getValue(youtube).simulateFullScreen(true) }
        composeRule.onNodeWithTag("tab_rail").assertDoesNotExist()
        composeRule.onNodeWithText("Viewport:https://reddit.com/").assertDoesNotExist()
        composeRule.runOnUiThread { engine.sessions.getValue(youtube).simulateFullScreen(false) }
        composeRule.onNodeWithTag("tab_rail").assertIsDisplayed()
        composeRule.onNodeWithText("Viewport:https://reddit.com/").assertIsDisplayed()
    }

    @Test
    fun settings_systemColorsSwitchSetsTheAccentsAside() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()

        composeRule.onNodeWithTag("setting_system_colors").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("accent_maroon").assertIsEnabled().assertIsSelected()

        composeRule.onNodeWithTag("setting_system_colors").performClick()
        assertTrue(browserVm.uiState.value.settings.useSystemColors)
        composeRule.onNodeWithTag("setting_system_colors").assertIsOn()
        // Shown, but not choosable while the wallpaper's colours are used; the choice is kept
        composeRule.onNodeWithTag("accent_teal").assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(AccentColor.MAROON, browserVm.uiState.value.settings.accent)
        composeRule.onNodeWithTag("accent_maroon").assertIsSelected()

        composeRule.onNodeWithTag("setting_system_colors").performClick()
        assertFalse(browserVm.uiState.value.settings.useSystemColors)
        composeRule.onNodeWithTag("accent_teal").assertIsEnabled().performClick()
        assertEquals(AccentColor.TEAL, browserVm.uiState.value.settings.accent)
    }

    @Test
    fun settings_pageRenderingIsChosenUnderAppearance() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performClick()

        composeRule.onNodeWithTag("page_rendering_standard").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("page_rendering_direct").performScrollTo().performClick()

        assertEquals(PageRendering.DIRECT, browserVm.uiState.value.settings.pageRendering)
        composeRule.onNodeWithTag("page_rendering_direct").assertIsSelected()

        composeRule.onNodeWithTag("page_rendering_legacy").performScrollTo().performClick()
        assertEquals(PageRendering.LEGACY, browserVm.uiState.value.settings.pageRendering)
    }

    @Test
    fun directRendering_aPageUnderAFullscreenPageGivesUpItsView() {
        redditBesideYouTubeWithNewsInTheRail()
        browserVm.setPageRendering(PageRendering.DIRECT)
        var youtubeViews = 0
        var youtubeViewsDisposed = 0
        launch { tab, modifier ->
            if (tab.url == "https://youtube.com/") {
                DisposableEffect(Unit) {
                    youtubeViews++
                    onDispose { youtubeViewsDisposed++ }
                }
            }
            Box(modifier = modifier) { Text(text = "Viewport:${tab.url}") }
        }
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        composeRule.onNodeWithText("Viewport:https://reddit.com/").assertIsDisplayed()
        composeRule.onNodeWithText("Viewport:https://youtube.com/").assertIsDisplayed()
        assertEquals(1, youtubeViews)

        // Both pages lie behind the window, where this app does not decide which is on top
        composeRule.runOnUiThread { engine.sessions.getValue(reddit).simulateFullScreen(true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Viewport:https://reddit.com/").assertIsDisplayed()
        assertEquals("The covered page's view is gone", 1, youtubeViewsDisposed)
        assertFalse("Its page is kept, paused", engine.sessions.getValue(youtube).isClosed)
        assertFalse(engine.sessions.getValue(youtube).visibleState)

        composeRule.runOnUiThread { engine.sessions.getValue(reddit).simulateFullScreen(false) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Viewport:https://youtube.com/").assertIsDisplayed()
        assertEquals("And back when the fullscreen page leaves", 2, youtubeViews)
        assertTrue("…running again", engine.sessions.getValue(youtube).visibleState)
    }

    @Test
    fun standardRendering_aPageUnderAFullscreenPageKeepsItsView() {
        redditBesideYouTubeWithNewsInTheRail()
        var youtubeViewsDisposed = 0
        launch { tab, modifier ->
            if (tab.url == "https://youtube.com/") {
                DisposableEffect(Unit) { onDispose { youtubeViewsDisposed++ } }
            }
            Box(modifier = modifier) { Text(text = "Viewport:${tab.url}") }
        }
        val reddit = browserVm.uiState.value.paneTabIds.first()

        composeRule.runOnUiThread { engine.sessions.getValue(reddit).simulateFullScreen(true) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { engine.sessions.getValue(reddit).simulateFullScreen(false) }
        composeRule.waitForIdle()

        assertEquals("In the window, the fullscreen pane simply covers it", 0, youtubeViewsDisposed)
    }

    @Test
    fun layoutChanges_keepTheWebViewOfEveryTabThatStaysOnScreen() {
        val news = redditBesideYouTubeWithNewsInTheRail()
        val (reddit, youtube) = browserVm.uiState.value.paneTabIds
        // Stands in for a GeckoView: created once per tab shown, disposed when its tab leaves
        val created = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        launch { tab, modifier ->
            DisposableEffect(Unit) {
                created += tab.id
                onDispose { disposed += tab.id }
            }
            Box(modifier = modifier) { Text(text = "Viewport:${tab.url}") }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(reddit, youtube), created)

        fun change(action: () -> Unit) {
            composeRule.runOnUiThread(action)
            composeRule.waitForIdle()
        }

        // A new pane, a resize, a move to another pane, a swap, a preset and fullscreen
        change { browserVm.dropTabOnPane(news, targetPaneIndex = 1, zone = DropZone.BOTTOM) }
        change { browserVm.resizeSplit("", 0.2f) }
        change { browserVm.dropTabOnPane(reddit, targetPaneIndex = 2, zone = DropZone.RIGHT) }
        change { browserVm.swapPanes(0, 2) }
        change { browserVm.setLayoutMode(LayoutMode.GRID_2X2) }
        change { engine.sessions.getValue(youtube).simulateFullScreen(true) }
        change { engine.sessions.getValue(youtube).simulateFullScreen(false) }
        change { browserVm.closePane(3) }

        assertEquals("Each tab got one view, and kept it", listOf(reddit, youtube, news), created)
        assertTrue("No view was thrown away: $disposed", disposed.isEmpty())

        // Only a tab that leaves the screen gives its view up
        change { browserVm.closePane(browserVm.uiState.value.paneIndexOf(news)) }
        assertEquals(listOf(news), disposed)
    }
}
