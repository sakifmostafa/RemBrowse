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

/**
 * Laying out a page that takes longer than this is taken to have failed: the page that asked is
 * let go, and printing works again.
 */
private const val PREPARING_TIMEOUT_MS = 60 * 1000L

/**
 * Lays a tab's page out as a PDF for RemBrowse's print screen ([onReady]), from where it is saved
 * as a PDF or sent on to Android's print dialog for a printer. There are two ways in:
 * - the browser's Print command (menu, Ctrl+P): [GeckoSession.printPageContent] calls
 *   [onPrint] with the session, and the page is laid out here;
 * - the page's own `window.print()` (a "Print" button on the page, or in a PDF form): GeckoView
 *   lays it out and calls [onPrintWithStatus]. The page waits for that result, so it must
 *   always complete, whether or not anything was printed. It completes once the PDF is made.
 *
 * A PDF shown in GeckoView's PDF viewer (pdf.js) is not laid out again: the viewer saves the
 * document itself, with what was typed into its form, as its own Download button does. Laying
 * the viewer out loses the form's contents.
 *
 * GeckoView's own print delegate is not used: it prints nothing unless every view has an
 * activity context delegate, names every job "Firefox" and every saved PDF "Document".
 *
 * GeckoView calls in on the main thread.
 */
internal class GeckoPagePrinter(
    private val appContext: Context,
    private val session: GeckoSession,
    /** Whether the session's page is on screen: a page nobody can see does not print. */
    private val isOnScreen: () -> Boolean,
    /** The page's title, which names the PDF. */
    private val pageTitle: () -> String?,
    /**
     * The PDF is ready, as a file the receiver now owns, with the name it goes by, and whether
     * the page asked for it (`window.print()`) rather than the user (the Print command).
     */
    private val onReady: (file: File, documentName: String, requestedByPage: Boolean) -> Unit
) : GeckoSession.PrintDelegate {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * When a page began to be laid out (0 when none is): a second request meanwhile is
     * dropped, unless the first has taken so long it must have failed.
     */
    private var preparingSince = 0L

    private val preparing: Boolean
        get() = preparingSince != 0L && SystemClock.elapsedRealtime() - preparingSince < PREPARING_TIMEOUT_MS

    override fun onPrint(session: GeckoSession) {
        if (preparing) return
        if (!isOnScreen()) {
            couldNotPrint()
            return
        }
        preparingSince = SystemClock.elapsedRealtime()
        pdfOfPage(laidOut = null).accept(
            { done -> if (done != true) couldNotPrint() },
            { couldNotPrint() }
        )
    }

    override fun onPrint(pdf: InputStream) {
        onPrintWithStatus(pdf)
    }

    override fun onPrintWithStatus(pdf: InputStream): GeckoResult<Boolean> {
        if (preparing || !isOnScreen()) {
            closeQuietly(pdf)
            return GeckoResult.fromValue(false)
        }
        preparingSince = SystemClock.elapsedRealtime()
        val result = GeckoResult<Boolean>()
        pdfOfPage(laidOut = pdf).accept(
            { done ->
                if (done != true) couldNotPrint()
                result.complete(done == true)
            },
            { error ->
                couldNotPrint()
                result.complete(false)
            }
        )
        return result
    }

    /**
     * Makes the page's PDF and hands it to [onReady]: the PDF viewer's own copy of a PDF (with
     * its form filled in), or else [laidOut] when GeckoView already laid the page out (the
     * page's `window.print()`), or else the page laid out now. True once it is handed over;
     * false when it failed, or took so long that the page was let go (a PDF that turns up
     * after that is thrown away).
     */
    private fun pdfOfPage(laidOut: InputStream?): GeckoResult<Boolean> {
        val result = GeckoResult<Boolean>()
        var finished = false
        val finish: (File?) -> Unit = { file ->
            if (finished) {
                file?.delete()
            } else {
                finished = true
                preparingSince = 0L
                laidOut?.let(::closeQuietly)
                if (file != null && isOnScreen()) {
                    onReady(file, printJobName(pageTitle()), laidOut != null)
                    result.complete(true)
                } else {
                    file?.delete()
                    result.complete(false)
                }
            }
        }
        // A content process that hangs or dies may never answer
        mainHandler.postDelayed({ finish(null) }, PREPARING_TIMEOUT_MS)
        val fromLayout = {
            if (laidOut != null) {
                copyInBackground(laidOut, finish)
            } else {
                guard(finish) {
                    session.saveAsPdf().accept(
                        { pdf -> if (pdf == null) finish(null) else copyInBackground(pdf, finish) },
                        { error ->
                            Log.w(TAG, "Could not lay the page out for printing", error)
                            finish(null)
                        }
                    )
                }
            }
        }
        guard(finish) {
            session.isPdfJs().accept(
                { isPdf ->
                    if (isPdf == true) {
                        savedPdf(finish, fallback = fromLayout)
                    } else {
                        fromLayout()
                    }
                },
                { fromLayout() }
            )
        }
        return result
    }

    /** The PDF viewer's copy of its document, with the form as filled in; [fallback] if none. */
    private fun savedPdf(finish: (File?) -> Unit, fallback: () -> Unit) {
        guard(finish) {
            session.pdfFileSaver.save().accept(
                { response ->
                    val body = response?.body
                    if (body == null) fallback() else copyInBackground(body, finish)
                },
                { error ->
                    Log.w(TAG, "The PDF viewer could not save its document", error)
                    fallback()
                }
            )
        }
    }

    /** Runs [block], which calls into GeckoView; if that throws, the request ends unprinted. */
    private inline fun guard(noinline finish: (File?) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not lay the page out for printing", e)
            finish(null)
        }
    }

    private fun copyInBackground(pdf: InputStream, finish: (File?) -> Unit) {
        ioExecutor.execute {
            val file = runCatching { copyToPrintFile(pdf) }
                .onFailure { Log.w(TAG, "Could not keep the page's PDF for printing", it) }
                .getOrNull()
            closeQuietly(pdf)
            mainHandler.post { finish(file) }
        }
    }

    private fun copyToPrintFile(pdf: InputStream): File {
        // Files left behind by a closed app are cleared at startup (clearLeftoverPrintFiles)
        val dir = File(appContext.cacheDir, "print").apply { mkdirs() }
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
        /**
         * Copying a page's PDF to a file reads from Gecko, which must not hold up the main
         * thread. Each copy gets a thread of its own: a stream that stalls (its read never
         * returns, and closing it does not wake it) must not hold up the prints after it.
         */
        private val ioExecutor = Executors.newCachedThreadPool()

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

        /**
         * Deletes every print file left behind, at startup: none is in use yet, and the app may
         * have been closed with the print screen open.
         */
        internal fun clearLeftoverPrintFiles(appContext: Context) {
            ioExecutor.execute {
                File(appContext.cacheDir, "print").listFiles()?.forEach { it.delete() }
            }
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
