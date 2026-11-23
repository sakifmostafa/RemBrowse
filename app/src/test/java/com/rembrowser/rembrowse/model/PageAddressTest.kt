package com.rembrowser.rembrowse.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAddressTest {

    private fun under(page: String, bookmark: String) = PageAddress.isUnderBookmark(page, bookmark)

    @Test
    fun aPageIsUnderABookmarkAtItsPathOrBelowOnASegmentBoundary() {
        assertTrue(under("https://example.com/a/", "https://example.com/a/"))
        assertTrue(under("https://example.com/a/x", "https://example.com/a/"))
        assertTrue(under("https://example.com/a/x/y", "https://example.com/a/"))
        // The same folder without its closing slash
        assertTrue(under("https://example.com/a", "https://example.com/a/"))
        assertFalse(under("https://example.com/ab", "https://example.com/a/"))
        assertFalse(under("https://example.com/", "https://example.com/a/"))

        assertTrue(under("https://example.com/a", "https://example.com/a"))
        assertTrue(under("https://example.com/a/x", "https://example.com/a"))
        assertFalse(under("https://example.com/ab", "https://example.com/a"))
        assertFalse(under("https://example.com/a.html", "https://example.com/a"))
    }

    @Test
    fun aRepositoryBookmarkCoversItsOwnPagesOnly() {
        val repo = "https://github.com/org/repo"
        assertTrue(under("https://github.com/org/repo", repo))
        assertTrue(under("https://github.com/org/repo/issues/12", repo))
        assertFalse(under("https://github.com/other", repo))
        assertFalse(under("https://github.com/org/repository", repo))
        assertFalse(under("https://github.com/org", repo))
    }

    @Test
    fun aSiteBookmarkCoversEveryPageOfTheSite() {
        assertTrue(under("https://github.com/", "https://github.com/"))
        assertTrue(under("https://github.com/org/repo", "https://github.com/"))
        assertTrue(under("https://github.com", "https://github.com/"))
        assertTrue(under("https://github.com/org", "https://github.com"))
    }

    @Test
    fun thePagesQueryAndFragmentAreIgnored() {
        assertTrue(under("https://web.telegram.org/a/#-1001234567", "https://web.telegram.org/a/"))
        assertTrue(under("https://example.com/a/x?tab=2#top", "https://example.com/a/"))
        assertFalse(under("https://example.com/ab?x=/a/", "https://example.com/a/"))
    }

    @Test
    fun aBookmarkWithAQueryCoversOnlyThatPage() {
        val video = "https://www.youtube.com/watch?v=abc"
        assertTrue(under("https://www.youtube.com/watch?v=abc", video))
        assertTrue(under("https://www.youtube.com/watch?v=abc#t=30", video))
        assertFalse(under("https://www.youtube.com/watch?v=xyz", video))
        assertFalse(under("https://www.youtube.com/watch", video))
        assertFalse(under("https://example.com/a/x?tab=2", "https://example.com/a/?q=1"))
        assertTrue(under("https://news.ycombinator.com/item?id=1", "https://news.ycombinator.com/item?id=1"))
        assertFalse(under("https://news.ycombinator.com/item?id=2", "https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun anotherOriginIsAnotherSite() {
        val bookmark = "https://web.telegram.org/a/"
        assertFalse(under("http://web.telegram.org/a/", bookmark))
        assertFalse(under("https://web.telegram.org:8443/a/", bookmark))
        assertFalse(under("https://telegram.org/a/", bookmark))
        // The usual port is the same origin, and hosts are not case-sensitive
        assertTrue(under("https://WEB.Telegram.org:443/a/k/", bookmark))
    }

    @Test
    fun onlyWebAddressesMatch() {
        assertNull(PageAddress.of("about:blank"))
        assertNull(PageAddress.of("rembrowse://settings"))
        assertNull(PageAddress.of(null))
        assertFalse(under("about:blank", "https://example.com/"))
        assertFalse(under("https://example.com/", "file:///sdcard/page.html"))
    }

    @Test
    fun theSamePageIgnoresOnlyTheFragment() {
        fun same(a: String, b: String) = PageAddress.of(a)!!.isSamePageAs(PageAddress.of(b)!!)
        assertTrue(same("https://web.telegram.org/a/#-1001234567", "https://web.telegram.org/a/"))
        assertTrue(same("https://docs.example", "https://docs.example/"))
        // A closing slash is the same page, so clicking the pin doesn't reload it
        assertTrue(same("https://example.com/a", "https://example.com/a/"))
        assertFalse(same("https://example.com/a/x", "https://example.com/a/"))
        assertFalse(same("https://example.com/a/?page=2", "https://example.com/a/"))
    }
}
