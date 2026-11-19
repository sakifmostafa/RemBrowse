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
    }
}
