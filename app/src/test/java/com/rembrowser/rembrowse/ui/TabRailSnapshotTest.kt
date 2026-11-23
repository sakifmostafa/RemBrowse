package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.TabRail
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
 * Renders the tab rail, with its shelf of pinned sites over a mix of open, on-screen and active
 * tabs, to PNG files for design review, like [UiSnapshotTest]. Skipped unless a target directory
 * is given: `./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots`
 *
 * In the scene: GitHub is the active tab (left pane) and pinned, so its pin has the open dot;
 * The Example Times is on screen in the right pane; a Telegram chat is open in the background
 * (the pinned Telegram at /a/ shows the dot); Android Developers is loading; Gmail, YouTube and
 * Calendar are pinned with no tab open.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabRailSnapshotTest {

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

    private fun scene(settings: BrowserSettings) {
        val tabs = listOf(
            BrowserTab(id = "github", url = "https://github.com/rembrowse/rembrowse", title = "GitHub"),
            BrowserTab(id = "news", url = "https://news.example.com/", title = "The Example Times"),
            BrowserTab(id = "chat", url = "https://web.telegram.org/a/#-1001234567", title = "Telegram"),
            // In the background, still loading
            BrowserTab(id = "docs", url = "https://developer.android.com/", title = "Android Developers", isLoading = true),
            BrowserTab(id = "new")
        )
        val bookmarks = listOf(
            "Telegram" to "https://web.telegram.org/a/",
            "GitHub" to "https://github.com/",
            "Gmail" to "https://mail.google.com/",
            "YouTube" to "https://www.youtube.com/",
            "Calendar" to "https://calendar.google.com/"
        ).mapIndexed { index, (title, url) ->
            BookmarkItem(id = "b$index", title = title, url = url, createdAtMillis = 0L)
        }
        browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = LayoutMode.SPLIT_1X2,
                    tabs = tabs,
                    paneTabIds = listOf("github", "news"),
                    activePaneIndex = 0,
                    bookmarks = bookmarks,
                    settings = settings
                )
            )
        )
        engine.sessions.values.forEach { it.simulateSecurityChange(true) }
    }

    private fun launch(dark: Boolean) {
        val settings = BrowserSettings(useDarkTheme = dark)
        scene(settings)
        val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
        val downloadsVm = DownloadsViewModel(
            storage = FakeDownloadStorage(),
            history = FakeDownloadHistoryStore(emptyList()),
            scope = uiScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme(darkTheme = settings.useDarkTheme, accent = settings.accent) {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "0.13.17",
                        onPickLocalXpi = {},
                        paneViewport = { tab, modifier -> SamplePage(tab, modifier) },
                        extensionPopupContent = {}
                    )
                }
            }
        }
    }

    /** Captures the display, or the part of it from the top left corner [width] by [height]. */
    private fun save(name: String, width: Dp? = null, height: Dp? = null) {
        composeRule.waitForIdle()
        var bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (width != null && height != null) {
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            bitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                (width.value * density).toInt().coerceAtMost(bitmap.width),
                (height.value * density).toInt().coerceAtMost(bitmap.height)
            )
        }
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun railDark() {
        launch(dark = true)
        save("rail-01-dark")
        save("rail-02-dark-closeup", width = 260.dp, height = 560.dp)
    }

    @Test
    fun railLight() {
        launch(dark = false)
        save("rail-03-light")
        save("rail-04-light-closeup", width = 260.dp, height = 560.dp)
    }

    /** The rail in every accent, dark then light, side by side. */
    @Test
    fun railEveryAccent() {
        scene(BrowserSettings())
        val state = browserVm.uiState.value
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                Row(
                    modifier = Modifier
                        .height(720.dp)
                        .background(Color(0xFF808080)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (dark in listOf(true, false)) {
                        for (accent in AccentColor.entries) {
                            RemBrowseTheme(darkTheme = dark, accent = accent) {
                                Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                                    TabRail(
                                        uiState = state,
                                        onOpenTabSwitcher = {},
                                        onSelectTab = {},
                                        onCloseTab = {},
                                        onCloseOtherTabs = {},
                                        onAssignTabToPane = { _, _ -> },
                                        onNewTab = {},
                                        onOpenBookmark = {},
                                        onOpenBookmarkInNewTab = {},
                                        onRemoveBookmark = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val railsWidth = (56 + 4) * AccentColor.entries.size * 2
        save("rail-05-every-accent", width = railsWidth.dp, height = 720.dp)
    }
}

/** Stand-in for web content, as in [UiSnapshotTest]. */
@Composable
private fun SamplePage(tab: BrowserTab, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
