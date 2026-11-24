package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Headings for screen readers, and touch-sized toolbar controls on a phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class AccessibilityUiTest {

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
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

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
    fun settings_sectionAndGroupTitlesAreHeadings() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_section_appearance").performClick()

        composeRule.onNode(hasText("Settings") and isHeading).assertExists()
        // The open section's title, and a group within it
        composeRule.onNode(hasText("Appearance") and isHeading).assertExists()
        composeRule.onNode(hasText("Theme")).assert(isHeading)
        composeRule.onNode(hasText("Theme color")).assert(isHeading)
        composeRule.onNode(hasText("Page rendering")).assert(isHeading)
        // A setting's own name is not a heading
        composeRule.onNode(hasText("System default")).assert(!isHeading)
    }

    @Test
    fun dialogTitles_areHeadings() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_bookmarks").performScrollTo().performClick()
        composeRule.onNode(hasText("Bookmarks") and isHeading).assertExists()
    }

    @Test
    fun theTabSwitchersCount_isAHeading() {
        browserVm.openNewTab("https://one.example/")
        launch()
        composeRule.onNodeWithTag("tab_switcher_button").performClick()
        composeRule.onNode(hasText("2 open tabs")).assert(isHeading)
    }

    @Test
    fun wideWindow_keepsTheDesktopToolbar() {
        launch()
        composeRule.onNodeWithTag("toolbar").assertHeightIsEqualTo(52.dp)
        composeRule.onNodeWithTag("address_pill").assertHeightIsEqualTo(40.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun phoneWindow_hasATouchSizedToolbarAndAddressBar() {
        launch()
        composeRule.onNodeWithTag("toolbar").assertHeightIsEqualTo(56.dp)
        composeRule.onNodeWithTag("address_pill").assertHeightIsEqualTo(48.dp)
    }
}
