package com.rembrowser.rembrowse.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
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
import com.rembrowser.rembrowse.model.ThemeMode
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import java.io.File
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

/**
 * Renders the screenshot overlay (with a selection, and lighting up a pane) and the notice after
 * it to PNG files, for design review, like [UiSnapshotTest]. Skipped unless a target directory is
 * given: `./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots`
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi-keysexposed-qwerty")
class ScreenCaptureSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File? = System.getProperty("rembrowse.screenshots")?.let(::File)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val chrome = BrowserChromeState()
    private var copies = true

    @Before
    fun setUp() {
        assumeTrue("Pass -Prembrowse.screenshots=<dir> to render snapshots", outputDir != null)
        outputDir!!.mkdirs()
    }

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    private fun launch(dark: Boolean, layout: LayoutMode = LayoutMode.SPLIT_1X2) {
        val tabs = listOf(
            BrowserTab(id = "chat", url = "https://web.telegram.org/a/", title = "Telegram Web"),
            BrowserTab(id = "news", url = "https://news.example.com/", title = "The Example Times")
        )
        val settings = BrowserSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val browserVm = BrowserViewModel(
            engine,
            FakeBrowserStateRepository(
                PersistedBrowserState(
                    layoutMode = layout,
                    tabs = tabs,
                    paneTabIds = listOf("chat", "news").take(layout.paneCount),
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
                        chrome = chrome,
                        versionName = "0.13.17",
                        onPickLocalXpi = {},
                        onCaptureScreen = { area ->
                            if (copies) {
                                ScreenshotResult.Copied(sampleThumbnail(area.width, area.height), area.width, area.height)
                            } else {
                                ScreenshotResult.Failed
                            }
                        },
                        paneViewport = { tab, modifier -> SamplePage(tab, modifier) },
                        extensionPopupContent = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startCapture() {
        composeRule.runOnUiThread { chrome.screenCapture.start() }
        composeRule.waitForIdle()
        // Past the overlay's fade-in
        composeRule.mainClock.advanceTimeBy(300)
    }

    private val overlay get() = composeRule.onNodeWithTag("screen_capture_overlay")

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Half-way through a drag across both panes, still pressed. */
    private fun dragAcrossThePanes() {
        overlay.performMouseInput {
            moveTo(Offset(420f, 180f))
            press()
            moveTo(Offset(700f, 330f))
            moveTo(Offset(980f, 470f))
        }
    }

    @Test
    fun selectionWideDark() {
        launch(dark = true)
        startCapture()
        save("capture-01-overlay-wide-dark")
        dragAcrossThePanes()
        save("capture-02-selection-wide-dark")
    }

    @Test
    fun selectionWideLight() {
        launch(dark = false)
        startCapture()
        dragAcrossThePanes()
        save("capture-03-selection-wide-light")
    }

    @Test
    fun paneUnderTheMouseWideLight() {
        launch(dark = false)
        startCapture()
        overlay.performMouseInput { moveTo(Offset(900f, 400f)) }
        save("capture-04-hover-pane-wide-light")
    }

    @Test
    fun selectionNearTheBottomWideDark() {
        launch(dark = true)
        startCapture()
        // Little room under it: the size goes above; it reaches under the hint, which steps aside
        overlay.performMouseInput {
            moveTo(Offset(300f, 30f))
            press()
            moveTo(Offset(900f, 790f))
        }
        save("capture-05-selection-tall-dark")
    }

    @Test
    fun noticeWideDark() {
        launch(dark = true)
        startCapture()
        overlay.performMouseInput {
            moveTo(Offset(420f, 180f))
            press()
            moveTo(Offset(980f, 470f))
            release()
        }
        save("capture-06-notice-wide-dark")
    }

    @Test
    fun noticeWideLight() {
        launch(dark = false)
        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        save("capture-07-notice-wide-light")
    }

    @Test
    fun failedWideLight() {
        copies = false
        launch(dark = false)
        startCapture()
        overlay.performKeyInput { pressKey(Key.Enter) }
        save("capture-08-failed-wide-light")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun selectionPhoneDark() {
        launch(dark = true, layout = LayoutMode.SINGLE_1X1)
        startCapture()
        save("capture-09-overlay-phone-dark")
        overlay.performTouchInput {
            down(Offset(80f, 400f))
            moveTo(Offset(300f, 700f))
            moveTo(Offset(600f, 1000f))
        }
        save("capture-10-selection-phone-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun selectionPhoneLight() {
        launch(dark = false, layout = LayoutMode.SINGLE_1X1)
        startCapture()
        overlay.performTouchInput {
            down(Offset(80f, 400f))
            moveTo(Offset(600f, 1000f))
        }
        save("capture-11-selection-phone-light")
    }
}

/** A small picture standing in for the screenshot: a page with a header and lines of text. */
private fun sampleThumbnail(width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap {
    val h = 80
    val w = (width.toFloat() * h / height).toInt().coerceIn(1, 320)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFF7F5F2.toInt())
    val paint = Paint().apply { color = 0xFF2F5AA8.toInt() }
    canvas.drawRect(0f, 0f, w.toFloat(), h * 0.2f, paint)
    paint.color = 0xFFE2DFDA.toInt()
    for (line in 0 until 4) {
        val top = h * (0.32f + line * 0.16f)
        canvas.drawRect(w * 0.08f, top, w * (if (line % 2 == 0) 0.9f else 0.7f), top + h * 0.07f, paint)
    }
    return bitmap.asImageBitmap()
}

/** Stand-in for web content, as in [UiSnapshotTest]: enough to judge the overlay against a page. */
@Composable
private fun SamplePage(tab: BrowserTab, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFFF7F5F2))
            .padding(32.dp)
    ) {
        Text(text = tab.displayTitle, color = Color(0xFF1B1B1F), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(text = tab.url, color = Color(0xFF5A5D66), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        repeat(10) { index ->
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
