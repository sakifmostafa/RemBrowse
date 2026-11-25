package com.rembrowser.rembrowse.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A site, as storage access is granted to an embedded page's whole site: the scheme and the
 * registrable domain, which [SiteOrigins.siteOf] reads from OkHttp's list of public suffixes.
 *
 * Since OkHttp 5 that list is an Android asset, read through the application's context, which
 * OkHttp is given at start-up in the app (AndroidX Startup) and by [OkHttp.initialize] here:
 * Robolectric does not run AndroidX Startup, and a test without Robolectric has no context at
 * all. Without it the lookup throws, and [SiteOrigins.siteOf] quietly falls back to the whole
 * host, so a test could pass on the fallback for an IP address or a single-label host while the
 * list was never read. [theListOfPublicSuffixesIsRead] fails when the list is missing (tried with
 * the call above left out, and without Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SiteOriginsSiteTest {

    @Before
    fun giveOkHttpTheContext() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun theListOfPublicSuffixesIsRead() {
        // Fails when the list is missing: the fallback would give the whole host
        assertEquals("https://b.co.uk", SiteOrigins.siteOf("https://a.b.co.uk/"))
    }

    @Test
    fun aSiteIsTheSchemeAndRegistrableDomain() {
        assertEquals("https://embed.com", SiteOrigins.siteOf("https://www.embed.com:8443/frame"))
        assertEquals("https://embed.co.uk", SiteOrigins.siteOf("https://login.embed.co.uk/"))
        assertEquals("http://192.168.1.20", SiteOrigins.siteOf("http://192.168.1.20:8080/"))
        assertEquals("http://localhost", SiteOrigins.siteOf("http://localhost:3000/"))
        assertNull(SiteOrigins.siteOf("about:blank"))
    }

    @Test
    fun aBarePublicSuffixIsItsOwnSite() {
        assertEquals("https://co.uk", SiteOrigins.siteOf("https://co.uk/"))
    }
}
