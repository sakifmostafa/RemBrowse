package com.rembrowser.rembrowse.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import com.rembrowser.rembrowse.data.AvailableUpdate
import com.rembrowser.rembrowse.model.DownloadNotice
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.ui.components.DownloadNoticeBar
import com.rembrowser.rembrowse.ui.components.ScreenshotNotice
import com.rembrowser.rembrowse.ui.components.ScreenshotNoticeBar
import com.rembrowser.rembrowse.ui.components.ScreenshotResult
import com.rembrowser.rembrowse.ui.components.UpdateNoticeBar
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the notices (update, download finished and failed, screenshot failed) stacked over the
 * page colour as they are over the bottom of the window, to PNG files for design review, like
 * [UiSnapshotTest]. Skipped unless a target directory is given:
 * `./gradlew testDebugUnitTest -Prembrowse.screenshots=build/ui-screenshots`
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class NoticeSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outputDir: File? = System.getProperty("rembrowse.screenshots")?.let(::File)

    @Before
    fun setUp() {
        assumeTrue("Pass -Prembrowse.screenshots=<dir> to render snapshots", outputDir != null)
        outputDir!!.mkdirs()
    }

    private fun render(dark: Boolean, name: String) {
        composeRule.setContent {
            RemBrowseTheme(darkTheme = dark) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        UpdateNoticeBar(
                            update = AvailableUpdate("0.15.0", 150, "x86_64", "https://example.org/a.apk", 90_000_000L, "a".repeat(64)),
                            onUpdate = {},
                            onDismiss = {}
                        )
                        DownloadNoticeBar(
                            notice = DownloadNotice(itemId = "a", fileName = "report.pdf", status = DownloadStatus.COMPLETED),
                            message = null,
                            onOpen = {},
                            onShowDownloads = {},
                            onDismiss = {}
                        )
                        DownloadNoticeBar(
                            notice = DownloadNotice(itemId = "b", fileName = "setup.exe", status = DownloadStatus.FAILED),
                            message = null,
                            onOpen = {},
                            onShowDownloads = {},
                            onDismiss = {}
                        )
                        ScreenshotNoticeBar(
                            notice = ScreenshotNotice(serial = 1, result = ScreenshotResult.Failed),
                            onDismiss = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun noticesWideDark() = render(dark = true, name = "notices-01-wide-dark")

    @Test
    fun noticesWideLight() = render(dark = false, name = "notices-02-wide-light")

    @Test
    @Config(qualifiers = "w360dp-h720dp-port-xhdpi")
    fun noticesPhoneLight() = render(dark = false, name = "notices-03-phone-light")
}
