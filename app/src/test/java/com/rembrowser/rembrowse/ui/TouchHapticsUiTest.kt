package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Haptics answer a finger on the screen only: switches, the tab switcher's long press, a tab
 * picked up and dropped, a divider double-tapped. The same things done with a mouse (the
 * touchpad is one) or a screen reader play nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TouchHapticsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private lateinit var browserVm: BrowserViewModel
    private val chrome = BrowserChromeState()

    private val played = mutableListOf<HapticFeedbackType>()
    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private val reddit = BrowserTab(id = "reddit", url = "https://reddit.com/", title = "Reddit")
    private val youtube = BrowserTab(id = "youtube", url = "https://youtube.com/", title = "YouTube")
    private val news = BrowserTab(id = "news", url = "https://news.example/", title = "News")

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(layout: LayoutMode = LayoutMode.SINGLE_1X1) {
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = layout,
                    tabs = listOf(reddit, youtube, news),
                    paneTabIds = listOf("reddit", "youtube").take(layout.paneCount)
                )
            )
        )
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
        val downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(emptyList()),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false, LocalHapticFeedback provides recorder) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> Box(modifier) { Text("Viewport:${tab.url}") } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aSettingsSwitch_flippedByAFinger_playsToggleOffAndOn() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        val switch = composeRule.onNodeWithTag("setting_ask_before_download")
        switch.assertIsOn()

        switch.performTouchInput { click() }
        switch.assertIsOff()
        switch.performTouchInput { click() }
        switch.assertIsOn()
        assertEquals(listOf(HapticFeedbackType.ToggleOff, HapticFeedbackType.ToggleOn), played)
    }

    @Test
    fun aSettingsSwitch_flippedWithTheMouseOrAScreenReader_playsNothing() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        val switch = composeRule.onNodeWithTag("setting_ask_before_download")

        switch.performMouseInput { click() }
        switch.assertIsOff()
        switch.performSemanticsAction(SemanticsActions.OnClick)
        switch.assertIsOn()
        // And a finger tap after them still counts, so nothing was left stuck
        switch.performTouchInput { click() }
        switch.assertIsOff()
        assertEquals(listOf(HapticFeedbackType.ToggleOff), played)
    }

    @Test
    fun matchCase_byAFingerOnly() {
        launch()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        val matchCase = composeRule.onNodeWithTag("find_match_case")

        matchCase.performMouseInput { click() }
        matchCase.performTouchInput { click() }
        assertEquals(listOf(HapticFeedbackType.ToggleOff), played)
    }

    @Test
    fun aSwitcherCard_heldByAFinger_playsTheLongPress() {
        launch()
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.onNodeWithTag("switcher_tab_news").performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.LongPress), played)
    }

    private fun rootRatio(): Float = browserVm.uiState.value.layout.splits().first().ratio

    @Test
    fun aDivider_doubleTappedByAFinger_confirmsTheReset_doubleClickedWithTheMouse_doesNot() {
        launch(LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread { browserVm.updateHorizontalSplit(0.2f) }
        assertEquals(0.7f, rootRatio(), 0.001f)
        val divider = composeRule.onNodeWithTag("split_divider_vertical")

        divider.performMouseInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(0.5f, rootRatio(), 0.001f)
        assertEquals(emptyList<HapticFeedbackType>(), played)

        composeRule.runOnUiThread { browserVm.updateHorizontalSplit(0.2f) }
        // Past the double-tap time, so the next taps are a pair of their own
        composeRule.mainClock.advanceTimeBy(1_000)
        divider.performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertEquals(0.5f, rootRatio(), 0.001f)
        assertEquals(listOf(HapticFeedbackType.Confirm), played)
    }

    /** The offset from the top left of the rail tile of [tabId] to a point in pane [paneIndex], in pixels. */
    private fun tileToPane(tabId: String, paneIndex: Int): Offset {
        val tile = composeRule.onNodeWithTag("tab_bar_item_$tabId").getUnclippedBoundsInRoot()
        val pane = composeRule.onNodeWithTag("browser_pane_$paneIndex").getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        return Offset(
            (pane.left + (pane.right - pane.left) * 0.5f - tile.left).value * density,
            (pane.top + (pane.bottom - pane.top) * 0.5f - tile.top).value * density
        )
    }

    @Test
    fun aTabDraggedByAFinger_buzzesWhenPickedUpAndWhenDropped() {
        launch(LayoutMode.SPLIT_1X2)
        val target = tileToPane("news", paneIndex = 0)
        composeRule.onNodeWithTag("tab_bar_item_news").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(Offset(1f, 0f))
        }
        // The tile's long press (its menu)
        assertEquals(listOf(HapticFeedbackType.LongPress), played)
        composeRule.onNodeWithTag("tab_bar_item_news").performTouchInput {
            moveTo(center + Offset(60f, 0f))
            moveTo(target)
        }
        assertEquals(HapticFeedbackType.GestureThresholdActivate, played.last())
        composeRule.onNodeWithTag("tab_bar_item_news").performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals("news", browserVm.uiState.value.paneTabIds[0])
        assertEquals(
            listOf(HapticFeedbackType.LongPress, HapticFeedbackType.GestureThresholdActivate, HapticFeedbackType.Confirm),
            played
        )
    }

    @Test
    fun aTabDraggedWithTheMouse_playsNothing() {
        launch(LayoutMode.SPLIT_1X2)
        val target = tileToPane("news", paneIndex = 0)
        composeRule.onNodeWithTag("tab_bar_item_news").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
            moveTo(target)
        }
        composeRule.onNodeWithTag("tab_bar_item_news").performMouseInput { release() }
        composeRule.waitForIdle()

        assertEquals("news", browserVm.uiState.value.paneTabIds[0])
        assertEquals(emptyList<HapticFeedbackType>(), played)
    }
}
