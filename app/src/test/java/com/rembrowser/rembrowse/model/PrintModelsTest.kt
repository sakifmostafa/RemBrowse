package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintModelsTest {
    @Test
    fun pdfFileName_addsPdfOnce() {
        assertEquals("Report.pdf", PrintPreview.pdfFileName("Report"))
        assertEquals("Report.pdf", PrintPreview.pdfFileName("Report.pdf"))
        assertEquals("Report.PDF", PrintPreview.pdfFileName("Report.PDF"))
        assertEquals("Report.pdf", PrintPreview.pdfFileName("  Report  "))
        assertEquals("notes.txt.pdf", PrintPreview.pdfFileName("notes.txt"))
    }

    @Test
    fun pdfFileName_fallsBackToPageForABlankName() {
        assertEquals("Page.pdf", PrintPreview.pdfFileName(""))
        assertEquals("Page.pdf", PrintPreview.pdfFileName("   "))
    }
}
