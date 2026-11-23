package com.rembrowser.rembrowse.model

/**
 * A web address reduced to what bookmark matching looks at: its origin (scheme, host and port,
 * as [SiteOrigins.originOf] gives it), its path and its query. The fragment is left out.
 */
class PageAddress private constructor(
    val origin: String,
    /** Never empty: an address without a path has "/". */
    val path: String,
    val query: String
) {
    /**
     * Whether this page is [bookmark]'s page or below it: the same origin, and a path that is the
     * bookmark's or goes on from it at a "/" (a bookmark of /a/ covers /a/ and /a/x, not /ab).
     * The page's query and fragment do not matter, so a pinned web.telegram.org/a/ covers
     * web.telegram.org/a/#-100…, and a pinned github.com/ covers every GitHub page. A bookmark
     * whose address has a query names one page, not a folder (youtube.com/watch?v=abc), so it
     * covers only that page: otherwise every video would count as the bookmarked one.
     */
    fun isUnder(bookmark: PageAddress): Boolean {
        if (bookmark.query.isNotEmpty()) return isSamePageAs(bookmark)
        if (origin != bookmark.origin) return false
        val base = bookmark.path.trimEnd('/')
        return path == base || path.startsWith("$base/")
    }

    /**
     * The same page, whatever the fragment and a closing slash: same origin, path and query, so
     * /a and /a/ are the same page and a click on its pin doesn't reload it.
     */
    fun isSamePageAs(other: PageAddress): Boolean =
        origin == other.origin && path.trimEnd('/') == other.path.trimEnd('/') && query == other.query

    companion object {
        private val parts = Regex("^[a-z][a-z0-9+.-]*://[^/?#]*([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)

        /** The address of [url], or null for anything that is not an http or https address. */
        fun of(url: String?): PageAddress? {
            val origin = SiteOrigins.originOf(url) ?: return null
            val match = parts.find(url!!.trim()) ?: return null
            val path = match.groupValues[1].ifEmpty { "/" }
            return PageAddress(origin, path, match.groupValues[2])
        }

        /** Whether the page at [pageUrl] is the bookmark [bookmarkUrl]'s page or below it. */
        fun isUnderBookmark(pageUrl: String?, bookmarkUrl: String?): Boolean {
            val bookmark = of(bookmarkUrl) ?: return false
            return of(pageUrl)?.isUnder(bookmark) == true
        }
    }
}
