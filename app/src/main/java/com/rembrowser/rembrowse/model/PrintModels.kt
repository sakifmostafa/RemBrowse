package com.rembrowser.rembrowse.model

/**
 * A page laid out for printing, shown on RemBrowse's print screen until the user saves it as a
 * PDF, sends it to a printer, or cancels.
 */
data class PrintPreview(
    /** The tab whose page this is. */
    val tabId: String,
    /**
     * The laid-out PDF, in the app's cache. The browser owns it until it is handed to a printer
     * ([com.rembrowser.rembrowse.viewmodel.BrowserViewModel.takePrintPreviewForPrinter]) and
     * deletes it when the preview is dismissed.
     */
    val pdfPath: String,
    /** What the PDF is called: the page's title, made safe for a file name. */
    val documentName: String,
    /** The page's address, recorded with the saved PDF in Downloads. */
    val pageUrl: String?,
    /** Goes up with every new preview, so the screen starts over for a new one. */
    val serial: Long
) {
    companion object {
        /** The file name "Save as PDF" suggests for [name]: with ".pdf" on the end, once. */
        fun pdfFileName(name: String): String {
            val trimmed = name.trim().ifEmpty { "Page" }
            return if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
        }
    }
}
