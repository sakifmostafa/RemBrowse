package com.rembrowser.rembrowse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNamesTest {

    @Test
    fun contentDisposition_quotedPlainAndEncodedNames() {
        assertEquals("report.pdf", DownloadFileNames.fromContentDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("report.pdf", DownloadFileNames.fromContentDisposition("attachment; filename=report.pdf"))
        assertEquals("a \"b\".txt", DownloadFileNames.fromContentDisposition("attachment; filename=\"a \\\"b\\\".txt\""))
        assertEquals("FILENAME is matched in any case", "x.zip", DownloadFileNames.fromContentDisposition("Attachment; FileName=x.zip"))
        assertNull(DownloadFileNames.fromContentDisposition("inline"))
        assertNull(DownloadFileNames.fromContentDisposition(null))
        assertNull(DownloadFileNames.fromContentDisposition("attachment; filename=\"\""))
    }

    @Test
    fun contentDisposition_encodedNameWinsAndKeepsPlusSigns() {
        // RFC 6266: filename* takes precedence over filename
        assertEquals(
            "naïve résumé.pdf",
            DownloadFileNames.fromContentDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''na%C3%AFve%20r%C3%A9sum%C3%A9.pdf"
            )
        )
        assertEquals("c++ notes.txt", DownloadFileNames.fromContentDisposition("attachment; filename*=UTF-8''c++%20notes.txt"))
        assertEquals("€ rates.csv", DownloadFileNames.fromContentDisposition("attachment; filename*=utf-8'en'%E2%82%AC%20rates.csv"))
    }

    @Test
    fun fromUrl_usesTheLastPathSegmentDecoded() {
        assertEquals("setup.exe", DownloadFileNames.fromUrl("https://example.com/files/setup.exe?token=abc#top"))
        assertEquals("my file.zip", DownloadFileNames.fromUrl("https://example.com/dl/my%20file.zip"))
        assertEquals("dl", DownloadFileNames.fromUrl("https://example.com/dl/"))
        assertNull(DownloadFileNames.fromUrl("https://example.com/"))
        assertNull(DownloadFileNames.fromUrl("https://example.com"))
        assertNull("A data: address has no name in it", DownloadFileNames.fromUrl("data:text/plain;base64,aGVsbG8="))
        assertNull(DownloadFileNames.fromUrl("blob:https://example.com/0b1c"))
    }

    @Test
    fun sanitize_neverLeavesAPathOrAHiddenFile() {
        assertEquals("passwd", DownloadFileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", DownloadFileNames.sanitize("..\\..\\windows\\evil.sh"))
        assertEquals("bashrc", DownloadFileNames.sanitize(".bashrc"))
        assertEquals("a_b_c_.txt", DownloadFileNames.sanitize("a:b|c?.txt"))
        assertEquals("line_break.txt", DownloadFileNames.sanitize("line\nbreak.txt"))
        assertEquals("trailing", DownloadFileNames.sanitize("  trailing. . "))
        assertEquals("download", DownloadFileNames.sanitize(""))
        assertEquals("download", DownloadFileNames.sanitize("..."))
        assertEquals("download", DownloadFileNames.sanitize("/"))
    }

    @Test
    fun sanitize_shortensLongNamesButKeepsTheExtension() {
        val long = "a".repeat(300) + ".tar"
        val result = DownloadFileNames.sanitize(long)
        assertEquals(120, result.length)
        assertTrue(result.endsWith(".tar"))
    }

    @Test
    fun resolve_prefersTheHeaderThenTheAddressThenAFallback() {
        assertEquals(
            "invoice.pdf",
            DownloadFileNames.resolve("https://example.com/get?id=7", "attachment; filename=\"invoice.pdf\"", "application/pdf")
        )
        assertEquals("photo.jpg", DownloadFileNames.resolve("https://example.com/img/photo.jpg", null, "image/jpeg"))
        assertEquals("download", DownloadFileNames.resolve("blob:https://example.com/1", null, null))
        // A hostile header cannot place the file anywhere but the download folder
        assertEquals(
            "id_rsa",
            DownloadFileNames.resolve("https://example.com/x", "attachment; filename=\"../../.ssh/id_rsa\"", null)
        )
    }

    @Test
    fun resolve_addsAnExtensionOnlyWhenTheNameHasNone() {
        val extensions = mapOf("application/pdf" to "pdf", "image/png" to ".png", "weird/type" to "../x")
        val lookup: (String) -> String? = { extensions[it] }

        assertEquals("export.pdf", DownloadFileNames.resolve("https://example.com/export", null, "Application/PDF", lookup))
        assertEquals("pic.png", DownloadFileNames.resolve("https://example.com/pic", null, "image/png", lookup))
        assertEquals("Kept as named", "data.csv", DownloadFileNames.resolve("https://example.com/data.csv", null, "application/pdf", lookup))
        assertEquals("Unknown type", "thing", DownloadFileNames.resolve("https://example.com/thing", null, "application/x-unknown", lookup))
        assertEquals("A nonsense extension is ignored", "thing", DownloadFileNames.resolve("https://example.com/thing", null, "weird/type", lookup))
    }

    @Test
    fun unique_numbersATakenNameBeforeItsExtension() {
        val taken = mutableSetOf("report.pdf", "report (1).pdf", "notes")
        assertEquals("report (2).pdf", DownloadFileNames.unique("report.pdf") { it in taken })
        assertEquals("notes (1)", DownloadFileNames.unique("notes") { it in taken })
        assertEquals("free.txt", DownloadFileNames.unique("free.txt") { it in taken })
    }

    @Test
    fun extensionOf_ignoresLeadingAndTrailingDots() {
        assertEquals("gz", DownloadFileNames.extensionOf("archive.tar.GZ"))
        assertEquals("", DownloadFileNames.extensionOf("README"))
        assertEquals("", DownloadFileNames.extensionOf(".hidden"))
        assertEquals("", DownloadFileNames.extensionOf("odd."))
        assertFalse(DownloadFileNames.extensionOf("a.b").isEmpty())
    }

    @Test
    fun byteSizes_readNaturally() {
        assertEquals("0 B", ByteSizes.format(0))
        assertEquals("512 B", ByteSizes.format(512))
        assertEquals("1.0 KB", ByteSizes.format(1024))
        assertEquals("1.5 KB", ByteSizes.format(1536))
        assertEquals("12.4 MB", ByteSizes.format(13_002_342))
        assertEquals("512 MB", ByteSizes.format(512L * 1024 * 1024))
        assertEquals("1.2 GB", ByteSizes.format(1_288_490_189))
        assertEquals("Unknown sizes are left out", "", ByteSizes.format(-1))
    }
}
