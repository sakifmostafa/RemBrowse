package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "One left, two right" layout from the Split menu, on a laptop-sized window. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class OneAndTwoLayoutUiTest {

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

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch() {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    @Test
    fun splitMenu_oneLeftTwoRight_showsOneTallPaneBesideTwoStackedOnes() {
        launch()
        val firstTab = browserVm.uiState.value.activeTabId

        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_1_2").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val state = browserVm.uiState.value
        assertEquals(LayoutMode.SPLIT_1_2, state.layoutMode)
        assertEquals(3, state.paneCount)
        assertEquals("The page you were on takes the tall pane", firstTab, state.paneTabIds[0])

        val left = composeRule.onNodeWithTag("browser_pane_0").getUnclippedBoundsInRoot()
        val top = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        val bottom = composeRule.onNodeWithTag("browser_pane_2").getUnclippedBoundsInRoot()
        assertTrue("The two on the right are beside the tall one", top.left > left.right && bottom.left > left.right)
        assertEquals("…in one column", top.left, bottom.left)
        assertTrue("…one above the other", bottom.top > top.bottom)
        assertTrue("The left pane spans both", left.top <= top.top && left.bottom >= bottom.bottom)

        // The menu ticks the layout that is on screen
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_1_2").assertIsSelected()
    }
}
