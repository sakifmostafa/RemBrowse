package com.rembrowser.rembrowse.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.WindowTouchpadInput
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.TouchpadGestures
import com.rembrowser.rembrowse.model.LayoutMode
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A touchpad's two-finger scroll and pinch, classified as such (Android 14+), through the whole
 * window as MainActivity hands them on: its touchpad filter ([WindowTouchpadInput]), the activity's
 * own dispatch, the window, Compose, and on to the web view embedded in a pane. The page's view
 * must get the events as they came, each once and in order (Gecko scrolls and zooms from them),
 * and the pane they land on becomes the active one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class WindowTouchpadUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )
    private val touchpad = TouchpadGestures()
    private lateinit var composeView: View

    /** What one event looked like when a page's view got it. */
    private data class Seen(
        val action: Int,
        val eventTime: Long,
        val rawX: Float,
        val rawY: Float,
        val pointers: Int,
        val toolType: Int,
        val classification: Int
    )

    private fun MotionEvent.seen() =
        Seen(action, eventTime, rawX, rawY, pointerCount, getToolType(0), classification)

    /** The touch events each tab's page view got, in order. */
    private val pageEvents = mutableMapOf<String, MutableList<Seen>>()

    /** A web page's view, which takes every touch stream it is given, as GeckoView does. */
    private class PageView(context: Context, private val onEvent: (MotionEvent) -> Unit) : View(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            onEvent(event)
            return true
        }
    }

    @After
    fun tearDown() {
        uiScope.cancel()
        touchpad.recycle()
    }

    private fun launchSideBySide() {
        composeRule.setContent {
            composeView = LocalView.current
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = BrowserChromeState(),
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier ->
                            Box(modifier) {
                                AndroidView(
                                    factory = { context ->
                                        PageView(context) { pageEvents.getOrPut(tab.id) { mutableListOf() } += it.seen() }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        // A web page in each pane
        composeRule.runOnIdle {
            browserVm.setLayoutMode(LayoutMode.SPLIT_1X2)
            browserVm.navigatePane(0, "https://example.com/")
            browserVm.navigatePane(1, "https://example.org/")
            browserVm.focusPane(0)
        }
        composeRule.waitForIdle()
        assertEquals(LayoutMode.SPLIT_1X2, browserVm.uiState.value.layoutMode)
        assertEquals(0, browserVm.uiState.value.activePaneIndex)
    }

    /** The tab shown in the right-hand pane. */
    private fun secondPaneTab(): String = browserVm.uiState.value.paneTabIds[1]

    /** The middle of the right-hand pane, in the window's pixels (mdpi: one dp is one pixel). */
    private fun secondPaneCentre(): Pair<Float, Float> {
        val bounds = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        val inWindow = IntArray(2).also { composeView.getLocationInWindow(it) }
        return (inWindow[0] + bounds.left.value + bounds.width.value / 2f) to
            (inWindow[1] + bounds.top.value + bounds.height.value / 2f)
    }

    /** Where the window is on the screen: the raw coordinates are the screen's. */
    private fun windowOnScreen(): Pair<Float, Float> {
        val location = IntArray(2).also { composeRule.activity.window.decorView.getLocationOnScreen(it) }
        return location[0].toFloat() to location[1].toFloat()
    }

    /** Through the window as MainActivity hands touches on: its filter, then the activity's own dispatch. */
    private fun dispatchThroughTheWindow(events: List<MotionEvent>) {
        val activity = composeRule.activity
        val window = WindowTouchpadInput(activity)
        for (event in events) {
            composeRule.runOnIdle { window.dispatchTouchEvent(event) { activity.dispatchTouchEvent(it) } }
        }
        composeRule.waitForIdle()
    }

    /** The cursor arrives over the pane first, as it does on a touchpad. */
    private fun hoverThroughTheWindow(x: Float, y: Float) {
        val (ox, oy) = windowOnScreen()
        val activity = composeRule.activity
        composeRule.runOnIdle {
            WindowTouchpadInput.dispatchGenericMotionEvent(touchpad.hover(x, y, 900L, ox, oy)) {
                activity.dispatchGenericMotionEvent(it)
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertThePageGotThemAsTheyCame(events: List<MotionEvent>) {
        val tab = secondPaneTab()
        assertEquals("Each event once, in order, unchanged", events.map { it.seen() }, pageEvents[tab].orEmpty())
        assertEquals("The other pane's page got none", emptyList<Seen>(), pageEvents[browserVm.uiState.value.paneTabIds[0]].orEmpty())
        assertEquals("The pane scrolled is the active one", 1, browserVm.uiState.value.activePaneIndex)
    }

    @Test
    fun aTwoFingerScroll_reachesThePagesViewOnceAndInOrder_andMakesItsPaneActive() {
        launchSideBySide()
        val (x, y) = secondPaneCentre()
        val (ox, oy) = windowOnScreen()
        hoverThroughTheWindow(x, y)
        assertEquals(0, browserVm.uiState.value.activePaneIndex)

        // Small steps first: a window filter that held the scroll back would drop them
        val swipe = touchpad.swipe(x, y, steps = listOf(0f to -2f, 0f to -2f) + List(5) { 0f to -8f }, rawOffsetX = ox, rawOffsetY = oy)
        dispatchThroughTheWindow(swipe)

        assertThePageGotThemAsTheyCame(swipe)
    }

    @Test
    fun aPinch_reachesThePagesViewOnceAndInOrder_andMakesItsPaneActive() {
        launchSideBySide()
        val (x, y) = secondPaneCentre()
        val (ox, oy) = windowOnScreen()
        hoverThroughTheWindow(x, y)

        val pinch = touchpad.pinch(x, y, rawOffsetX = ox, rawOffsetY = oy)
        dispatchThroughTheWindow(pinch)

        assertThePageGotThemAsTheyCame(pinch)
    }
}
