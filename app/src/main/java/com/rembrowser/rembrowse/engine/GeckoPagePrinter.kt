package com.rembrowser.rembrowse.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import android.widget.Toast
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

private const val TAG = "GeckoPagePrinter"

/** Print files older than this are left over from a print the app never finished (it was killed). */
private const val STALE_PRINT_FILE_MS = 24 * 60 * 60 * 1000L

/** Laying out a page that takes longer than this is taken to have failed, so printing still works. */
private const val PREPARING_TIMEOUT_MS = 2 * 60 * 1000L

/**
 * Prints a tab's page through Android's print dialog, which also offers "Save as PDF". GeckoView
 * lays the page out as a PDF (as Firefox's print preview would) and this hands that PDF to the
 * dialog. There are two ways in:
 * - the browser's Print command (menu, Ctrl+P): [GeckoSession.printPageContent] calls
 *   [onPrint] with the session, and the page is laid out here;
 * - the page's own `window.print()` (a "Print" button on the page, or in a PDF form): GeckoView
 *   lays it out and calls [onPrintWithStatus]. The page waits for that result, so it must
 *   always complete, whether or not anything was printed. It completes once the dialog is up:
 *   the PDF is made by then, and Android may drop the dialog (with its activity) without ever
 *   saying it was closed.
 *
 * GeckoView's own print delegate is not used: it prints nothing unless every view has an
 * activity context delegate, names every job "Firefox" and every saved PDF "Document".
 *
 * GeckoView calls in on the main thread.
 */
internal class GeckoPagePrinter(
    private val appContext: Context,
    /** The activity showing the session's page, or null when it is not on screen. */
    private val hostActivity: () -> Activity?,
    /** The page's title, which names the print job and the saved PDF. */
    private val pageTitle: () -> String?
) : GeckoSession.PrintDelegate {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * When a page began to be laid out or copied for the dialog (0 when none is): a second
     * request meanwhile is dropped, unless the first has taken so long it must have failed.
     */
    private var preparingSince = 0L

    private val preparing: Boolean
        get() = preparingSince != 0L && SystemClock.elapsedRealtime() - preparingSince < PREPARING_TIMEOUT_MS

    private fun startPreparing() {
        preparingSince = SystemClock.elapsedRealtime()
    }

    private fun stopPreparing() {
        preparingSince = 0L
    }

    override fun onPrint(session: GeckoSession) {
        if (preparing) return
        if (hostActivity() == null) {
            couldNotPrint()
            return
        }
        startPreparing()
        try {
            session.saveAsPdf().accept(
                { pdf ->
                    if (pdf == null) {
                        stopPreparing()
                        couldNotPrint()
                    } else {
                        printPdf(pdf)
                    }
                },
                { error ->
                    stopPreparing()
                    Log.w(TAG, "Could not lay the page out for printing", error)
                    couldNotPrint()
                }
            )
        } catch (e: RuntimeException) {
            stopPreparing()
            Log.w(TAG, "Could not lay the page out for printing", e)
            couldNotPrint()
        }
    }

    override fun onPrint(pdf: InputStream) {
        onPrintWithStatus(pdf)
    }

    override fun onPrintWithStatus(pdf: InputStream): GeckoResult<Boolean> {
        if (preparing) {
            closeQuietly(pdf)
            return GeckoResult.fromValue(false)
        }
        startPreparing()
        return printPdf(pdf)
    }

    /**
     * Copies [pdf] to a file (the dialog may ask for it more than once, as the printer or the
     * paper changes) and opens the print dialog on it. The result is true once the dialog is
     * up, false when no dialog could be shown.
     */
    private fun printPdf(pdf: InputStream): GeckoResult<Boolean> {
        val result = GeckoResult<Boolean>()
        ioExecutor.execute {
            val file = runCatching { copyToPrintFile(pdf) }
                .onFailure { Log.w(TAG, "Could not keep the page's PDF for printing", it) }
                .getOrNull()
            closeQuietly(pdf)
            mainHandler.post {
                stopPreparing()
                val activity = hostActivity()
                if (file == null || activity == null) {
                    file?.delete()
                    couldNotPrint()
                    result.complete(false)
                    return@post
                }
                val name = printJobName(pageTitle())
                val job = try {
                    val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager?
                    printManager?.print(name, PdfFilePrintAdapter(file, name), PrintAttributes.Builder().build())
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not open the print dialog", e)
                    null
                }
                // Null without an error too: the device has no print service, or the dialog
                // could not start
                if (job == null) {
                    file.delete()
                    couldNotPrint()
                    result.complete(false)
                } else {
                    result.complete(true)
                }
            }
        }
        return result
    }

    private fun copyToPrintFile(pdf: InputStream): File {
        val dir = File(appContext.cacheDir, "print").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_PRINT_FILE_MS) it.delete() }
        val file = File(dir, "${UUID.randomUUID()}.pdf")
        try {
            FileOutputStream(file).use { out -> pdf.copyTo(out) }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return file
    }

    private fun couldNotPrint() {
        Toast.makeText(appContext, "Couldn't print this page", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Copying a page's PDF to a file reads from Gecko, which must not hold up the main thread. */
        private val ioExecutor = Executors.newSingleThreadExecutor()

        private val UNSAFE_NAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
        private const val MAX_NAME_LENGTH = 100

        /**
         * The name of the print job, which is also what "Save as PDF" calls the file: the page's
         * title without characters a file name cannot have, or "Page" for a page without one.
         */
        internal fun printJobName(title: String?): String {
            val cleaned = title.orEmpty()
                .replace(UNSAFE_NAME_CHARACTERS, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NAME_LENGTH)
                .trim()
            return cleaned.ifEmpty { "Page" }
        }

        /** The activity behind [context], which a view's context usually wraps. */
        internal fun activityOf(context: Context?): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return null
        }

        private fun closeQuietly(stream: InputStream) {
            runCatching { stream.close() }
        }
    }
}

/**
 * Hands an already laid-out PDF to the print dialog. Android asks for the document again each
 * time the printer or the paper changes, so it is read from [file], which is deleted once the
 * dialog is done.
 */
internal class PdfFilePrintAdapter(
    private val file: File,
    private val documentName: String,
    private val onDone: () -> Unit = {}
) : PrintDocumentAdapter() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(documentName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        ioExecutor.execute {
            val error = try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                null
            } catch (e: IOException) {
                Log.w(TAG, "Could not hand the PDF to the print dialog", e)
                e.message ?: "Could not read the page"
            }
            mainHandler.post {
                when {
                    cancellationSignal?.isCanceled == true -> callback.onWriteCancelled()
                    error != null -> callback.onWriteFailed(error)
                    else -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            }
        }
    }

    override fun onFinish() {
        // After any write still copying it: one thread does both
        ioExecutor.execute { file.delete() }
        onDone()
    }

    private companion object {
        /** Shared, so an adapter Android drops without [onFinish] leaves no thread behind. */
        val ioExecutor = Executors.newSingleThreadExecutor()
    }
}
