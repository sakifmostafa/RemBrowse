package com.rembrowser.rembrowse.ui

import android.view.PointerIcon
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.components.ChromePointerIconClaim
import com.rembrowser.rembrowse.ui.components.LocalChromePointerIconClaim
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

/**
 * Resizing split panes by their divider, mostly with a mouse, on the real screen with a fake
 * engine. At mdpi one dp is one pixel, so positions read the same in both. Pointer positions
 * are given in the window (on the root), because the divider itself moves during a drag.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class SplitDividerMouseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = FakeBrowserStateRepository()
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), repository)
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined,
        askBeforeDownloading = { false },
        onRequestSettled = {}
    )
    private val cursorClaim = ChromePointerIconClaim()
    private lateinit var composeView: View

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(layout: LayoutMode) {
        composeRule.setContent {
            composeView = LocalView.current
            CompositionLocalProvider(
                LocalSiteIconsEnabled provides false,
                LocalChromePointerIconClaim provides cursorClaim
            ) {
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
        composeRule.runOnIdle { browserVm.setLayoutMode(layout) }
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private val DpRect.centre: Offset
        get() = Offset(left.value + width.value / 2f, top.value + height.value / 2f)

    private fun rootRatio(): Float = browserVm.uiState.value.layout.splits().first().ratio

    /** One pointer event, then a frame: the divider has moved before the next event arrives. */
    private fun mouse(block: MouseInjectionScope.() -> Unit) {
        composeRule.onRoot().performMouseInput(block)
        composeRule.waitForIdle()
    }

    private fun systemIcon(type: Int): PointerIcon = PointerIcon.getSystemIcon(composeView.context, type)

    /** What Compose sets when nothing under the pointer asks for a cursor of its own. */
    @Suppress("DEPRECATION")
    private fun defaultIcon(): PointerIcon = systemIcon(PointerIcon.TYPE_DEFAULT)

    // The divider used to be dragged by the sum of the steps Compose delivered. Compose drops a
    // move whose position has not changed for any node that gets it, in the node's own
    // coordinates; for a divider that had caught up with the pointer that is every step as long
    // as the last one. At a steady speed every other step was lost, and the divider fell behind.
    @Test
    fun aSteadyMouseDrag_movesTheDividerWithThePointerAtEveryStep() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre

        mouse { moveTo(grip) }
        mouse { press() }
        for (step in 1..12) {
            mouse { moveTo(grip + Offset(step * 3f, 0f)) }
            assertEquals("after step $step", grip.x + step * 3f, bounds("split_divider_vertical").centre.x, 0.6f)
        }
        mouse { release() }

        assertEquals(grip.x + 36f, bounds("split_divider_vertical").centre.x, 0.6f)
    }

    @Test
    fun aSteadyMouseDrag_movesAStackedSplitToo() {
        launch(LayoutMode.SPLIT_2X1)
        val grip = bounds("split_divider_horizontal").centre

        mouse { moveTo(grip) }
        mouse { press() }
        for (step in 1..8) mouse { moveTo(grip + Offset(0f, step * -5f)) }
        mouse { release() }

        assertEquals(grip.y - 40f, bounds("split_divider_horizontal").centre.y, 0.6f)
    }

    @Test
    fun aMouseDragPastTheLimitAndBack_keepsTheGripUnderThePointer() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre

        mouse { moveTo(grip) }
        mouse { press() }
        mouse { moveTo(grip + Offset(250f, 0f)) }
        // Far past the narrowest the right pane may get: the divider waits at its limit
        mouse { moveTo(grip + Offset(560f, 0f)) }
        val limit = bounds("split_divider_vertical").centre.x
        assertTrue(limit < grip.x + 500f)
        // On the way back it stays there until the pointer has come back to it
        mouse { moveTo(grip + Offset(480f, 0f)) }
        assertEquals(limit, bounds("split_divider_vertical").centre.x, 0.6f)
        mouse { moveTo(grip + Offset(100f, 0f)) }
        mouse { release() }

        assertEquals(grip.x + 100f, bounds("split_divider_vertical").centre.x, 0.6f)
    }

    @Test
    fun aDragIsSavedOnceWhenTheButtonIsLetGo() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre
        mouse { moveTo(grip) }
        mouse { press() }
        mouse { moveTo(grip + Offset(60f, 0f)) }
        mouse { moveTo(grip + Offset(120f, 0f)) }
        val savesWhileDragging = repository.saveCount

        mouse { release() }

        assertEquals(savesWhileDragging + 1, repository.saveCount)
        assertEquals(rootRatio(), repository.storedState.layout!!.splits().first().ratio, 0f)
    }

    // The divider of a split keeps its place in the tree when the split turns, and its gesture
    // was set up once: the turned divider went on listening along the old axis.
    @Test
    fun aDividerThatTurnsWithItsSplit_isDraggedAlongItsNewAxis() {
        launch(LayoutMode.SPLIT_1X2)
        // The gesture only starts listening once a pointer has been on the divider
        mouse { moveTo(bounds("split_divider_vertical").centre) }
        composeRule.runOnIdle { browserVm.setLayoutMode(LayoutMode.SPLIT_2X1) }
        val grip = bounds("split_divider_horizontal").centre

        mouse { moveTo(grip) }
        mouse { press() }
        mouse { moveTo(grip + Offset(0f, 40f)) }
        mouse { moveTo(grip + Offset(0f, 80f)) }
        mouse { release() }

        assertEquals(grip.y + 80f, bounds("split_divider_horizontal").centre.y, 0.6f)
    }

    // A mouse starts a drag on the slightest move, and a drag used to cancel the double click.
    @Test
    fun aDoubleClickWithAMouseThatSlipsAPixel_resetsTheSplit() {
        launch(LayoutMode.SPLIT_1X2)
        composeRule.runOnIdle { browserVm.setSplitPreset(0.7f) }
        assertEquals(0.7f, rootRatio(), 0.001f)
        val grip = bounds("split_divider_vertical").centre

        mouse { moveTo(grip) }
        mouse { press() }
        mouse { moveTo(grip + Offset(1f, 0f)) }
        mouse { release() }
        mouse {
            advanceEventTime(80)
            press()
        }
        mouse { moveTo(grip + Offset(2f, 1f)) }
        mouse { release() }

        assertEquals(0.5f, rootRatio(), 0f)
    }

    @Test
    fun aPlainDoubleClick_resetsTheSplit() {
        launch(LayoutMode.SPLIT_1X2)
        composeRule.runOnIdle { browserVm.setSplitPreset(0.7f) }

        composeRule.onNodeWithTag("split_divider_vertical").performMouseInput { doubleClick() }
        composeRule.waitForIdle()

        assertEquals(0.5f, rootRatio(), 0f)
    }

    @Test
    fun twoSlowClicks_orADragAndAClick_doNotResetTheSplit() {
        launch(LayoutMode.SPLIT_1X2)
        composeRule.runOnIdle { browserVm.setSplitPreset(0.7f) }
        val grip = bounds("split_divider_vertical").centre

        mouse { moveTo(grip) }
        mouse { click(grip) }
        mouse {
            advanceEventTime(1_000)
            click(grip)
        }
        assertEquals(0.7f, rootRatio(), 0.001f)

        // A drag is not the first half of a double click
        mouse { press() }
        mouse { moveTo(grip + Offset(-60f, 0f)) }
        mouse { release() }
        val dragged = rootRatio()
        assertTrue(dragged < 0.69f)
        mouse {
            advanceEventTime(50)
            click(grip + Offset(-60f, 0f))
        }
        assertEquals(dragged, rootRatio(), 0.001f)
    }

    @Test
    fun theSecondaryButton_doesNotDragTheDivider() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre

        mouse { moveTo(grip) }
        mouse { press(MouseButton.Secondary) }
        mouse { moveTo(grip + Offset(80f, 0f)) }
        mouse { release(MouseButton.Secondary) }

        assertEquals(0.5f, rootRatio(), 0f)
    }

    // The grab area is wider than the gap and lies over the edge of the page on each side. The
    // page's view answers Android's question for the cursor there unless the divider claims it.
    @Test
    fun theDividerClaimsTheCursorOverItsWholeGrabArea_andOnlyThere() {
        launch(LayoutMode.SPLIT_1X2)
        val divider = bounds("split_divider_vertical")
        val leftPane = bounds("browser_pane_0")
        val y = divider.centre.y
        assertTrue("The grab area overlaps the page", divider.left < leftPane.right)

        mouse { moveTo(Offset(leftPane.right.value - 100f, y)) }
        assertFalse(cursorClaim.isClaimed)

        // Over the page's edge, inside the grab area
        mouse { moveTo(Offset(divider.left.value + 1f, y)) }
        assertTrue(cursorClaim.isClaimed)
        assertEquals(systemIcon(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW), composeView.pointerIcon)
        mouse { moveTo(Offset(divider.right.value - 1f, y)) }
        assertTrue(cursorClaim.isClaimed)

        mouse { moveTo(Offset(divider.right.value + 30f, y)) }
        assertFalse(cursorClaim.isClaimed)
        assertEquals(defaultIcon(), composeView.pointerIcon)
    }

    // While dragging, the pointer is off the divider whenever it runs ahead of it or the divider
    // waits at a limit. Compose then takes the divider's cursor away, and the page's shows.
    @Test
    fun theResizeCursorStays_whileADragTakesThePointerOffTheDivider() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre
        val resize = systemIcon(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)

        mouse { moveTo(grip) }
        mouse { press() }
        // One fast step, far ahead of the divider, then deep into the other page past the limit
        mouse { moveTo(grip + Offset(300f, 0f)) }
        assertTrue(cursorClaim.isClaimed)
        assertEquals(resize, composeView.pointerIcon)
        mouse { moveTo(grip + Offset(560f, 120f)) }
        assertTrue(cursorClaim.isClaimed)
        assertEquals(resize, composeView.pointerIcon)

        mouse { release() }
        assertFalse("Let go over the page: its cursor is back", cursorClaim.isClaimed)
        mouse { moveTo(grip + Offset(555f, 120f)) }
        assertEquals(defaultIcon(), composeView.pointerIcon)
    }

    @Test
    fun aDividerThatGoesAwayUnderThePointer_givesTheCursorBack() {
        launch(LayoutMode.SPLIT_1X2)
        mouse { moveTo(bounds("split_divider_vertical").centre) }
        mouse { press() }
        assertTrue(cursorClaim.isClaimed)

        composeRule.runOnIdle { browserVm.setLayoutMode(LayoutMode.SINGLE_1X1) }

        composeRule.onNodeWithTag("split_divider_vertical").assertDoesNotExist()
        assertFalse(cursorClaim.isClaimed)
        mouse { release() }
        mouse { moveBy(Offset(5f, 0f)) }
        assertEquals(defaultIcon(), composeView.pointerIcon)
    }

    @Test
    fun aFingerStillDragsTheDivider_andDoubleTapStillResetsIt() {
        launch(LayoutMode.SPLIT_1X2)
        val grip = bounds("split_divider_vertical").centre

        composeRule.onRoot().performTouchInput { down(grip) }
        for (step in 1..10) {
            composeRule.onRoot().performTouchInput { moveTo(grip + Offset(step * 12f, 0f)) }
            composeRule.waitForIdle()
        }
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()

        // Less the few pixels a finger must travel before it counts as a drag
        val moved = bounds("split_divider_vertical").centre.x - grip.x
        assertTrue("moved $moved", moved > 100f && moved <= 120f)
        assertFalse("A finger has no cursor to claim", cursorClaim.isClaimed)

        composeRule.onNodeWithTag("split_divider_vertical").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(0.5f, rootRatio(), 0f)
    }

    @Test
    fun aMouseDragInANestedSplit_resizesThatSplitOnly() {
        launch(LayoutMode.GRID_2X2)
        val dividers = composeRule.onAllNodesWithTag("split_divider_vertical")
        val grip = dividers[0].getUnclippedBoundsInRoot().centre

        mouse { moveTo(grip) }
        mouse { press() }
        for (step in 1..6) mouse { moveTo(grip + Offset(step * 10f, 0f)) }
        mouse { release() }

        val splits = browserVm.uiState.value.layout.splits()
        val moved = splits.filter { it.ratio != 0.5f }
        assertEquals(1, moved.size)
        assertEquals(grip.x + 60f, dividers[0].getUnclippedBoundsInRoot().centre.x, 0.6f)
    }
}
