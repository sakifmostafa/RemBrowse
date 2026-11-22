package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.engine.FindResult
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.ArchivedAdBlocker
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.InstalledExtension
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.rembrowser.rembrowse.model.DropZone
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to PNG files without a device, for design review.
 *
 * Skipped unless a target directory is given:
 * `./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots`
 *
 * Web content is a placeholder, since the real engine cannot run on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class UiSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File? = System.getProperty("rembrowse.screenshots")?.let(::File)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val chrome = BrowserChromeState()
    private lateinit var browserVm: BrowserViewModel
    private lateinit var extVm: ExtensionsViewModel
    private lateinit var downloadsVm: DownloadsViewModel

    @Before
    fun setUp() {
        assumeTrue("Pass -Prembrowse.screenshots=<dir> to render snapshots", outputDir != null)
        outputDir!!.mkdirs()
    }

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(
        settings: BrowserSettings = BrowserSettings(),
        layout: LayoutMode = LayoutMode.SINGLE_1X1,
        startOnNewTab: Boolean = false,
        withExtensions: Boolean = false,
        downloads: List<DownloadItem> = emptyList()
    ) {
        val tabs = listOf(
            BrowserTab(id = "arch", url = "https://www.archdaily.com/", title = "ArchDaily"),
            BrowserTab(id = "android", url = "https://developer.android.com/", title = "Android Developers"),
            BrowserTab(id = "github", url = "https://github.com/", title = "GitHub"),
            BrowserTab(id = "new")
        )
        val bookmarks = listOf("Discord", "Reddit", "Calendar", "Outlook").mapIndexed { index, name ->
            BookmarkItem(id = "b$index", title = name, url = "https://${name.lowercase()}.com/", createdAtMillis = 0L)
        }
        val panes = when {
            startOnNewTab -> listOf("new")
            layout == LayoutMode.SINGLE_1X1 -> listOf("arch")
            else -> listOf("android", "arch", "github", "new").take(layout.paneCount)
        }
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = layout,
                    tabs = tabs,
                    paneTabIds = panes,
                    bookmarks = bookmarks,
                    settings = settings
                )
            )
        )
        val extensionManager = FakeExtensionManager()
        val amo = FakeAmoApiClient()
        if (withExtensions) {
            extensionManager.installedExtensions.value = listOf(
                InstalledExtension(
                    id = DefaultAdBlocker.ID,
                    name = DefaultAdBlocker.NAME,
                    version = "5.2.1",
                    description = "Blocks ads and trackers on the sites you visit.",
                    enabled = true,
                    hasBrowserAction = true,
                    badgeText = "12",
                    icon = sampleIcon(0xFF68BC71.toInt())
                ),
                InstalledExtension(
                    id = "vpn@proton.ch",
                    name = "Proton VPN: Fast & Secure",
                    version = "1.2.9",
                    description = "Free, fast and secure VPN.",
                    enabled = true,
                    hasBrowserAction = true,
                    icon = sampleIcon(0xFF6D4AFF.toInt())
                ),
                InstalledExtension(
                    id = "addon@darkreader.org",
                    name = "Dark Reader",
                    version = "4.9.110",
                    description = "Dark mode for every website.",
                    enabled = false
                )
            )
            amo.featuredResult = Result.success(
                listOf(
                    sampleAddon(DefaultAdBlocker.ID, DefaultAdBlocker.NAME, "Raymond Hill", 4.8, "An efficient wide-spectrum content blocker."),
                    sampleAddon(ArchivedAdBlocker.ID, ArchivedAdBlocker.NAME, "Adguard Software Ltd", 4.7, "Blocks ads, trackers and annoyances on every site."),
                    sampleAddon("{446900e4-71c2-419f-a6a7-df9c091e268b}", "Bitwarden Password Manager", "Bitwarden Inc.", 4.6, "A secure password manager for all of your devices."),
                    sampleAddon("sponsorBlocker@ajay.app", "SponsorBlock", "Ajay Ramachandran", 4.7, "Skips sponsored segments in videos.")
                )
            )
        }
        extVm = ExtensionsViewModel(extensionManager, amo, uiScope)
        downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(downloads),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        browserVm.downloadHandler = downloadsVm::onDownloadRequested
        engine.sessions.values.forEach { it.simulateSecurityChange(true) }

        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme(darkTheme = settings.useDarkTheme, accent = settings.accent) {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "0.06",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> PlaceholderPage(tab, modifier) },
                        extensionPopupContent = { modifier ->
                            Box(modifier.background(Color(0xFF1B1530)), contentAlignment = Alignment.Center) {
                                Text("Extension popup page", color = Color.White)
                            }
                        }
                    )
                }
            }
        }
    }

    /** A filled circle standing in for an extension's toolbar icon. */
    private fun sampleIcon(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        android.graphics.Canvas(bitmap).drawCircle(32f, 32f, 28f, paint)
        return bitmap
    }

    /** Captures the whole display, so dialogs and menus are composited over the browser. */
    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun newTab() {
        launch(startOnNewTab = true)
        save("01-new-tab")
    }

    @Test
    fun browsing() {
        launch()
        save("02-browsing")
    }

    @Test
    fun tabSwitcher() {
        launch()
        composeRule.onNodeWithTag("tab_switcher_button").performClick()
        save("03-tab-switcher")
    }

    @Test
    fun splitView() {
        launch(layout = LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread { browserVm.focusPane(1) }
        save("04-split-view")
    }

    @Test
    fun splitViewMenu() {
        launch()
        composeRule.onNodeWithTag("split_button").performClick()
        save("04b-split-menu")
    }

    @Test
    fun splitControlsMenu() {
        launch(layout = LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread { browserVm.focusPane(1) }
        composeRule.onNodeWithTag("split_button").performClick()
        save("04c-split-controls-menu")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactSplit() {
        launch(layout = LayoutMode.SPLIT_2X1)
        save("14-compact-split")
    }

    @Test
    fun gridView() {
        launch(layout = LayoutMode.GRID_2X2)
        save("05-grid-view")
    }

    @Test
    fun threePaneLayout() {
        launch(layout = LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread { browserVm.dropTabOnPane("github", targetPaneIndex = 1, zone = DropZone.BOTTOM) }
        save("05b-three-panes")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dropZoneHighlight() {
        launch(layout = LayoutMode.SPLIT_1X2)
        val tile = composeRule.onNodeWithTag("tab_bar_item_github").getUnclippedBoundsInRoot()
        val pane = composeRule.onNodeWithTag("browser_pane_1").getUnclippedBoundsInRoot()
        val target = Offset(
            (pane.left + pane.width * 0.5f - tile.left).value,
            (pane.top + pane.height * 0.85f - tile.top).value
        )
        composeRule.onNodeWithTag("tab_bar_item_github").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(40f, 0f))
            moveTo(target)
        }
        save("05c-drop-zone")
    }

    @Test
    fun pageFullscreen() {
        launch(layout = LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread { engine.sessions.getValue("android").simulateFullScreen(true) }
        save("05d-page-fullscreen")
    }

    @Test
    fun commandPalette() {
        launch(startOnNewTab = true)
        composeRule.runOnUiThread { chrome.showCommandPalette = true }
        save("06-command-palette")
        composeRule.onNodeWithTag("command_palette_input").performTextInput("git")
        save("06b-command-palette-typed")
    }

    @Test
    fun settings() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings() }
        save("07-settings")
    }

    @Test
    fun moreMenu() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        save("08-more-menu")
    }

    @Test
    fun findInPage() {
        launch()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("find_input").performTextInput("design")
        composeRule.runOnUiThread {
            engine.sessions.getValue("arch").answerFind(FindResult("design", found = true, current = 3, total = 12))
        }
        save("12-find-in-page")
    }

    @Test
    fun findInPageNoResultsLightSplit() {
        launch(settings = BrowserSettings(useDarkTheme = false), layout = LayoutMode.SPLIT_1X2)
        composeRule.runOnUiThread {
            browserVm.focusPane(1)
            browserVm.openFindInPage()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("find_input").performTextInput("zeppelin")
        composeRule.onNodeWithTag("find_match_case").performClick()
        composeRule.runOnUiThread {
            engine.sessions.getValue("arch").answerFind(FindResult("zeppelin", found = false, current = 0, total = 0))
        }
        save("12b-find-no-results-light-split")
    }

    @Test
    fun lightTheme() {
        launch(settings = BrowserSettings(useDarkTheme = false), startOnNewTab = true)
        save("09-light-new-tab")
    }

    @Test
    fun lightThemeSplit() {
        launch(settings = BrowserSettings(useDarkTheme = false, accent = AccentColor.BLUE), layout = LayoutMode.SPLIT_1X2)
        save("10-light-blue-split")
    }

    @Test
    fun extensionsManager() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.EXTENSIONS) }
        save("11-extensions")
    }

    @Test
    fun settingsSearch() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.SEARCH) }
        save("07b-settings-search")
    }

    @Test
    fun settingsPrivacy() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.PRIVACY) }
        save("07c-settings-privacy")
    }

    @Test
    fun extensionButtons() {
        launch(withExtensions = true)
        save("20-extension-buttons")
    }

    @Test
    fun extensionPopup() {
        launch(withExtensions = true)
        composeRule.onNodeWithTag("rail_extension_vpn@proton.ch").performClick()
        save("21-extension-popup")
    }

    @Test
    fun extensionsInstalled() {
        launch(withExtensions = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.EXTENSIONS) }
        save("11b-extensions-installed")
    }

    @Test
    fun extensionsFirefoxAddons() {
        launch(withExtensions = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.EXTENSIONS) }
        composeRule.onNodeWithTag("extensions_tab_discover_amo").performClick()
        save("11c-extensions-firefox-addons")
    }

    @Test
    fun settingsLightTheme() {
        launch(settings = BrowserSettings(useDarkTheme = false, accent = AccentColor.TEAL), withExtensions = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.PRIVACY) }
        save("07d-settings-light")
    }

    @Test
    fun settingsAbout() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.ABOUT) }
        save("07e-settings-about")
    }

    @Test
    fun settingsTabs() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TABS) }
        save("07f-settings-tabs")
    }

    @Test
    fun settingsDownloads() {
        launch(downloads = sampleDownloads)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        save("17-settings-downloads")
    }

    @Test
    fun settingsDownloadsEmpty() {
        launch(settings = BrowserSettings(useDarkTheme = false))
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        save("17b-settings-downloads-empty-light")
    }

    @Test
    fun downloadPrompt() {
        launch()
        composeRule.runOnUiThread {
            engine.sessions.getValue("arch").simulateDownload(
                url = "https://www.archdaily.com/press/ArchDaily-media-kit-2026.pdf",
                fileName = "ArchDaily-media-kit-2026.pdf",
                bytes = ByteArray(1_300_000),
                mimeType = "application/pdf"
            )
        }
        save("18-download-prompt")
        composeRule.onNodeWithTag("download_confirm").performClick()
        save("18b-download-finished-notice")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactDownloads() {
        launch(downloads = sampleDownloads)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.DOWNLOADS) }
        save("19-compact-downloads")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactSettings() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings() }
        save("15-compact-settings")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactExtensions() {
        launch(withExtensions = true)
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.EXTENSIONS) }
        save("16-compact-extensions")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactPhone() {
        launch(startOnNewTab = true)
        save("12-compact-new-tab")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun compactPhoneBrowsing() {
        launch()
        save("13-compact-browsing")
    }
}

private val sampleDownloads = listOf(
    DownloadItem(
        id = "d1",
        fileName = "ubuntu-24.04-desktop-amd64.iso",
        sourceUrl = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
        mimeType = "application/x-iso9660-image",
        totalBytes = 6_114_656_256,
        receivedBytes = 2_340_000_000,
        status = DownloadStatus.IN_PROGRESS
    ),
    DownloadItem(
        id = "d2",
        fileName = "ArchDaily-media-kit-2026.pdf",
        sourceUrl = "https://www.archdaily.com/press/ArchDaily-media-kit-2026.pdf",
        mimeType = "application/pdf",
        totalBytes = 13_002_342,
        receivedBytes = 13_002_342,
        status = DownloadStatus.COMPLETED,
        contentUri = "content://downloads/ArchDaily-media-kit-2026.pdf"
    ),
    DownloadItem(
        id = "d3",
        fileName = "quarterly-figures-final-v2-reviewed-by-finance-and-legal.xlsx",
        sourceUrl = "https://intranet.example.com/files/quarterly.xlsx",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        receivedBytes = 48_000,
        status = DownloadStatus.FAILED
    ),
    DownloadItem(
        id = "d4",
        fileName = "holiday-photos.zip",
        sourceUrl = "https://drive.example.org/share/holiday-photos.zip",
        mimeType = "application/zip",
        receivedBytes = 0,
        status = DownloadStatus.CANCELLED
    )
)

private fun sampleAddon(id: String, name: String, author: String, rating: Double, summary: String) = AmoAddon(
    id = id,
    slug = name.lowercase().replace(' ', '-'),
    name = name,
    summary = summary,
    iconUrl = null,
    author = author,
    rating = rating,
    weeklyDownloads = 0,
    version = "1.0",
    xpiUrl = "https://addons.mozilla.org/firefox/downloads/file/1/addon.xpi"
)

/** Stand-in for web content: enough structure to judge how the chrome frames a page. */
@androidx.compose.runtime.Composable
private fun PlaceholderPage(tab: BrowserTab, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFFF7F5F2))
            .padding(32.dp)
    ) {
        Text(text = tab.displayTitle, color = Color(0xFF1B1B1F), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(text = tab.url, color = Color(0xFF5A5D66), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        repeat(5) { index ->
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
