package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
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

/** Keyboard shortcuts in More and in the command palette, both only with a keyboard attached. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi-qwerty")
class KeyboardShortcutsMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )
    private var sheetsShown = 0

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
                        onPickLocalXpi = {},
                        onShowKeyboardShortcuts = { sheetsShown++ },
                        paneViewport = { tab, modifier -> Box(modifier) { Text(tab.displayTitle) } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    @Test
    fun withAKeyboard_moreOffersKeyboardShortcuts() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_keyboard_shortcuts").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, sheetsShown)
        composeRule.onNodeWithTag("menu_keyboard_shortcuts").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi-nokeys")
    fun withoutAKeyboard_moreLeavesItOut() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_keyboard_shortcuts").assertDoesNotExist()
    }

    @Test
    fun theCommandPalette_opensTheSheet() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_command_palette").performClick()
        composeRule.onNodeWithTag("command_palette_input").performTextInput("shortcuts")
        composeRule.onNodeWithTag("palette_keyboard_shortcuts").performClick()
        assertEquals(1, sheetsShown)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi-nokeys")
    fun withoutAKeyboard_theCommandPaletteLeavesItOut() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_command_palette").performClick()
        composeRule.onNodeWithTag("command_palette_input").performTextInput("shortcuts")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("palette_keyboard_shortcuts").assertDoesNotExist()
        // The rest of the palette is there: the other commands still match
        composeRule.onNodeWithTag("command_palette_input").performTextClearance()
        composeRule.onNodeWithTag("command_palette_input").performTextInput("settings")
        composeRule.onNodeWithTag("palette_settings").assertExists()
    }
}
