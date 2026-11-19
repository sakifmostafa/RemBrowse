package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.DownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DownloadRequestsTest {

    private val body = ByteArrayInputStream(ByteArray(0))

    @Test
    fun readsNameTypeAndSizeFromHeadersInAnyLetterCase() {
        val request = downloadRequestFrom(
            tabId = "tab-1",
            url = "https://example.com/get?id=7",
            headers = mapOf(
                "content-disposition" to "attachment; filename=\"Q3 report.pdf\"",
                "CONTENT-TYPE" to "Application/PDF; charset=binary",
                "Content-Length" to " 1048576 "
            ),
            skipConfirmation = true,
            body = body
        )

        assertEquals("tab-1", request.tabId)
        assertEquals("Q3 report.pdf", request.fileName)
        assertEquals("application/pdf", request.mimeType)
        assertEquals(1_048_576L, request.contentLength)
        assertEquals("example.com", request.sourceHost)
        assertTrue(request.skipConfirmation)
        assertSame(body, request.body)
    }

    @Test
    fun fallsBackToTheAddressAndAnUnknownSize() {
        val request = downloadRequestFrom(
            tabId = null,
            url = "https://cdn.example.org/builds/app-release.apk",
            headers = mapOf("Content-Length" to "not a number"),
            skipConfirmation = false,
            body = body
        )

        assertEquals("app-release.apk", request.fileName)
        assertEquals("", request.mimeType)
        assertEquals(DownloadItem.UNKNOWN_SIZE, request.contentLength)
        assertFalse(request.skipConfirmation)
    }

    @Test
    fun aHostileNameOrSizeCannotEscapeTheDownloadFolderOrGoNegative() {
        val request = downloadRequestFrom(
            tabId = "tab-1",
            url = "https://example.com/x",
            headers = mapOf(
                "Content-Disposition" to "attachment; filename=\"../../../data/data/evil.so\"",
                "Content-Length" to "-5"
            ),
            skipConfirmation = false,
            body = body
        )

        assertEquals("evil.so", request.fileName)
        assertEquals(DownloadItem.UNKNOWN_SIZE, request.contentLength)
    }

    @Test
    fun namesAFileWithoutAnExtensionFromItsType() {
        val request = downloadRequestFrom(
            tabId = "tab-1",
            url = "blob:https://example.com/3f0c",
            headers = mapOf("content-type" to "image/png"),
            skipConfirmation = false,
            body = body,
            extensionForMime = { if (it == "image/png") "png" else null }
        )

        assertEquals("download.png", request.fileName)
        assertEquals("A blob: address names no site", "", request.sourceHost)
    }
}
