package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PxRect
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.ScreenCaptureState
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The screenshot overlay (Ctrl+Alt+S) on a laptop-sized window with a keyboard: what it takes for
 * a drag, a click and Enter, how it is cancelled, the notice afterwards, and where it is offered.
 * The copy of the window itself is the activity's ([com.rembrowser.rembrowse.engine.WindowScreenshot]);
 * here a fake records what it was asked for.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi-keysexposed-qwerty")
class ScreenCaptureUiTest {

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
    private val chrome = BrowserChromeState()
    private val capture: ScreenCaptureState get() = chrome.screenCapture

    /** What the screen asked to be copied, in window pixels. */
    private val requests = mutableListOf<PxRect>()
    private var copies = true

    /** The tabs whose whole page the screen asked to be copied. */
    private val wholePages = mutableListOf<String>()

    /** The activity's part for a whole page: by default copied at once, 1280 by 24,000 pixels. */
    private var wholePageCapturer: suspend (String) -> ScreenshotResult = { tabId ->
        wholePages += tabId
        ScreenshotResult.Copied(Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888).asImageBitmap(), 1280, 24_000, wholePage = true)
    }

    /** How many times the notice's Save asked for the whole page to be saved. */
    private var saves = 0
    private var saver: suspend () -> ScreenshotResult = {
        saves++
        ScreenshotResult.Saved("Screenshot 2026-11-25 at 14.03.22.png")
    }

    /** The activity's part: by default the screenshot is copied at once. */
    private var capturer: suspend (PxRect) -> ScreenshotResult = { area ->
        requests += area
        if (copies) {
            ScreenshotResult.Copied(Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).asImageBitmap(), area.width, area.height)
        } else {
            ScreenshotResult.Failed
        }
    }

    @After
    fun tearDown() {
        uiScope.cancel()
        setAnimatorScale(1f)
    }

    /** Android's animator duration scale; 0 is Remove animations. */
    private fun setAnimatorScale(scale: Float) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private fun launch(viewport: @Composable (BrowserTab, Modifier) -> Unit = { _, modifier -> Box(modifier) }) {
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
                        onCaptureScreen = { area -> capturer(area) },
                        onCaptureWholePage = { tabId -> wholePageCapturer(tabId) },
                        onSaveScreenshot = { saver() },
                        paneViewport = viewport,
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun loadPage(url: String = "https://example.com/", title: String = "Example") {
        loadPageIn(browserVm.uiState.value.activePaneIndex, url, title)
    }

    private fun loadPageIn(pane: Int, url: String = "https://example.com/", title: String = "Example") {
        val tab = browserVm.uiState.value.paneTabIds[pane]
        composeRule.runOnUiThread {
            browserVm.navigatePane(pane, url)
            engine.sessions.getValue(tab).simulatePageLoad(url, title, canGoBack = false)
        }
        composeRule.waitForIdle()
    }

    private val wholePageButton get() = composeRule.onNodeWithTag("screen_capture_whole_page")

    private fun startCapture() {
        composeRule.runOnUiThread { assertTrue(capture.start()) }
        composeRule.waitForIdle()
    }

    private val overlay get() = composeRule.onNodeWithTag("screen_capture_overlay")

    /** Where the overlay's top-left corner is in the window: what its own positions are offset by. */
    private fun overlayOrigin(): Offset = overlay.fetchSemanticsNode().positionInWindow

    private fun windowBoundsOf(tag: String): PxRect {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return PxRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }

    @Test
    fun theOverlay_dimsTheWindow_saysWhatToDo_andTakesTheKeyboard() {
        launch()
        startCapture()

        overlay.assertIsDisplayed().assertIsFocused()
        overlay.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Take screenshot"))
        composeRule.onNodeWithText("Drag to capture an area · Click a page to capture all of it · Esc to cancel")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel screenshot (Esc)").assertIsDisplayed()
        // No size until something is dragged
        composeRule.onNodeWithTag("screen_capture_size").assertDoesNotExist()
        assertEquals(ScreenCaptureState.Phase.SELECTING, capture.phase)
    }

    @Test
    fun esc_cancels_withoutTakingAnything() {
        launch()
        startCapture()

        overlay.performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        overlay.assertDoesNotExist()
        assertEquals(emptyList<PxRect>(), requests)
        assertEquals(ScreenCaptureState.Phase.IDLE, capture.phase)
    }

    @Test
    fun aRightClick_orTheCloseButton_cancels() {
        launch()
        startCapture()
        overlay.performMouseInput {
            moveTo(Offset(400f, 400f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        composeRule.waitForIdle()
        overlay.assertDoesNotExist()

        startCapture()
        composeRule.onNodeWithTag("screen_capture_cancel").performClick()
        composeRule.waitForIdle()
        overlay.assertDoesNotExist()
        assertEquals(emptyList<PxRect>(), requests)
    }

    @Test
    fun aDrag_showsItsSizeAsItGoes_andTakesTheRectangle_thenConfirmsWithAPicture() {
        launch()
        startCapture()
        val origin = overlayOrigin()

        overlay.performMouseInput {
            moveTo(Offset(200f, 200f))
            press()
            moveTo(Offset(350f, 300f))
            moveTo(Offset(500f, 400f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_capture_size")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Selection, 300 by 200 pixels")))

        overlay.performMouseInput { release() }
        composeRule.waitForIdle()

        val ox = origin.x.roundToInt()
        val oy = origin.y.roundToInt()
        assertEquals(listOf(PxRect(200 + ox, 200 + oy, 500 + ox, 400 + oy)), requests)
        overlay.assertDoesNotExist()
        composeRule.onNodeWithTag("screenshot_notice").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshot copied").assertIsDisplayed()
        composeRule.onNodeWithText("300 × 200 px").assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_thumbnail", useUnmergedTree = true).assertIsDisplayed()

        // It leaves by itself
        composeRule.mainClock.advanceTimeBy(4_500)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screenshot_notice").assertDoesNotExist()
    }

    @Test
    fun aFingerDrag_takesTheRectangleToo() {
        launch()
        startCapture()
        val origin = overlayOrigin()

        overlay.performTouchInput {
            down(Offset(100f, 150f))
            moveTo(Offset(200f, 250f))
            moveTo(Offset(420f, 330f))
            up()
        }
        composeRule.waitForIdle()

        val ox = origin.x.roundToInt()
        val oy = origin.y.roundToInt()
        assertEquals(listOf(PxRect(100 + ox, 150 + oy, 420 + ox, 330 + oy)), requests)
    }

    @Test
    fun aTinyDrag_takesNothing_andTheOverlayStaysForAnotherTry() {
        launch()
        startCapture()

        overlay.performMouseInput {
            moveTo(Offset(300f, 300f))
            press()
            moveTo(Offset(305f, 400f))
            release()
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<PxRect>(), requests)
        overlay.assertIsDisplayed()
        composeRule.onNodeWithTag("screen_capture_size").assertDoesNotExist()
    }

    @Test
    fun aClick_takesThePaneUnderThePointer_andOverTheToolbarTheWholeWindow() {
        launch()
        composeRule.runOnUiThread { browserVm.setLayoutMode(LayoutMode.SPLIT_1X2) }
        composeRule.waitForIdle()
        val rightPane = windowBoundsOf("browser_pane_1")

        startCapture()
        val origin = overlayOrigin()
        overlay.performMouseInput {
            click(Offset(rightPane.left + 100f - origin.x, rightPane.top + 100f - origin.y))
        }
        composeRule.waitForIdle()
        assertEquals(listOf(rightPane), requests)

        // The toolbar is not a pane: the whole window
        startCapture()
        val window = windowBoundsOf("screen_capture_overlay")
        overlay.performMouseInput { click(Offset(200f, 26f)) }
        composeRule.waitForIdle()
        assertEquals(window, requests[1])
        assertEquals("The overlay covers the whole window", PxRect(0, 0, 1280, 800), window.offset(-window.left, -window.top))
    }

    @Test
    fun enter_takesTheFocusedPane() {
        launch()
        composeRule.runOnUiThread {
            browserVm.setLayoutMode(LayoutMode.SPLIT_1X2)
            browserVm.focusPane(1)
        }
        composeRule.waitForIdle()
        val focusedPane = windowBoundsOf("browser_pane_1")

        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals(listOf(focusedPane), requests)
    }

    @Test
    fun enterReadByTheActivity_takesTheFocusedPaneToo() {
        launch()
        val pane = windowBoundsOf("browser_pane_0")
        startCapture()

        composeRule.runOnUiThread { capture.requestFocusedCapture() }
        composeRule.waitForIdle()

        assertEquals(listOf(pane), requests)
    }

    @Test
    fun withTheTabSwitcherOpen_aClickOnItTakesTheSwitcher() {
        launch()
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        val switcher = windowBoundsOf("tab_switcher")

        startCapture()
        val origin = overlayOrigin()
        overlay.performMouseInput { click(Offset(switcher.left + 40f - origin.x, switcher.top + 40f - origin.y)) }
        composeRule.waitForIdle()

        assertEquals(listOf(switcher), requests)
        assertTrue("Still open after the screenshot", chrome.showTabSwitcher)
    }

    @Test
    fun aScreenReader_canTakeTheFocusedPaneOrTheWindow_orDismissIt() {
        launch()
        val pane = windowBoundsOf("browser_pane_0")
        startCapture()

        val actions = overlay.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Capture the focused pane", "Capture the whole window"), actions.map { it.label })
        composeRule.runOnUiThread { actions.first().action() }
        composeRule.waitForIdle()
        assertEquals(listOf(pane), requests)

        startCapture()
        overlay.performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()
        overlay.assertDoesNotExist()
        assertEquals(1, requests.size)
    }

    @Test
    fun aScreenshotThatFails_saysSoPlainly() {
        copies = false
        launch()
        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't take the screenshot").assertIsDisplayed()
        composeRule.onNodeWithText("Try again with Ctrl+Alt+S").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Error", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_notice_dismiss").performClick()
        composeRule.onNodeWithTag("screenshot_notice").assertDoesNotExist()
    }

    @Test
    fun anotherScreenshot_cantStartWhileOneIsUnderWay_andANewOneClearsTheLastNotice() {
        launch()
        startCapture()
        composeRule.runOnUiThread { assertTrue("Already selecting", !capture.start()) }
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screenshot_notice").assertIsDisplayed()

        // The notice must not be in the next screenshot
        startCapture()
        composeRule.onNodeWithTag("screenshot_notice").assertDoesNotExist()
    }

    @Test
    fun theMoreMenu_offersIt_withItsShortcut_onAnyPage() {
        launch()
        // The start page: printing is greyed out, a screenshot is not
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_screenshot").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ctrl+Alt+S").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_screenshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("menu_screenshot").assertDoesNotExist()
        overlay.assertIsDisplayed()
    }

    @Test
    fun theCommandPalette_offersIt() {
        launch()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("screenshot")
        composeRule.onNodeWithTag("palette_screenshot").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("command_palette").assertDoesNotExist()
        overlay.assertIsDisplayed()
    }

    @Test
    fun thePagesRightClickMenu_offersIt() {
        launch()
        loadPage()
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread { engine.sessions.getValue(tab).simulateContextMenu() }

        composeRule.onNodeWithText("Take screenshot").assertIsDisplayed()
        composeRule.onNodeWithTag("page_menu_screenshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("page_context_menu").assertDoesNotExist()
        overlay.assertIsDisplayed()
    }

    @Test
    fun aScreenshotThatTakesTooLong_isCancelled_failsPlainly_andTheNextOneWorks() {
        var cancelled = 0
        capturer = { area ->
            requests += area
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        launch()
        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertEquals(1, requests.size)
        assertEquals(ScreenCaptureState.Phase.CAPTURING, capture.phase)

        composeRule.mainClock.advanceTimeBy(10_500)
        composeRule.waitForIdle()

        assertEquals("Given up on, the copy is cancelled rather than left running", 1, cancelled)
        composeRule.onNodeWithText("Couldn't take the screenshot").assertIsDisplayed()
        assertEquals(ScreenCaptureState.Phase.IDLE, capture.phase)

        // The next one is not held up by it
        capturer = { area ->
            requests += area
            ScreenshotResult.Copied(null, area.width, area.height)
        }
        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Screenshot copied").assertIsDisplayed()
        assertEquals(2, requests.size)
    }

    @Test
    fun theAddressBar_getsTheKeyboardBack_withWhatWasTyped() {
        launch()
        composeRule.runOnUiThread { chrome.focusAddressBar() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("omnibox_input").assertIsFocused().performTextInput("half typed")

        startCapture()
        overlay.assertIsFocused()
        overlay.performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("omnibox_input").assertIsFocused()
        assertEquals("half typed", browserVm.uiState.value.addressBarInput)
        assertTrue(browserVm.uiState.value.isAddressBarFocused)
    }

    @Test
    fun theTabSwitchersSearch_getsTheKeyboardBack_afterAScreenshot() {
        launch()
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("switcher_search").assertIsFocused()

        startCapture()
        overlay.assertIsFocused()
        overlay.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals(1, requests.size)
        composeRule.onNodeWithTag("switcher_search").assertIsFocused()
        assertTrue(chrome.isTabSearchFocused)
    }

    @Test
    fun aPagesOwnView_getsTheKeyboardBack() {
        // Stands in for a page's web view or a terminal: an Android view with the focus
        var page: View? = null
        launch(viewport = { _, modifier ->
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    View(context).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        page = this
                    }
                }
            )
        })
        // A web page, so the pane shows its view
        loadPage()
        composeRule.runOnUiThread { assertTrue(page!!.requestFocus()) }
        composeRule.waitForIdle()

        startCapture()
        overlay.assertIsFocused()
        composeRule.runOnUiThread { assertTrue("The overlay took it", !page!!.isFocused) }
        overlay.performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { assertTrue(page!!.isFocused) }
    }

    @Test
    fun theWholePageButton_isOfferedForAWebPage_notTheStartPage_andTakesTheFocusedPanesPage() {
        launch()
        startCapture()
        wholePageButton.assertDoesNotExist()
        overlay.performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        loadPage()
        val tab = browserVm.uiState.value.activeTabId!!
        startCapture()
        wholePageButton.assertIsDisplayed()
            .assertTextEquals("Whole page")
            .assertHasClickAction()
            .assertHeightIsEqualTo(48.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
        wholePageButton.performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(tab), wholePages)
        assertEquals("Nothing of the window is copied", emptyList<PxRect>(), requests)
        overlay.assertDoesNotExist()
        composeRule.onNodeWithText("Whole page copied").assertIsDisplayed()
        composeRule.onNodeWithText("1280 × 24000 px").assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_notice_save").assertIsDisplayed().assertTextEquals("Save")
    }

    @Test
    fun theWholePageButtonsTooltip_namesItsKey() {
        launch()
        loadPage()
        startCapture()
        wholePageButton.performMouseInput { moveTo(center) }
        // Inside the 1.5s a plain tooltip stays up
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText("Capture the whole page (P)").assertExists()
    }

    @Test
    fun p_takesTheWholePageUnderTheMouse_elseTheFocusedPanes_butNeverAStartPage() {
        launch()
        composeRule.runOnUiThread { browserVm.setLayoutMode(LayoutMode.SPLIT_1X2) }
        composeRule.waitForIdle()
        loadPageIn(0)
        val left = browserVm.uiState.value.paneTabIds[0]
        val right = browserVm.uiState.value.paneTabIds[1]
        val rightPane = windowBoundsOf("browser_pane_1")

        // The right pane shows the start page: under the mouse, P takes nothing
        startCapture()
        var origin = overlayOrigin()
        overlay.performMouseInput { moveTo(Offset(rightPane.left + 100f - origin.x, rightPane.top + 100f - origin.y)) }
        overlay.performKeyInput { pressKey(Key.P) }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), wholePages)
        overlay.assertIsDisplayed()
        overlay.performKeyInput { pressKey(Key.Escape) }
        composeRule.waitForIdle()

        loadPageIn(1, "https://news.example.com/", "News")
        startCapture()
        origin = overlayOrigin()
        overlay.performMouseInput { moveTo(Offset(rightPane.left + 100f - origin.x, rightPane.top + 100f - origin.y)) }
        overlay.performKeyInput { pressKey(Key.P) }
        composeRule.waitForIdle()
        assertEquals("The page under the mouse", listOf(right), wholePages)

        // Over the toolbar, no pane is under the mouse: the focused one
        composeRule.runOnUiThread { browserVm.focusPane(0) }
        startCapture()
        overlay.performMouseInput { moveTo(Offset(200f, 26f)) }
        overlay.performKeyInput { pressKey(Key.P) }
        composeRule.waitForIdle()
        assertEquals(listOf(right, left), wholePages)
    }

    @Test
    fun pReadByTheActivity_takesTheWholePageToo_andAScreenReaderHasItAsAnAction() {
        launch()
        loadPage()
        val tab = browserVm.uiState.value.activeTabId!!
        startCapture()
        val actions = overlay.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(
            listOf("Capture the focused pane", "Capture the whole window", "Capture the whole page"),
            actions.map { it.label }
        )

        composeRule.runOnUiThread { capture.requestWholePageCapture() }
        composeRule.waitForIdle()
        assertEquals(listOf(tab), wholePages)

        startCapture()
        composeRule.runOnUiThread { actions.last().action() }
        composeRule.waitForIdle()
        assertEquals(listOf(tab, tab), wholePages)
    }

    @Test
    fun aPageTooLongToTake_saysSoPlainly() {
        wholePageCapturer = { tabId ->
            wholePages += tabId
            ScreenshotResult.TooLong
        }
        launch()
        loadPage()
        startCapture()
        wholePageButton.performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("This page is too long to capture whole").assertIsDisplayed()
        composeRule.onNodeWithText("Capture part of it with Ctrl+Alt+S").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Error", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_notice_save").assertDoesNotExist()
        assertEquals(ScreenCaptureState.Phase.IDLE, capture.phase)
    }

    @Test
    fun theNoticesSave_putsTheWholePageInDownloads_andSaysWhere() {
        launch()
        loadPage()
        startCapture()
        wholePageButton.performClick()
        composeRule.waitForIdle()

        // A notice with a button stays longer than the 4 seconds of the others
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithTag("screenshot_notice_save").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, saves)
        composeRule.onNodeWithText("Saved to Downloads").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshot 2026-11-25 at 14.03.22.png").assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_notice_save").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(4_500)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screenshot_notice").assertDoesNotExist()
    }

    @Test
    fun aSaveThatFails_saysSo() {
        saver = {
            saves++
            ScreenshotResult.NotSaved
        }
        launch()
        loadPage()
        startCapture()
        wholePageButton.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screenshot_notice_save").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't save the screenshot").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun whileAWholePageIsTaken_theNoticeSaysSo_stillWithAnimationsOff_andItsCancelGivesUpOnIt() {
        // Remove animations: the loading indicator holds still (and the test needn't wait it out)
        setAnimatorScale(0f)
        var cancelled = 0
        wholePageCapturer = { tabId ->
            wholePages += tabId
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        launch()
        loadPage()
        startCapture()
        wholePageButton.performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("screenshot_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Capturing the whole page…").assertIsDisplayed()
        composeRule.onNodeWithTag("loading_indicator_still", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("screenshot_progress_cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(1, cancelled)
        assertEquals(ScreenCaptureState.Phase.IDLE, capture.phase)
        composeRule.onNodeWithTag("screenshot_progress").assertDoesNotExist()
        composeRule.onNodeWithTag("screenshot_notice").assertDoesNotExist()
    }

    @Test
    fun theCommandPalette_offersTheWholePage_onlyWithAWebPageInTheFocusedPane() {
        launch()
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("whole")
        composeRule.onNodeWithTag("palette_whole_page_screenshot").assertDoesNotExist()
        composeRule.runOnUiThread { chrome.showCommandPalette = false }
        composeRule.waitForIdle()

        loadPage()
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        composeRule.onNodeWithTag("command_palette_input").performTextInput("whole")
        composeRule.onNodeWithText("Screenshot whole page").assertIsDisplayed()
        composeRule.onNodeWithTag("palette_whole_page_screenshot").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("command_palette").assertDoesNotExist()
        // Straight to the clipboard, without the overlay
        overlay.assertDoesNotExist()
        assertEquals(listOf(tab), wholePages)
        composeRule.onNodeWithText("Whole page copied").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun onAPhone_theWholePageButtonGoesUnderTheHintsWords() {
        launch()
        loadPage()
        startCapture()
        val words = composeRule.onNodeWithText("Drag to capture an area · Tap a page to capture all of it", substring = true)
            .fetchSemanticsNode().boundsInWindow
        val button = wholePageButton.assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Under the words: $button, $words", button.top >= words.bottom)
        wholePageButton.assertHeightIsEqualTo(48.dp)
    }
}
