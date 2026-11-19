package com.rembrowser.rembrowse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ErrorPagesTest {

    @Test
    fun buildHtml_showsTheMessageAndARetryLinkForWebAddresses() {
        val html = ErrorPages.buildHtml("https://example.com/page", "Site not found", "Check the address.")

        assertTrue(html.contains("<h1>Site not found</h1>"))
        assertTrue(html.contains("Check the address."))
        assertTrue(html.contains("""<a class="retry" href="https://example.com/page">Try again</a>"""))
    }

    @Test
    fun buildHtml_escapesTheFailedUrlSoItCannotInjectMarkup() {
        val hostile = "https://example.com/\"><script>alert(1)</script>"
        val html = ErrorPages.buildHtml(hostile, "<b>Title</b>", "A & B")

        assertFalse("Raw script tags must never reach the page", html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("&quot;&gt;"))
        assertTrue(html.contains("&lt;b&gt;Title&lt;/b&gt;"))
        assertTrue(html.contains("A &amp; B"))
    }

    @Test
    fun buildHtml_offersNoRetryLinkForNonWebSchemes() {
        val html = ErrorPages.buildHtml("javascript:alert(1)", "That address isn't valid", "Check it.")
        assertFalse(html.contains("class=\"retry\" href"))
    }

    @Test
    fun buildDataUrl_isASelfContainedBase64Page() {
        val url = ErrorPages.buildDataUrl("https://example.com", "You're offline", "Check your connection.")
        val prefix = "data:text/html;charset=utf-8;base64,"

        assertTrue(url.startsWith(prefix))
        val decoded = String(Base64.getDecoder().decode(url.removePrefix(prefix)), Charsets.UTF_8)
        assertEquals(ErrorPages.buildHtml("https://example.com", "You're offline", "Check your connection."), decoded)
        assertTrue(decoded.contains("You&#39;re offline"))
    }
}
