package com.rembrowser.rembrowse.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.PrintPreview
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.components.PdfPreviewDocument
import com.rembrowser.rembrowse.ui.components.PdfPreviewInfo
import com.rembrowser.rembrowse.ui.components.PrintScreen
import com.rembrowser.rembrowse.ui.components.paperSizeOf
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** RemBrowse's print screen, shown in place of Android's print dialog once a page is laid out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class PrintScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    /** The fake engine, keeping the browser's listener so a test can hand it a laid-out page. */
    private class ListeningEngine(val fake: FakeBrowserEngine = FakeBrowserEngine()) : BrowserEngine by fake {
        var listener: SessionEventListener? = null

        override fun createSession(
            tabId: String,
            initialUrl: String,
            desktopMode: Boolean,
            listener: SessionEventListener
        ): BrowserSessionHandle {
            this.listener = listener
            return fake.createSession(tabId, initialUrl, desktopMode, listener)
        }
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = ListeningEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
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

    private fun launchOnAWebPage(): String {
        composeRule.setContent {
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/")
            engine.fake.sessions.getValue(tab).simulatePageLoad("https://example.com/", "Example", canGoBack = false)
        }
        composeRule.waitForIdle()
        return tab
    }

    /** A valid one-page A4 PDF, as small as one can be. */
    private fun tinyPdf(): File {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << >> >>"
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            pdf.length.also { pdf.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return temp.newFile("page.pdf").apply { writeText(pdf.toString(), Charsets.US_ASCII) }
    }

    @Test
    fun aLaidOutPage_showsItsNameAndTheThreeActions_andCancelDismissesIt() {
        val tab = launchOnAWebPage()
        val pdf = tinyPdf()
        composeRule.runOnUiThread { engine.listener!!.onPrintReady(tab, pdf.path, "Example page") }
        composeRule.waitForIdle()
        assertNotNull(browserVm.printPreview.value)

        composeRule.onNodeWithTag("print_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("print_file_name").assertTextContains("Example page")
        composeRule.onNodeWithText("Save as PDF").assertIsDisplayed()
        composeRule.onNodeWithText("Print…").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertNull(browserVm.printPreview.value)
        assertFalse("Cancelling deletes the laid-out PDF", pdf.exists())
        composeRule.onNodeWithTag("print_screen").assertDoesNotExist()
    }

    /** A document of A4 pages that renders blank ones at once, counting what was asked for. */
    private class FakeDocument(file: File, private val pageCount: Int) : PdfPreviewDocument(file) {
        val renderedPages = mutableListOf<Int>()

        override fun open(onOpened: (PdfPreviewInfo?) -> Unit): Request {
            onOpened(PdfPreviewInfo(pageCount, firstPageWidthPt = 595, firstPageHeightPt = 842))
            return Request()
        }

        override fun render(index: Int, widthPx: Int, onRendered: (Bitmap?) -> Unit): Request {
            renderedPages += index
            onRendered(Bitmap.createBitmap(widthPx, widthPx * 842 / 595, Bitmap.Config.ARGB_8888))
            return Request()
        }

        override fun close() {}
    }

    @Test
    fun aLongDocument_rendersOnlyThePagesInView_andSaysWhatItIs() {
        val document = FakeDocument(tinyPdf(), pageCount = 200)
        composeRule.setContent {
            RemBrowseTheme {
                PrintScreen(
                    preview = PrintPreview("tab", "unused.pdf", "Long read", "https://example.com/", serial = 1),
                    onSaveAsPdf = { true },
                    onPrint = {},
                    onCancel = {},
                    openDocument = { document }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasContentDescription("Page 1 of 200")).assertIsDisplayed()
        composeRule.onNodeWithText("200").assertIsDisplayed()
        composeRule.onNodeWithText("A4 · 210 × 297 mm").assertIsDisplayed()
        assertTrue("Rendered ${document.renderedPages}", document.renderedPages.isNotEmpty() && document.renderedPages.size <= 6)
    }

    @Test
    fun paperSizes_areNamedWhenCommon_andMeasuredOtherwise() {
        assertEquals("A4 · 210 × 297 mm", paperSizeOf(595, 842, Locale.US).fullLabel)
        assertEquals("Letter · 8.5 × 11 in", paperSizeOf(612, 792, Locale.UK).fullLabel)
        assertEquals("Legal landscape", paperSizeOf(1008, 612, Locale.US).shortLabel)
        assertEquals("5.5 × 8.5 in", paperSizeOf(396, 612, Locale.US).fullLabel)
        assertEquals("140 × 216 mm", paperSizeOf(396, 612, Locale.GERMANY).fullLabel)
    }
}
