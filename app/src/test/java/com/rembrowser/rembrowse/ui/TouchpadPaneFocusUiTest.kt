package com.rembrowser.rembrowse.ui

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.TouchpadGestures
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.activatesPane
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

/**
 * In split view, the pane a touchpad gesture works on becomes the active one, as a click makes
 * it. Since Compose 1.13 a touchpad's two-finger scroll and pinch (Android 14+, classified as
 * such) reach Compose as a pan or a zoom with nothing pressed, not as a press, while the page's
 * view still takes the keyboard focus for them: the typing went to the pane just scrolled, and
 * the address bar, Ctrl+R and Alt+Left to the other one. The events here are classified ones,
 * dispatched to the window's Compose view as Android hands them over.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TouchpadPaneFocusUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined,
        askBeforeDownloading = { false },
        onRequestSettled = {}
    )
    private val touchpad = TouchpadGestures()
    private lateinit var composeView: View

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
                        paneViewport = { tab, modifier -> Box(modifier) { Text("Viewport:${tab.url}") } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.runOnIdle {
            browserVm.setLayoutMode(LayoutMode.SPLIT_1X2)
            browserVm.focusPane(0)
        }
        composeRule.waitForIdle()
        assertEquals(0, browserVm.uiState.value.activePaneIndex)
    }

    /** The middle of the right-hand pane, in the Compose view's pixels (mdpi: one dp is one pixel). */
    private fun secondPaneCentre(): Pair<Float, Float> {
        val bounds = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        return (bounds.left.value + bounds.width.value / 2f) to (bounds.top.value + bounds.height.value / 2f)
    }

    private fun screenOffset(): Pair<Float, Float> {
        val location = IntArray(2)
        composeView.getLocationOnScreen(location)
        return location[0].toFloat() to location[1].toFloat()
    }

    private fun dispatch(events: List<MotionEvent>) {
        for (event in events) {
            composeRule.runOnIdle { composeView.dispatchTouchEvent(event) }
        }
        composeRule.waitForIdle()
    }

    /** The cursor arrives over the pane before the gesture, as it does on a touchpad. */
    private fun hoverAt(x: Float, y: Float) {
        val (ox, oy) = screenOffset()
        composeRule.runOnIdle { composeView.dispatchGenericMotionEvent(touchpad.hover(x, y, 900L, ox, oy)) }
        composeRule.waitForIdle()
    }

    @Test
    fun aTwoFingerScrollOnTheOtherPane_makesItTheActiveOne() {
        launchSideBySide()
        val (x, y) = secondPaneCentre()
        val (ox, oy) = screenOffset()

        hoverAt(x, y)
        assertEquals("The pointer passing over a pane leaves it inactive", 0, browserVm.uiState.value.activePaneIndex)

        dispatch(touchpad.swipe(x, y, steps = List(6) { 0f to -6f }, rawOffsetX = ox, rawOffsetY = oy))

        assertEquals(1, browserVm.uiState.value.activePaneIndex)
    }

    @Test
    fun aPinchOnTheOtherPane_makesItTheActiveOne() {
        launchSideBySide()
        val (x, y) = secondPaneCentre()
        val (ox, oy) = screenOffset()

        hoverAt(x, y)
        dispatch(touchpad.pinch(x, y, rawOffsetX = ox, rawOffsetY = oy))

        assertEquals(1, browserVm.uiState.value.activePaneIndex)
    }

    @Test
    fun activatesPane_onAPressOrTheStartOfAPanOrZoom_only() {
        val activating = listOf(
            PointerEventType.Press,
            PointerEventType.PanStart,
            PointerEventType.ScaleStart
        )
        val others = listOf(
            PointerEventType.Move,
            PointerEventType.Enter,
            PointerEventType.Exit,
            PointerEventType.Scroll,
            PointerEventType.Release,
            PointerEventType.PanMove,
            PointerEventType.PanEnd,
            PointerEventType.ScaleChange,
            PointerEventType.ScaleEnd
        )
        activating.forEach { assertTrue(it.toString(), activatesPane(it)) }
        others.forEach { assertFalse(it.toString(), activatesPane(it)) }
    }
}
