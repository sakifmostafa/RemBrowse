package com.rembrowser.rembrowse.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlResolverTest {

    @Test
    fun resolveInput_preservesHttpAndHttpsUrls() {
        assertEquals("https://mozilla.org/en-US/", UrlResolver.resolveInput("https://mozilla.org/en-US/"))
        assertEquals("http://localhost:8080/test", UrlResolver.resolveInput("http://localhost:8080/test"))
    }

    @Test
    fun resolveInput_addsHttpsToBareDomains() {
        assertEquals("https://github.com", UrlResolver.resolveInput("github.com"))
        assertEquals("https://addons.mozilla.org/en-US/android/", UrlResolver.resolveInput("addons.mozilla.org/en-US/android/"))
        assertEquals("http://localhost:3000", UrlResolver.resolveInput("localhost:3000"))
        assertEquals("http://192.168.1.10:8000", UrlResolver.resolveInput("192.168.1.10:8000"))
    }

    @Test
    fun resolveInput_preservesAboutSchemes() {
        assertEquals("about:blank", UrlResolver.resolveInput("about:blank"))
        assertEquals("about:config", UrlResolver.resolveInput("about:config"))
    }

    @Test
    fun resolveInput_convertsSearchTermsToSearchUrl() {
        assertEquals(
            "https://duckduckgo.com/?q=kotlin+jetpack+compose",
            UrlResolver.resolveInput("kotlin jetpack compose")
        )
        assertEquals(
            "https://duckduckgo.com/?q=weather",
            UrlResolver.resolveInput("  weather  ")
        )
    }

    @Test
    fun extractDisplayHost_stripsWwwAndExtractsDomain() {
        assertEquals("github.com", UrlResolver.extractDisplayHost("https://www.github.com/mozilla"))
        assertEquals("addons.mozilla.org", UrlResolver.extractDisplayHost("https://addons.mozilla.org/api/v5/"))
        assertEquals("about:blank", UrlResolver.extractDisplayHost("about:blank"))
    }

    @Test
    fun isValidExtensionUri_validatesSupportedSchemes() {
        assertTrue(UrlResolver.isValidExtensionUri("https://addons.mozilla.org/firefox/downloads/file/1234/ublock.xpi"))
        assertTrue(UrlResolver.isValidExtensionUri("file:///data/user/0/com.rembrowser.rembrowse/cache/ext.xpi"))
        assertTrue(UrlResolver.isValidExtensionUri("resource://android/assets/ext/"))
        assertFalse(UrlResolver.isValidExtensionUri(""))
        assertFalse(UrlResolver.isValidExtensionUri("javascript:alert(1)"))
        // Extension packages are code: never accept them over an unencrypted connection
        assertFalse(UrlResolver.isValidExtensionUri("http://example.com/extension.xpi"))
    }

    @Test
    fun resolveInput_treatsNumbersAndVersionsAsSearchesNotHosts() {
        assertEquals("https://duckduckgo.com/?q=3.14", UrlResolver.resolveInput("3.14"))
        assertEquals("https://duckduckgo.com/?q=v1.2.3", UrlResolver.resolveInput("v1.2.3"))
        assertEquals("https://duckduckgo.com/?q=e.g", UrlResolver.resolveInput("e.g"))
        // ...while real addresses, including ones with a query or an IDN host, still load
        assertEquals("https://example.com?q=1", UrlResolver.resolveInput("example.com?q=1"))
        assertEquals("https://münchen.de", UrlResolver.resolveInput("münchen.de"))
        assertEquals("http://localhost", UrlResolver.resolveInput("localhost"))
    }

    @Test
    fun resolveInput_sendsSearchesToTheChosenEngine() {
        val google = "https://www.google.com/search?q="
        assertEquals("https://www.google.com/search?q=kotlin+flow", UrlResolver.resolveInput("kotlin flow", google))
        assertEquals("An address is not a search", "https://github.com", UrlResolver.resolveInput("github.com", google))
        assertEquals("rem://settings/privacy", UrlResolver.resolveInput("rem://settings/privacy", google))
    }

    @Test
    fun resolveInput_blankInputMeansTheStartPage() {
        assertEquals("about:blank", UrlResolver.resolveInput("   "))
    }

    @Test
    fun toDisplayUrl_keepsTheAddressButTidiesTheRootSlash() {
        assertEquals("https://www.archdaily.com", UrlResolver.toDisplayUrl("https://www.archdaily.com/"))
        assertEquals("https://example.com/docs/", UrlResolver.toDisplayUrl("https://example.com/docs/"))
        assertEquals("http://localhost:3000", UrlResolver.toDisplayUrl("http://localhost:3000/"))
        assertEquals("about:config", UrlResolver.toDisplayUrl("about:config"))
        assertEquals("The start page shows an empty address bar", "", UrlResolver.toDisplayUrl("about:blank"))
    }

    @Test
    fun isWebUrl_acceptsOnlyHttpAndHttps() {
        assertTrue(UrlResolver.isWebUrl("https://example.com"))
        assertTrue(UrlResolver.isWebUrl("HTTP://example.com"))
        assertFalse(UrlResolver.isWebUrl("file:///data/data/com.rembrowser.rembrowse/files/secret"))
        assertFalse(UrlResolver.isWebUrl("javascript:alert(1)"))
        assertFalse(UrlResolver.isWebUrl("about:config"))
        assertFalse(UrlResolver.isWebUrl("moz-extension://abc/options.html"))
        assertFalse(UrlResolver.isWebUrl("intent://example.com#Intent;end"))
    }

    @Test
    fun faviconUrl_pointsAtTheSiteRootAndSkipsNonWebPages() {
        assertEquals("https://github.com/favicon.ico", UrlResolver.faviconUrl("https://github.com/mozilla/gecko?tab=1"))
        assertEquals("http://localhost:3000/favicon.ico", UrlResolver.faviconUrl("http://localhost:3000/app"))
        assertEquals(null, UrlResolver.faviconUrl("about:blank"))
        assertEquals(null, UrlResolver.faviconUrl("file:///sdcard/page.html"))
    }

    @Test
    fun monogram_usesTheFirstLetterOfTheHost() {
        assertEquals("G", UrlResolver.monogram("https://www.github.com/mozilla"))
        assertEquals("1", UrlResolver.monogram("http://192.168.1.10:8000"))
        assertEquals("C", UrlResolver.monogram("about:config"))
        assertEquals("N", UrlResolver.monogram("about:blank", "New tab"))
        assertEquals("•", UrlResolver.monogram("", ""))
    }
}
