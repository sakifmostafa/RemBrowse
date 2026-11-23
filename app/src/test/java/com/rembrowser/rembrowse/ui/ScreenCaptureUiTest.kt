package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.viewinterop.AndroidView
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
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi-keysexposed-qwerty")
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
                        paneViewport = viewport,
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun loadPage(url: String = "https://example.com/", title: String = "Example") {
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(browserVm.uiState.value.activePaneIndex, url)
            engine.sessions.getValue(tab).simulatePageLoad(url, title, canGoBack = false)
        }
        composeRule.waitForIdle()
    }

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
}
