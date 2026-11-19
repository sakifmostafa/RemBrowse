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
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
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
        startOnNewTab: Boolean = false
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
        extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
        engine.sessions.values.forEach { it.simulateSecurityChange(true) }

        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme(darkTheme = settings.useDarkTheme, accent = settings.accent) {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        chrome = chrome,
                        versionName = "0.02",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> PlaceholderPage(tab, modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
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
        save("04-split-view")
    }

    @Test
    fun gridView() {
        launch(layout = LayoutMode.GRID_2X2)
        save("05-grid-view")
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
        composeRule.runOnUiThread { chrome.showSettings = true }
        save("07-settings")
    }

    @Test
    fun moreMenu() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        save("08-more-menu")
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
        composeRule.runOnUiThread { chrome.showExtensions = true }
        save("11-extensions")
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
