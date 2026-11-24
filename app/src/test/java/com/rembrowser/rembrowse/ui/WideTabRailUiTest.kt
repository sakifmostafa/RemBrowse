package com.rembrowser.rembrowse.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.ui.theme.RemDimens
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The tab rail expanded, Material 3 Expressive's wide navigation rail: the menu button at its
 * top, the command palette and Settings expand and collapse it; on a laptop-sized window it sits
 * beside the page, which makes room, and the choice is kept; on a narrower one it opens over the
 * page and a click outside it, Esc or Back closes it. Its tiles keep their menus, middle click
 * and drag, with each title beside its icon.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class WideTabRailUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val extensionManager = FakeExtensionManager()
    private val chrome = BrowserChromeState()
    private lateinit var repository: FakeBrowserStateRepository
    private lateinit var browserVm: BrowserViewModel
    private var backDispatcher: OnBackPressedDispatcher? = null
    private var inputModes: InputModeManager? = null

    /**
     * The input mode the screen's composables see, when set; otherwise the window's. Robolectric's
     * window can't be put in touch mode, so this stands in for a click or a tap.
     */
    private val shownInputMode = mutableStateOf<InputMode?>(null)

    /** The theme's outline colour, as drawn. */
    private var outline = Color.Unspecified

    /**
     * Where each tab's page starts in the window, in px, as laid out: read without the semantics
     * tree, from which the page is left out while the rail is open over it.
     */
    private val viewportLeft = mutableMapOf<String, Float>()

    private val gmail = BookmarkItem(id = "mail", title = "Gmail", url = "https://mail.google.com/", createdAtMillis = 0L)
    private val tabs = listOf(
        BrowserTab(id = "github", url = "https://github.com/rembrowse/rembrowse", title = "GitHub"),
        BrowserTab(id = "news", url = "https://news.example.com/", title = "The Example Times"),
        BrowserTab(id = "chat", url = "https://chat.example/", title = "Chat")
    )

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(settings: BrowserSettings = BrowserSettings()) {
        repository = FakeBrowserStateRepository(
            PersistedBrowserState(tabs = tabs, paneTabIds = listOf("github"), bookmarks = listOf(gmail), settings = settings)
        )
        browserVm = BrowserViewModel(engine, repository)
        val extVm = ExtensionsViewModel(extensionManager, FakeAmoApiClient(), uiScope)
        val downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(emptyList()),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val window = LocalInputModeManager.current
            inputModes = window
            val shown = remember {
                object : InputModeManager {
                    override val inputMode: InputMode get() = shownInputMode.value ?: window.inputMode
                    override fun requestInputMode(inputMode: InputMode): Boolean = window.requestInputMode(inputMode)
                }
            }
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalInputModeManager provides shown) {
                RemBrowseTheme {
                    outline = MaterialTheme.colorScheme.outline
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier ->
                            Box(modifier.onGloballyPositioned { viewportLeft[tab.id] = it.positionInRoot().x }) {
                                Text("Viewport:${tab.url}")
                            }
                        },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun node(tag: String) = composeRule.onNodeWithTag(tag)
    private fun title(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun railWidth(): Dp = node("tab_rail").getUnclippedBoundsInRoot().width
    private fun paneLeft(): Dp = node("browser_pane_0").getUnclippedBoundsInRoot().left
    private val settings get() = browserVm.uiState.value.settings

    private fun SemanticsNodeInteraction.mouseClick(button: MouseButton = MouseButton.Primary) {
        performMouseInput {
            moveTo(center)
            press(button)
            release(button)
        }
        composeRule.waitForIdle()
    }

    private fun toggle() {
        node("tab_rail_toggle").performClick()
        composeRule.waitForIdle()
    }

    private fun assertCollapsed() {
        assertEquals(RemDimens.RailWidth, railWidth())
        title("rail_tab_title_github").assertDoesNotExist()
        title("tab_switcher_title").assertDoesNotExist()
        node("tab_rail_toggle").assertContentDescriptionEquals("Expand tab rail")
    }

    private fun assertExpandedWithTitles() {
        assertEquals(RemDimens.RailExpandedWidth, railWidth())
        title("rail_tab_title_github").assertIsDisplayed().assertTextEquals("GitHub")
        title("rail_tab_title_news").assertIsDisplayed().assertTextEquals("The Example Times")
        title("pinned_site_title_mail").assertIsDisplayed().assertTextEquals("Gmail")
        title("tab_switcher_title").assertIsDisplayed().assertTextEquals("Tab switcher")
        title("new_tab_title").assertIsDisplayed().assertTextEquals("New tab")
        node("tab_rail_toggle").assertContentDescriptionEquals("Collapse tab rail")
        // The titles have the room: well past the icon, and inside the rail
        val github = title("rail_tab_title_github").getUnclippedBoundsInRoot()
        assertTrue(github.left >= RemDimens.RailWidth - 16.dp && github.right <= RemDimens.RailExpandedWidth)
    }

    // --- beside the page, on a laptop-sized window ---

    @Test
    fun menuButton_expandsTheRailBesideThePage_andCollapsesIt_keepingTheChoice() {
        launch()
        assertCollapsed()
        val collapsedPaneLeft = paneLeft()

        toggle()

        assertExpandedWithTitles()
        assertTrue("The page made room", paneLeft() >= RemDimens.RailExpandedWidth)
        node("tab_rail_scrim").assertDoesNotExist()
        assertTrue(settings.tabRailExpanded)
        assertTrue("Saved", repository.storedState.settings.tabRailExpanded)

        toggle()

        assertCollapsed()
        assertEquals(collapsedPaneLeft, paneLeft())
        assertFalse(settings.tabRailExpanded)
        assertFalse(repository.storedState.settings.tabRailExpanded)
    }

    @Test
    fun anExpandedRail_isExpandedAgainAtTheNextStart() {
        launch(BrowserSettings(tabRailExpanded = true))
        assertExpandedWithTitles()
        assertTrue(paneLeft() >= RemDimens.RailExpandedWidth)
    }

    @Test
    fun commandPalette_expandsAndCollapsesTheRail() {
        launch()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        node("command_palette_input").performTextInput("rail")
        node("palette_tab_rail").assertTextContains("Expand tab rail").performClick()
        composeRule.waitForIdle()
        assertExpandedWithTitles()
        assertTrue(settings.tabRailExpanded)

        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        node("command_palette_input").performTextInput("rail")
        node("palette_tab_rail").assertTextContains("Collapse tab rail").performClick()
        composeRule.waitForIdle()
        assertCollapsed()
        assertFalse(settings.tabRailExpanded)
    }

    @Test
    fun settingsSwitch_expandsAndCollapsesTheRail() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TABS) }
        composeRule.waitForIdle()

        node("setting_tab_rail_expanded").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertExpandedWithTitles()
        assertTrue(repository.storedState.settings.tabRailExpanded)

        node("setting_tab_rail_expanded").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertCollapsed()
    }

    @Test
    fun expandedRail_extensionButtonsKeepTheirBadgeAndShowTheirName() {
        extensionManager.installedExtensions.value = listOf(
            InstalledExtension(
                id = "ublock@example",
                name = "uBlock Origin",
                version = "1.0",
                description = "",
                enabled = true,
                hasBrowserAction = true,
                badgeText = "12"
            )
        )
        launch(BrowserSettings(tabRailExpanded = true))

        node("rail_extension_ublock@example").assertIsDisplayed().assertContentDescriptionEquals("uBlock Origin (12)")
        title("rail_extension_title_ublock@example").assertIsDisplayed().assertTextEquals("uBlock Origin")
    }

    @Test
    fun expandedRail_rightClickMenuAndMiddleClickStillWork() {
        launch(BrowserSettings(tabRailExpanded = true))

        // Right-click anywhere on the wide tile, here over its title
        node("tab_bar_item_chat").performMouseInput {
            moveTo(centerRight - Offset(40f, 0f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        composeRule.waitForIdle()
        node("tab_bar_close_chat").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("github", "news"), browserVm.uiState.value.tabs.map { it.id })

        node("tab_bar_item_news").mouseClick(MouseButton.Tertiary)
        assertEquals(listOf("github"), browserVm.uiState.value.tabs.map { it.id })
        // Still expanded: closing tabs keeps the rail as it was
        assertEquals(RemDimens.RailExpandedWidth, railWidth())
    }

    /** A point of pane [paneIndex], in the coordinates of the rail tile of [tabId]. */
    private fun pointInPane(tabId: String, paneIndex: Int, fractionX: Float, fractionY: Float): Offset {
        val tile = node("tab_bar_item_$tabId").getUnclippedBoundsInRoot()
        // Under the rail open over the page, the page is left out of the merged tree
        val pane = composeRule.onNodeWithTag("browser_pane_$paneIndex", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        return Offset(
            (pane.left + pane.width * fractionX - tile.left).value * density,
            (pane.top + pane.height * fractionY - tile.top).value * density
        )
    }

    /** Drags the rail tile of [tabId] with the mouse onto the right edge of the only pane. */
    private fun dragOntoThePanesRightEdge(tabId: String) {
        val rightEdge = pointInPane(tabId, paneIndex = 0, fractionX = 0.95f, fractionY = 0.5f)
        node("tab_bar_item_$tabId").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
            moveTo(rightEdge)
        }
        composeRule.onNodeWithTag("tab_drag_ghost", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun expandedRail_tabsStillDragOntoThePanes() {
        launch(BrowserSettings(tabRailExpanded = true))

        dragOntoThePanesRightEdge("news")
        node("tab_bar_item_news").performMouseInput { release() }
        composeRule.waitForIdle()

        assertEquals(listOf("github", "news"), browserVm.uiState.value.paneTabIds)
        assertEquals(RemDimens.RailExpandedWidth, railWidth())
    }

    // --- over the page, on a narrower window ---

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_theMenuButtonOpensTheRailOverThePage_andAClickOutsideClosesIt() {
        launch()
        val collapsedPaneLeft = viewportLeft.getValue("github")

        toggle()

        assertExpandedWithTitles()
        assertEquals("The page stays where it was, under the rail", collapsedPaneLeft, viewportLeft.getValue("github"))
        node("tab_rail_scrim").assertIsDisplayed()
        assertTrue(chrome.tabRailOverlayOpen)
        assertFalse("Not kept: it is only open for a moment", settings.tabRailExpanded)

        // A click on the dimmed page, right of the rail, closes it and reaches nothing under it
        node("tab_rail_scrim").performClick()
        composeRule.waitForIdle()

        assertCollapsed()
        node("tab_rail_scrim").assertDoesNotExist()
        assertFalse(chrome.tabRailOverlayOpen)
        assertEquals("github", browserVm.uiState.value.activeTabId)
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_escClosesTheRailOverThePage() {
        launch()
        // Opened from the keyboard, the rail takes the keyboard focus, as a dialog would
        composeRule.runOnIdle { inputModes!!.requestInputMode(InputMode.Keyboard) }
        composeRule.runOnUiThread { chrome.tabRailOverlayOpen = true }
        composeRule.waitForIdle()
        node("tab_rail_toggle").assertIsFocused()

        node("tab_rail_toggle").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        assertCollapsed()
        assertFalse(chrome.tabRailOverlayOpen)
        // The focus stays on the menu button, now on the collapsed rail
        node("tab_rail_toggle").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_backClosesTheRailOverThePage_beforeAnythingElse() {
        launch()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        toggle()
        assertTrue(chrome.tabRailOverlayOpen)

        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()

        assertCollapsed()
        assertFalse(chrome.tabRailOverlayOpen)
        assertTrue("The find bar, behind the rail, stays for the next Back", browserVm.findInPage.value != null)
        assertEquals(0, engine.sessions.getValue("github").goBackCount)
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_choosingATabInTheRailOverThePage_showsItAndClosesTheRail() {
        launch()
        toggle()

        node("tab_bar_item_news").performClick()
        composeRule.waitForIdle()

        assertEquals("news", browserVm.uiState.value.activeTabId)
        assertCollapsed()
        assertFalse(chrome.tabRailOverlayOpen)
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_aTabDraggedOutOfTheRailOverThePage_landsOnThePane() {
        launch()
        toggle()

        dragOntoThePanesRightEdge("news")
        // Picked up, the rail steps back to its icons so the whole pane shows
        assertFalse(chrome.tabRailOverlayOpen)
        assertEquals(RemDimens.RailWidth, railWidth())
        node("tab_rail_scrim").assertDoesNotExist()

        node("tab_bar_item_news").performMouseInput { release() }
        composeRule.waitForIdle()

        assertEquals(listOf("github", "news"), browserVm.uiState.value.paneTabIds)
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_rightClickMenuWorksInTheRailOverThePage() {
        launch()
        toggle()

        node("tab_bar_item_chat").mouseClick(MouseButton.Secondary)
        node("tab_bar_close_chat").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("github", "news"), browserVm.uiState.value.tabs.map { it.id })
        assertTrue("Closing a tab leaves it open", chrome.tabRailOverlayOpen)
    }

    /** The node with the keyboard focus, which must be one of the rail's. */
    private fun assertFocusInTheRail(step: String) {
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("tab_rail")))
            .assertExists("$step: the focus is in the rail")
    }

    private fun pressOnTheFocused(key: Key, shift: Boolean = false) {
        composeRule.onNode(isFocused()).performKeyInput {
            if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
        }
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_tabAndShiftTabGoRoundTheRailOverThePage_neverIntoThePage() {
        launch()
        composeRule.runOnIdle { inputModes!!.requestInputMode(InputMode.Keyboard) }
        composeRule.runOnUiThread { chrome.tabRailOverlayOpen = true }
        composeRule.waitForIdle()
        node("tab_rail_toggle").assertIsFocused()

        // Menu button, tab switcher, the pinned site, three tabs, new tab: twice round and more
        repeat(16) { step ->
            pressOnTheFocused(Key.Tab)
            assertFocusInTheRail("Tab ${step + 1}")
        }
        // Round from the last place to the first, and back
        node("new_tab_button").requestFocus()
        pressOnTheFocused(Key.Tab)
        node("tab_rail_toggle").assertIsFocused()
        pressOnTheFocused(Key.Tab, shift = true)
        node("new_tab_button").assertIsFocused()
        repeat(10) { step ->
            pressOnTheFocused(Key.Tab, shift = true)
            assertFocusInTheRail("Shift+Tab ${step + 1}")
        }
        // The arrows stop at the rail's side, rather than going on to the page beside it
        node("tab_bar_item_news").requestFocus()
        pressOnTheFocused(Key.DirectionRight)
        assertFocusInTheRail("Right arrow")
        assertTrue(chrome.tabRailOverlayOpen)

        // Closed, Tab goes on to the toolbar again
        composeRule.runOnUiThread { chrome.tabRailOverlayOpen = false }
        composeRule.waitForIdle()
        node("new_tab_button").requestFocus()
        pressOnTheFocused(Key.Tab)
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("toolbar"))).assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_openedWithAPointer_theFocusLeavesThePage_andTabStartsInTheRail() {
        launch()
        // In touch mode, as a click or a tap leaves the window
        composeRule.runOnIdle { shownInputMode.value = InputMode.Touch }
        // Typing an address, then the menu button clicked
        val addressBar = composeRule.onNode(hasContentDescription("Address and search bar") and hasSetTextAction())
        addressBar.performClick()
        addressBar.assertIsFocused()
        toggle()
        assertTrue(chrome.tabRailOverlayOpen)
        // The focus has left the address bar and the page under the rail
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("toolbar")), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("browser_pane_0")), useUnmergedTree = true).assertDoesNotExist()

        // The keyboard's first Tab lands in the rail
        composeRule.runOnIdle { shownInputMode.value = null }
        composeRule.runOnIdle { inputModes!!.requestInputMode(InputMode.Keyboard) }
        // The window's root (a tooltip may be up as well)
        composeRule.onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }
        composeRule.waitForIdle()
        assertFocusInTheRail("Tab")
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    fun narrowWindow_screenReadersDontReachThePageUnderTheRail() {
        launch()
        node("toolbar").assertExists()
        node("browser_pane_0").assertExists()

        toggle()

        // The toolbar and the pages are out of the semantics tree; the rail and the scrim are there
        node("toolbar").assertDoesNotExist()
        node("browser_pane_0").assertDoesNotExist()
        composeRule.onNodeWithText("Viewport:", substring = true).assertDoesNotExist()
        node("tab_bar_item_github").assertExists()
        node("tab_rail_scrim").assertContentDescriptionEquals("Close the tab rail").assertHasClickAction()

        node("tab_rail_scrim").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertFalse(chrome.tabRailOverlayOpen)
        node("toolbar").assertExists()
        node("browser_pane_0").assertExists()
    }

    /** In the dark theme the rail over the page has a line along its page side, the outline colour. */
    @Test
    @Config(qualifiers = "w800dp-h600dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowWindow_inTheDarkTheme_theRailOverThePageHasAnEdge() {
        launch()
        toggle()
        composeRule.waitForIdle()

        val rail = node("tab_rail").getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        // The last pixel of the rail, level with the toolbar: under the scrim the toolbar is the
        // rail's own colour, so this is where the edge was lost
        val edge = Color(screen.getPixel((rail.right.value * density).toInt() - 1, (26 * density).toInt()))
        val expected = outline
        assertTrue(
            "The edge, in the outline colour: $edge",
            abs(edge.red - expected.red) < 0.03f && abs(edge.green - expected.green) < 0.03f && abs(edge.blue - expected.blue) < 0.03f
        )
    }

    // A phone held sideways: the rail over the page, the tab switcher scrolling with the list
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun phoneHeldSideways_theRailOverThePageShowsTheTitlesAndTheActiveTab() {
        launch()
        toggle()

        assertEquals(RemDimens.RailExpandedWidth, railWidth())
        node("tab_rail_scrim").assertIsDisplayed()
        title("rail_tab_title_github").assertIsDisplayed().assertTextEquals("GitHub")
        val list = node("tab_rail_list").getUnclippedBoundsInRoot()
        val active = node("tab_bar_item_github").getUnclippedBoundsInRoot()
        assertTrue(active.top >= list.top && active.bottom <= list.bottom)
    }
}
