package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.data.InMemoryAddonUpdatePreferences
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AddonUpdateResult
import com.rembrowser.rembrowse.model.InstalledExtension
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Add-on updates in Settings → Extensions: the daily check's switch and "Check for updates". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class AddonUpdatesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val manager = FakeExtensionManager().apply {
        installedExtensions.value = listOf(
            InstalledExtension("uBlock0@raymondhill.net", "uBlock Origin", "1.61.2", "", enabled = true),
            InstalledExtension("vpn@proton.ch", "Proton VPN", "1.2.0", "", enabled = false)
        )
    }
    private val preferences = InMemoryAddonUpdatePreferences()
    private var online = true
    private val extVm = ExtensionsViewModel(
        manager,
        FakeAmoApiClient(),
        uiScope,
        addonUpdatePreferences = preferences,
        isOnline = { online }
    )
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

    private fun openExtensionSettings() {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "0.16.1",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        browserVm.openSettings(SettingsSection.EXTENSIONS)
        composeRule.waitForIdle()
    }

    /** At least 48dp tall (mdpi: one dp is one pixel). */
    private fun assertTouchTarget(tag: String) {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$tag is ${bounds.height}px tall", bounds.height >= 48f)
    }

    @Test
    fun checkForUpdates_saysWhatWasUpdated_andTheSwitchTurnsTheDailyCheckOff() {
        manager.addonUpdateResults["uBlock0@raymondhill.net"] = AddonUpdateResult.Updated("1.62.0")
        openExtensionSettings()

        composeRule.onNodeWithTag("addon_update_status")
            .assertTextContains("See whether your add-ons have newer versions.")
        assertTouchTarget("addon_update_check")
        composeRule.onNodeWithTag("addon_update_check")
            .assertTextContains("Check for updates")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("addon_update_status").assertTextContains("Updated uBlock Origin.")
        composeRule.onNodeWithText("Version 1.62.0").assertIsDisplayed()

        assertTouchTarget("setting_update_addons")
        composeRule.onNodeWithTag("setting_update_addons")
            .assertIsOn()
            .performClick()
        composeRule.onNodeWithTag("setting_update_addons").assertIsOff()
        assertFalse(preferences.updateAutomatically)
    }

    @Test
    fun aCheckWithoutAConnection_saysSo() {
        online = false
        openExtensionSettings()

        composeRule.onNodeWithTag("addon_update_check").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("addon_update_status")
            .assertTextContains("Couldn't check for updates. Check your connection and try again.")
    }
}
