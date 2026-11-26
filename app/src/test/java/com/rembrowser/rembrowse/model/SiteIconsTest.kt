package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.data.SharedPrefsDeclaredSiteIconStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the icons a site names for itself RemBrowse tries, in what order, and how they are kept. */
class SiteIconsTest {

    private fun icon(url: String, vararg sizes: Int, touch: Boolean = false) =
        DeclaredSiteIcon(url = url, isTouchIcon = touch, sizes = sizes.toList())

    private fun urls(icons: List<DeclaredSiteIcon>) = icons.map { it.url }

    @Test
    fun parseSizes_readsEachWidthAndHeight_andSkipsAnyAndNonsense() {
        assertEquals(listOf(16, 32), SiteIcons.parseSizes("32x32 16x16"))
        assertEquals(listOf(180), SiteIcons.parseSizes(" 180X180 "))
        // Not square: the smaller side is what fits a square tile
        assertEquals(listOf(48), SiteIcons.parseSizes("64x48"))
        assertEquals(emptyList<Int>(), SiteIcons.parseSizes("any"))
        assertEquals(emptyList<Int>(), SiteIcons.parseSizes(""))
        assertEquals(emptyList<Int>(), SiteIcons.parseSizes("big 0x0 99999x99999 -1x-1"))
        assertEquals(listOf(192), SiteIcons.parseSizes("any 192x192"))
    }

    @Test
    fun ranked_prefersTheSmallestIconAtLeastAsLargeAsTheTile_thenLarger_thenSmaller() {
        val icons = listOf(
            icon("https://a.example/16.png", 16),
            icon("https://a.example/32.png", 32),
            icon("https://a.example/192.png", 192),
            icon("https://a.example/64.png", 64),
            icon("https://a.example/512.png", 512)
        )
        assertEquals(
            listOf(
                "https://a.example/64.png",
                "https://a.example/192.png",
                "https://a.example/512.png",
                "https://a.example/32.png",
                "https://a.example/16.png"
            ),
            urls(SiteIcons.ranked(icons, targetPx = 55))
        )
        // On a denser screen the tile needs more pixels
        assertEquals("https://a.example/192.png", SiteIcons.ranked(icons, targetPx = 100).first().url)
    }

    @Test
    fun ranked_putsIconsBeforeTouchIcons_andAnUnsizedIconBeforeATouchIcon() {
        val icons = listOf(
            icon("https://a.example/apple.png", touch = true),
            icon("https://a.example/favicon-16.png", 16),
            icon("https://a.example/first.ico"),
            icon("https://a.example/last.ico"),
            icon("https://a.example/48.png", 48)
        )
        assertEquals(
            listOf(
                "https://a.example/48.png",
                // Of equally good icons the last declared, as browsers take
                "https://a.example/last.ico",
                "https://a.example/first.ico",
                // A home-screen icon, 180 pixels unless it says otherwise
                "https://a.example/apple.png",
                // Smaller than 32 pixels: only when nothing else is there
                "https://a.example/favicon-16.png"
            ),
            urls(SiteIcons.ranked(icons, targetPx = 55))
        )
    }

    @Test
    fun ranked_aSmallTouchIconComesAfterLargerOnesButBeforeNothing() {
        val icons = listOf(icon("https://a.example/t16.png", 16, touch = true), icon("https://a.example/t120.png", 120, touch = true))
        assertEquals(listOf("https://a.example/t120.png", "https://a.example/t16.png"), urls(SiteIcons.ranked(icons, 55)))
    }

    @Test
    fun addressesFor_triesTwoDeclaredIcons_thenTheSitesFaviconIco() {
        // Discord's app names its icon at /assets/favicon.ico; its /favicon.ico is a 404 page
        val declared = mapOf(
            "https://discord.com" to listOf(icon("https://discord.com/assets/favicon.ico")),
            "https://en.wikipedia.org" to listOf(
                icon("https://en.wikipedia.org/static/apple-touch/wikipedia.png", touch = true),
                icon("https://en.wikipedia.org/static/favicon/wikipedia.ico")
            ),
            "https://many.example" to listOf(
                icon("https://cdn.example/a.png", 64),
                icon("https://cdn.example/b.png", 128),
                icon("https://cdn.example/c.png", 256)
            )
        )
        assertEquals(
            listOf("https://discord.com/assets/favicon.ico", "https://discord.com/favicon.ico"),
            SiteIcons.addressesFor("https://discord.com/channels/@me", declared, 55)
        )
        assertEquals(
            listOf(
                "https://en.wikipedia.org/static/favicon/wikipedia.ico",
                "https://en.wikipedia.org/static/apple-touch/wikipedia.png",
                "https://en.wikipedia.org/favicon.ico"
            ),
            SiteIcons.addressesFor("https://en.wikipedia.org/wiki/Firefox", declared, 55)
        )
        assertEquals(
            listOf("https://cdn.example/a.png", "https://cdn.example/b.png", "https://many.example/favicon.ico"),
            SiteIcons.addressesFor("https://many.example/", declared, 55)
        )
    }

    @Test
    fun addressesFor_aSiteThatNamesNone_getsItsFaviconIco_andAPageOffTheWebNothing() {
        val declared = mapOf("https://other.example" to listOf(icon("https://other.example/i.png", 64)))
        assertEquals(listOf("https://github.com/favicon.ico"), SiteIcons.addressesFor("https://github.com/org/repo", declared, 55))
        // The origin is matched whole: another port or scheme is another site
        assertEquals(listOf("http://other.example/favicon.ico"), SiteIcons.addressesFor("http://other.example/", declared, 55))
        assertEquals(listOf("https://other.example:8443/favicon.ico"), SiteIcons.addressesFor("https://other.example:8443/", declared, 55))
        assertEquals(emptyList<String>(), SiteIcons.addressesFor("about:blank", declared, 55))
        assertEquals(emptyList<String>(), SiteIcons.addressesFor("file:///sdcard/page.html", declared, 55))
    }

    @Test
    fun addressesFor_aDeclaredFaviconIcoIsNotTriedTwice() {
        val declared = mapOf("https://a.example" to listOf(icon("https://a.example/favicon.ico")))
        assertEquals(listOf("https://a.example/favicon.ico"), SiteIcons.addressesFor("https://a.example/x", declared, 55))
    }

    @Test
    fun withSite_keepsTheNewestList_movesTheSiteToTheEnd_andForgetsTheOldestPastTheLimit() {
        val a = listOf(icon("https://a.example/1.png"))
        val b = listOf(icon("https://b.example/1.png"))
        var sites = SiteIcons.withSite(emptyMap(), "https://a.example", a)
        sites = SiteIcons.withSite(sites, "https://b.example", b)
        // Nothing new: the same map, so nothing is saved
        assertSame(sites, SiteIcons.withSite(sites, "https://b.example", b))
        assertSame("An empty list changes nothing", sites, SiteIcons.withSite(sites, "https://a.example", emptyList()))

        // The same icons from a site heard from earlier: it moves to the end
        val again = SiteIcons.withSite(sites, "https://a.example", a)
        assertEquals(listOf("https://b.example", "https://a.example"), again.keys.toList())
        sites = SiteIcons.withSite(again, "https://b.example", b)

        val a2 = listOf(icon("https://a.example/2.png"))
        sites = SiteIcons.withSite(sites, "https://a.example", a2)
        assertEquals(listOf("https://b.example", "https://a.example"), sites.keys.toList())
        assertEquals(a2, sites["https://a.example"])

        repeat(SiteIcons.MAX_SITES) { n -> sites = SiteIcons.withSite(sites, "https://s$n.example", a) }
        assertEquals(SiteIcons.MAX_SITES, sites.size)
        assertTrue("The site heard from longest ago goes first", "https://b.example" !in sites)
        assertTrue("https://s${SiteIcons.MAX_SITES - 1}.example" in sites)
    }

    @Test
    fun withSite_keepsAtMostEightIconsOfASite() {
        val many = (1..12).map { icon("https://a.example/$it.png") }
        assertEquals(SiteIcons.MAX_ICONS_PER_SITE, SiteIcons.withSite(emptyMap(), "https://a.example", many).getValue("https://a.example").size)
    }

    @Test
    fun theStoredForm_readsBackTheSameSitesInTheSameOrder() {
        val sites = linkedMapOf(
            "https://b.example" to listOf(icon("https://b.example/t.png", 152, 180, touch = true), icon("https://b.example/f.ico")),
            "https://a.example" to listOf(icon("https://cdn.example/a.png", 32))
        )
        val read = SharedPrefsDeclaredSiteIconStore.fromJson(SharedPrefsDeclaredSiteIconStore.toJson(sites))
        assertEquals(sites, read)
        assertEquals(sites.keys.toList(), read.keys.toList())
    }
}
