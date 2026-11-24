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
import com.rembrowser.rembrowse.data.InMemoryUpdatePreferences
import com.rembrowser.rembrowse.data.UpdateApk
import com.rembrowser.rembrowse.data.UpdateManifest
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.FakeUpdateInstaller
import com.rembrowser.rembrowse.fakes.FakeUpdateSource
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.UpdatesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app's own updates: the notice over the page and the controls in Settings → About. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class UpdatesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

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
    private val source = FakeUpdateSource()
    private val installer = FakeUpdateInstaller()
    private val preferences = InMemoryUpdatePreferences()

    private val updatesVm by lazy {
        UpdatesViewModel(
            source = source,
            installer = installer,
            preferences = preferences,
            updatesDir = folder.root.resolve("updates"),
            currentVersionCode = 14,
            supportedAbis = listOf("x86_64"),
            baseUrl = "https://example.test/build/",
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

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
                        versionName = "0.12",
                        onPickLocalXpi = {},
                        updatesViewModel = updatesVm,
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    private fun manifest(versionCode: Int) = UpdateManifest(
        versionName = "0.13",
        versionCode = versionCode,
        apks = mapOf("x86_64" to UpdateApk("RemBrowse-x86_64.apk", 90_000_000L, "a".repeat(64)))
    )

    @Test
    fun dailyCheck_offersTheNewVersionAndUpdateDownloadsAndInstallsIt() {
        source.manifest = Result.success(manifest(versionCode = 15))
        launch()
        updatesVm.onAppStarted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update_notice").assertIsDisplayed()
        composeRule.onNodeWithText("RemBrowse 0.13 is available").assertIsDisplayed()
        composeRule.onNodeWithTag("update_notice_action").performClick()
        composeRule.waitForIdle()

        // About opens and shows the downloaded update while the installer asks to confirm
        composeRule.onNodeWithTag("update_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_page").assertIsDisplayed()
        composeRule.onNodeWithTag("update_status")
            .assertTextContains("RemBrowse 0.13 is downloaded", substring = true)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun closingTheNotice_keepsTheUpdateInAbout() {
        source.manifest = Result.success(manifest(versionCode = 15))
        launch()
        updatesVm.onAppStarted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update_notice").assertIsDisplayed()
        updatesVm.dismissNotice()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("update_notice").assertDoesNotExist()

        browserVm.openSettings(SettingsSection.ABOUT)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("update_status").assertTextContains("RemBrowse 0.13 is available", substring = true)
        composeRule.onNodeWithTag("update_action").assertTextContains("Download and install")
    }

    @Test
    fun about_checksOnRequestAndTurnsTheDailyCheckOff() {
        preferences.checkAutomatically = false
        source.manifest = Result.success(manifest(versionCode = 14))
        launch()
        browserVm.openSettings(SettingsSection.ABOUT)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update_action").assertTextContains("Check for updates").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("update_status").assertTextContains("RemBrowse is up to date.", substring = true)
        // Asked for, so no notice over the page
        composeRule.onNodeWithTag("update_notice").assertDoesNotExist()

        composeRule.onNodeWithTag("setting_check_updates").assertIsOff().performClick()
        composeRule.onNodeWithTag("setting_check_updates").assertIsOn()
        composeRule.onNodeWithTag("setting_check_updates").performClick()
        assertFalse(preferences.checkAutomatically)
    }
}
