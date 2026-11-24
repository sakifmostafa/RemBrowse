package com.rembrowser.rembrowse.engine

import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.print.layoutCallback
import android.print.writeCallback
import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GeckoPagePrinterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("print-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- printJobName ---

    @Test
    fun printJobName_keepsANormalTitle() {
        assertEquals("Example Domain", GeckoPagePrinter.printJobName("Example Domain"))
        assertEquals("Café – menu 2026", GeckoPagePrinter.printJobName("Café – menu 2026"))
    }

    @Test
    fun printJobName_turnsUnsafeCharactersAndControlsIntoSingleSpaces() {
        assertEquals("a b", GeckoPagePrinter.printJobName("a/b"))
        assertEquals("a b c d e f g h i j", GeckoPagePrinter.printJobName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("Q A", GeckoPagePrinter.printJobName("Q: ?? A"))
        assertEquals("one two three", GeckoPagePrinter.printJobName("one\ttwo\nthree\u0000"))
        assertEquals("trimmed", GeckoPagePrinter.printJobName("  /trimmed/  "))
    }

    @Test
    fun printJobName_fallsBackToPage() {
        assertEquals("Page", GeckoPagePrinter.printJobName(null))
        assertEquals("Page", GeckoPagePrinter.printJobName(""))
        assertEquals("Page", GeckoPagePrinter.printJobName("   \t "))
        assertEquals("Page", GeckoPagePrinter.printJobName("/\\:*?\"<>|"))
    }

    @Test
    fun printJobName_capsAtOneHundredCharactersWithNoTrailingSpace() {
        val long = GeckoPagePrinter.printJobName("x".repeat(300))
        assertEquals(100, long.length)

        // The 100th character would be a space: it must not be left dangling
        val title = "a".repeat(99) + " " + "b".repeat(50)
        val name = GeckoPagePrinter.printJobName(title)
        assertEquals("a".repeat(99), name)
        assertFalse(name.endsWith(" "))
        assertTrue(GeckoPagePrinter.printJobName("word ".repeat(60)).length <= 100)
    }

    // --- activityOf ---

    @Test
    fun activityOf_unwrapsContextWrappers() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertNull(GeckoPagePrinter.activityOf(null))
        assertNull(GeckoPagePrinter.activityOf(app))
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).get()
        assertSame(activity, GeckoPagePrinter.activityOf(activity))
        assertSame(activity, GeckoPagePrinter.activityOf(ContextWrapper(ContextWrapper(activity))))
        assertNull(GeckoPagePrinter.activityOf(ContextWrapper(ContextWrapper(app))))
    }

    // --- PdfFilePrintAdapter ---

    private fun pdfFile(bytes: ByteArray): File = File(dir, "page.pdf").apply { writeBytes(bytes) }

    /** Waits for [latch], letting the main looper run what the adapter posts to it. */
    private fun await(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 10_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            latch.await(20, TimeUnit.MILLISECONDS)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Timed out waiting for the adapter", 0L, latch.count)
    }

    @Test
    fun adapter_layoutReportsTheNameAndDocumentType() {
        val adapter = PdfFilePrintAdapter(pdfFile(byteArrayOf(1)), "My Page") {}
        var info: PrintDocumentInfo? = null
        var changed: Boolean? = null
        adapter.onLayout(
            null,
            PrintAttributes.Builder().build(),
            CancellationSignal(),
            layoutCallback(onFinished = { i, c ->
                info = i
                changed = c
            }),
            null
        )
        assertEquals("My Page", info!!.name)
        assertEquals(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT, info!!.contentType)
        assertEquals(true, changed)
    }

    @Test
    fun adapter_layoutCancelled_reportsCancelled() {
        val adapter = PdfFilePrintAdapter(pdfFile(byteArrayOf(1)), "My Page") {}
        var cancelled = false
        val signal = CancellationSignal().apply { cancel() }
        adapter.onLayout(
            null,
            PrintAttributes.Builder().build(),
            signal,
            layoutCallback(onCancelled = { cancelled = true }),
            null
        )
        assertTrue(cancelled)
    }

    @Test
    fun adapter_writeCopiesTheFilesExactBytes_thenFinishDeletesItAndCallsOnDone() {
        val bytes = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val file = pdfFile(bytes)
        var done = 0
        val adapter = PdfFilePrintAdapter(file, "My Page") { done++ }
        val target = File(dir, "out.pdf")
        val pfd = ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        val written = CountDownLatch(1)
        var ranges: Array<out PageRange>? = null
        var failure: String? = null
        adapter.onWrite(
            arrayOf(PageRange.ALL_PAGES),
            pfd,
            CancellationSignal(),
            writeCallback(
                onFinished = {
                    ranges = it
                    written.countDown()
                },
                onFailed = {
                    failure = it?.toString()
                    written.countDown()
                }
            )
        )
        await(written)
        pfd.close()

        assertNull(failure)
        assertArrayEquals(arrayOf(PageRange.ALL_PAGES), ranges)
        assertArrayEquals(bytes, FileInputStream(target).use { it.readBytes() })
        assertTrue("The file stays for the dialog's next request", file.exists())
        assertEquals(0, done)

        adapter.onFinish()
        // The delete runs on the adapter's executor
        val deadline = System.currentTimeMillis() + 5_000
        while (file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(file.exists())
        assertEquals(1, done)
    }

    @Test
    fun adapter_writeOfAMissingFile_failsInsteadOfFinishing() {
        val adapter = PdfFilePrintAdapter(File(dir, "gone.pdf"), "My Page") {}
        val target = File(dir, "out.pdf")
        val pfd = ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        val latch = CountDownLatch(1)
        var failure: CharSequence? = null
        var finished = false
        adapter.onWrite(
            null,
            pfd,
            CancellationSignal(),
            writeCallback(
                onFinished = {
                    finished = true
                    latch.countDown()
                },
                onFailed = {
                    failure = it
                    latch.countDown()
                }
            )
        )
        await(latch)
        pfd.close()
        assertFalse(finished)
        assertTrue(failure != null)
        adapter.onFinish()
    }
}
