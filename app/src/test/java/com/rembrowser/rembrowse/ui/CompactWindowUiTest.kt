package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.InstalledExtension
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
import org.robolectric.annotation.GraphicsMode

/** The same screen on a phone-sized window: no tab rail, a tab counter instead. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
class CompactWindowUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extensionManager = FakeExtensionManager()
    private val extVm = ExtensionsViewModel(extensionManager, FakeAmoApiClient(), uiScope)
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
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> Box(modifier) { Text(tab.displayTitle) } },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    @Test
    fun compactWindow_replacesTheRailWithATabCounterThatOpensTheSwitcher() {
        browserVm.openNewTab("https://one.example/")
        launch()

        composeRule.onNodeWithTag("tab_rail").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("2 open tabs. Show tabs").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_switcher_button").performClick()
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()

        composeRule.onNodeWithTag("switcher_new_tab").performClick()
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        assertEquals(3, browserVm.uiState.value.tabs.size)
    }

    @Test
    fun compactWindow_keepsExtensionButtonsInTheMoreMenu() {
        extensionManager.installedExtensions.value = listOf(
            InstalledExtension(
                id = "vpn@example",
                name = "Proton VPN",
                version = "1.0",
                description = "",
                enabled = true,
                hasBrowserAction = true
            )
        )
        launch()

        composeRule.onNodeWithTag("rail_extensions").assertDoesNotExist()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extension_vpn@example").performClick()

        assertEquals(listOf("vpn@example"), extensionManager.triggeredActionIds)
        composeRule.onNodeWithTag("extension_popup_page").assertIsDisplayed()
    }

    // Native graphics for real text widths: with the legacy renderer text measures as almost
    // nothing, every chip would fit on screen and this could not fail.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactWindow_settingsScrollTheOpenSectionIntoView() {
        launch()

        // Extensions is the fourth chip: on a 360dp window it starts off screen
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_extensions").performClick()

        composeRule.onNodeWithTag("settings_section_extensions").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("extensions_panel").assertIsDisplayed()

        // Every other section stays reachable by scrolling the row
        composeRule.onNodeWithTag("settings_section_about").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_section_about").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("settings_section_appearance").performScrollTo().performClick()
        composeRule.onNodeWithTag("setting_dark_theme").assertIsDisplayed()

        // Downloads sits in the middle of the row: its chip is brought into view as well
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_downloads").performClick()
        composeRule.onNodeWithTag("settings_section_downloads").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("downloads_panel").assertIsDisplayed()
    }

    @Test
    fun compactWindow_keepsSplitViewReachableFromTheMenu() {
        launch()
        composeRule.onNodeWithTag("split_button").assertDoesNotExist()

        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("split_button").performClick()
        composeRule.onNodeWithTag("layout_2x1").performClick()

        assertEquals(LayoutMode.SPLIT_2X1, browserVm.uiState.value.layoutMode)
        composeRule.onNodeWithTag("browser_pane_0").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_pane_1").assertIsDisplayed()
    }
}
