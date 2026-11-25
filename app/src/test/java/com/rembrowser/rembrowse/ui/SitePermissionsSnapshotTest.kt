package com.rembrowser.rembrowse.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.PermissionActionDelayMillis
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the site permission card and Settings → Site permissions to PNG files, for design
 * review, like [UiSnapshotTest]. Skipped unless a target directory is given:
 * `./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots`
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class SitePermissionsSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File? = System.getProperty("rembrowse.screenshots")?.let(::File)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private lateinit var browserVm: BrowserViewModel

    @Before
    fun setUp() {
        assumeTrue("Pass -Prembrowse.screenshots=<dir> to render snapshots", outputDir != null)
        outputDir!!.mkdirs()
    }

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private val meetTab = BrowserTab(id = "meet", url = "https://meet.google.com/abc-defg-hij", title = "Meet")

    /** A web app that talks to a media server at home, as the local network prompt's example. */
    private val plexTab = BrowserTab(id = "plex", url = "https://app.plex.tv/desktop", title = "Plex")

    private fun launch(dark: Boolean, layout: LayoutMode = LayoutMode.SINGLE_1X1, firstTab: BrowserTab = meetTab) {
        val tabs = listOf(
            firstTab,
            BrowserTab(id = "news", url = "https://news.example.com/", title = "The Example Times"),
            BrowserTab(id = "new")
        )
        val settings = BrowserSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = layout,
                    tabs = tabs,
                    paneTabIds = listOf(firstTab.id, "news").take(layout.paneCount),
                    settings = settings
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
        engine.sessions.values.forEach { it.simulateSecurityChange(true) }
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme(darkTheme = settings.themeMode.isDark(systemIsDark = true), accent = settings.accent) {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "0.13.16",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> SamplePage(tab, modifier) },
                        extensionPopupContent = {}
                    )
                }
            }
        }
    }

    private fun askCameraAndMicrophone() {
        composeRule.runOnUiThread {
            engine.simulatePermissionRequest(
                "meet",
                "https://meet.google.com",
                setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE)
            )
        }
    }

    private fun askStorageAccess() {
        composeRule.runOnUiThread {
            engine.simulatePermissionRequest(
                "news",
                "https://news.example.com",
                setOf(SitePermissionKind.STORAGE_ACCESS),
                embeddedOrigin = "https://comments.disqus.com"
            )
        }
    }

    private fun askLocal(kind: SitePermissionKind) {
        composeRule.runOnUiThread { engine.simulatePermissionRequest("plex", "https://app.plex.tv", setOf(kind)) }
    }

    /**
     * Lets the card's answers wake up, so they are drawn as they look when they can be used. Their
     * wait starts when they are first drawn, and Robolectric draws only when asked to.
     */
    private fun waitOutActionDelay() {
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        composeRule.mainClock.advanceTimeBy(PermissionActionDelayMillis + 300)
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun promptWideDark() {
        launch(dark = true)
        composeRule.waitForIdle()
        // Caught inside its first half second, while the answers are still dimmed
        composeRule.mainClock.autoAdvance = false
        askCameraAndMicrophone()
        composeRule.mainClock.advanceTimeBy(250)
        save("perm-01-prompt-wide-dark-waiting")
        composeRule.mainClock.autoAdvance = true
        waitOutActionDelay()
        save("perm-02-prompt-wide-dark")
        // The find bar opens beside it: a wide pane has room for both
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        save("perm-03-prompt-wide-dark-with-find")
    }

    @Test
    fun promptWideLight() {
        launch(dark = false)
        askCameraAndMicrophone()
        waitOutActionDelay()
        save("perm-04-prompt-wide-light")
    }

    @Test
    fun promptSplitPanesDark() {
        launch(dark = true, layout = LayoutMode.SPLIT_1X2)
        askCameraAndMicrophone()
        askStorageAccess()
        composeRule.runOnUiThread {
            browserVm.focusPane(1)
            browserVm.openFindInPage()
        }
        waitOutActionDelay()
        save("perm-05-prompt-split-dark")
    }

    @Test
    fun promptSplitPanesLight() {
        launch(dark = false, layout = LayoutMode.SPLIT_1X2)
        askCameraAndMicrophone()
        askStorageAccess()
        composeRule.runOnUiThread {
            browserVm.focusPane(1)
            browserVm.openFindInPage()
        }
        waitOutActionDelay()
        save("perm-06-prompt-split-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun promptPhoneDark() {
        launch(dark = true)
        askCameraAndMicrophone()
        waitOutActionDelay()
        save("perm-07-prompt-phone-dark")
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        save("perm-08-prompt-phone-dark-with-find")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun promptPhoneLight() {
        launch(dark = false)
        composeRule.runOnUiThread {
            engine.simulatePermissionRequest("meet", "https://meet.google.com", setOf(SitePermissionKind.NOTIFICATIONS))
        }
        waitOutActionDelay()
        save("perm-09-prompt-phone-light-notifications")
    }

    @Test
    fun promptLocalNetworkWideDark() {
        launch(dark = true, firstTab = plexTab)
        askLocal(SitePermissionKind.LOCAL_NETWORK)
        waitOutActionDelay()
        save("perm-14-prompt-local-network-wide-dark")
    }

    @Test
    fun promptLocalNetworkWideLight() {
        launch(dark = false, firstTab = plexTab)
        askLocal(SitePermissionKind.LOCAL_NETWORK)
        waitOutActionDelay()
        save("perm-15-prompt-local-network-wide-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun promptThisDevicePhoneDark() {
        launch(dark = true, firstTab = plexTab)
        askLocal(SitePermissionKind.LOCAL_DEVICE)
        waitOutActionDelay()
        save("perm-16-prompt-this-device-phone-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun promptThisDevicePhoneLight() {
        launch(dark = false, firstTab = plexTab)
        askLocal(SitePermissionKind.LOCAL_DEVICE)
        waitOutActionDelay()
        save("perm-17-prompt-this-device-phone-light")
    }

    private fun seedDecisions() {
        fun entry(origin: String, kind: SitePermissionKind, state: SitePermissionState, embedded: String? = null) =
            SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, state, embedded)
        engine.storedPermissions += listOf(
            entry("https://meet.google.com", SitePermissionKind.CAMERA, SitePermissionState.ALLOW),
            entry("https://meet.google.com", SitePermissionKind.MICROPHONE, SitePermissionState.ALLOW),
            entry("https://www.openstreetmap.org", SitePermissionKind.LOCATION, SitePermissionState.BLOCK),
            entry("https://web.whatsapp.com", SitePermissionKind.NOTIFICATIONS, SitePermissionState.ALLOW),
            entry("https://www.netflix.com", SitePermissionKind.PROTECTED_CONTENT, SitePermissionState.ALLOW),
            entry(
                "https://news.example.com",
                SitePermissionKind.STORAGE_ACCESS,
                SitePermissionState.ALLOW,
                embedded = "https://comments.disqus.com"
            ),
            entry("http://192.168.1.20:8080", SitePermissionKind.CAMERA, SitePermissionState.BLOCK),
            entry("https://app.plex.tv", SitePermissionKind.LOCAL_NETWORK, SitePermissionState.ALLOW),
            entry("https://app.plex.tv", SitePermissionKind.LOCAL_DEVICE, SitePermissionState.BLOCK)
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h1100dp-land-mdpi")
    fun settingsDark() {
        seedDecisions()
        launch(dark = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SITE_PERMISSIONS) }
        save("perm-10-settings-dark")
    }

    @Test
    @Config(qualifiers = "w1280dp-h1100dp-land-mdpi")
    fun settingsLight() {
        seedDecisions()
        launch(dark = false)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SITE_PERMISSIONS) }
        save("perm-11-settings-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-port-xhdpi")
    fun settingsPhoneDark() {
        seedDecisions()
        launch(dark = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SITE_PERMISSIONS) }
        save("perm-12-settings-phone-dark")
    }

    @Test
    fun settingsEmptyLight() {
        launch(dark = false)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SITE_PERMISSIONS) }
        save("perm-13-settings-empty-light")
    }
}

/** Stand-in for web content, as in [UiSnapshotTest]: enough to judge the card against a page. */
@Composable
private fun SamplePage(tab: BrowserTab, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFFF7F5F2))
            .padding(32.dp)
    ) {
        Text(text = tab.displayTitle, color = Color(0xFF1B1B1F), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(text = tab.url, color = Color(0xFF5A5D66), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        repeat(8) { index ->
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(if (index % 2 == 0) 0.9f else 0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE2DFDA))
            )
        }
    }
}
