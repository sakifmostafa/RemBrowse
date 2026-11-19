package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RemBrowseUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    @Test
    fun layoutSwitcher_switchesFrom1x1To1x2And2x2Panes() {
        val engine = FakeBrowserEngine()
        val repo = FakeBrowserStateRepository()
        val browserVm = BrowserViewModel(engine, repo)
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)

        composeRule.setContent {
            RemBrowseTheme {
                RemBrowseScreen(
                    browserViewModel = browserVm,
                    extensionsViewModel = extVm,
                    onPickLocalXpi = {},
                    paneViewport = { tab, modifier ->
                        Box(modifier = modifier) {
                            Text(text = "Viewport:${tab.url}")
                        }
                    },
                    extensionPopupContent = { Box(it) }
                )
            }
        }

        // Starts in 1x1
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertDoesNotExist()

        // Switch to 1x2
        composeRule.onNodeWithTag("layout_1x2").performClick()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_2").assertDoesNotExist()

        // Switch to 2x2
        composeRule.onNodeWithTag("layout_2x2").performClick()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_2").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_3").assertIsDisplayed()

        // Collapse back to 1x1: all 4 tabs should remain visible in the top Tab Bar
        val allTabIds = browserVm.uiState.value.tabs.map { it.id }
        composeRule.onNodeWithTag("layout_1x1").performClick()
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertDoesNotExist()
        allTabIds.forEach { id ->
            composeRule.onNodeWithTag("tab_bar_item_$id").assertExists()
        }

        // Clicking another tab in the Tab Bar switches the active pane to it
        val targetTabId = allTabIds.last()
        composeRule.onNodeWithTag("tab_bar_item_$targetTabId").performScrollTo().performClick()
        org.junit.Assert.assertEquals(targetTabId, browserVm.uiState.value.activeTabId)
    }

    @Test
    fun extensionsButton_opensWebExtensionsManagerDialog() {
        val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)

        composeRule.setContent {
            RemBrowseTheme {
                RemBrowseScreen(
                    browserViewModel = browserVm,
                    extensionsViewModel = extVm,
                    onPickLocalXpi = {},
                    paneViewport = { tab, modifier -> Box(modifier) { Text(tab.title) } },
                    extensionPopupContent = { Box(it) }
                )
            }
        }

        composeRule.onNodeWithTag("extensions_button").performClick()
        composeRule.onNodeWithTag("extensions_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("RemBrowse WebExtensions").assertIsDisplayed()
        composeRule.onNodeWithText("Browse AMO").assertIsDisplayed()
    }
}
