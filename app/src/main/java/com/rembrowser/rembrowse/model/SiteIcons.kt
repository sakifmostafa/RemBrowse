package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.util.UrlResolver

/**
 * An icon a page names for itself in its `<head>`: `<link rel="icon">` (also `shortcut icon`)
 * or `<link rel="apple-touch-icon">`. Only raster icons at web addresses the icon client may
 * fetch are kept (see GeckoBrowserEngine.declaredSiteIcons); SVG icons are left out, since
 * RemBrowse has no SVG decoder.
 */
data class DeclaredSiteIcon(
    val url: String,
    /** An `apple-touch-icon`: made for a home screen, usually larger and on a solid background. */
    val isTouchIcon: Boolean = false,
    /** The widths, in pixels, the page says the file has (its `sizes`); empty when it doesn't say. */
    val sizes: List<Int> = emptyList()
)

/** Which of a site's icons RemBrowse shows, and where it looks for them. */
object SiteIcons {
    /** The smallest icon preferred: 16-pixel ones look blurred at the tab rail's size. */
    const val MIN_PREFERRED_SIZE = 32

    /** The size an `apple-touch-icon` that gives none has: what iOS asks for. */
    const val TOUCH_ICON_SIZE = 180

    /** How many of a site's declared icons are tried before its `/favicon.ico`. */
    const val DECLARED_TRIED = 2

    /** The most icons kept for one site. */
    const val MAX_ICONS_PER_SITE = 8

    /** The most sites whose icons are kept; the ones heard from longest ago go first. */
    const val MAX_SITES = 300

    /** A width larger than any icon: `sizes` values past it are nonsense. */
    private const val MAX_SIZE = 4096

    private val sizeToken = Regex("""(\d{1,5})[xX](\d{1,5})""")

    /**
     * The widths in a `sizes` attribute ("16x16 32x32"), smallest first. `any` (a scalable icon)
     * and anything that isn't a size are left out.
     */
    fun parseSizes(attribute: String): List<Int> =
        attribute.trim().split(Regex("\\s+")).mapNotNull { token ->
            val match = sizeToken.matchEntire(token) ?: return@mapNotNull null
            val width = match.groupValues[1].toInt()
            val height = match.groupValues[2].toInt()
            minOf(width, height).takeIf { it in 1..MAX_SIZE }
        }.distinct().sorted()

    /**
     * [icons] in the order they are worth trying for a picture [targetPx] pixels across:
     * 1. `rel="icon"` with a size of at least [MIN_PREFERRED_SIZE];
     * 2. `rel="icon"` without a size (often a `.ico` holding several), the last declared first,
     *    as browsers take the last of equally good icons;
     * 3. `apple-touch-icon`, at least [MIN_PREFERRED_SIZE] (without a size, [TOUCH_ICON_SIZE]);
     * 4. anything smaller, largest first.
     *
     * Among sized icons of one group, the smallest that is at least [targetPx] comes first, as it
     * is drawn sharp with the least to fetch, then the larger ones, then the largest of the rest.
     */
    fun ranked(icons: List<DeclaredSiteIcon>, targetPx: Int): List<DeclaredSiteIcon> {
        fun sideOf(icon: DeclaredSiteIcon): Int? = icon.sizes.maxOrNull() ?: if (icon.isTouchIcon) TOUCH_ICON_SIZE else null
        fun group(icon: DeclaredSiteIcon): Int {
            val side = sideOf(icon)
            return when {
                side == null -> 1
                side < MIN_PREFERRED_SIZE -> 3
                icon.isTouchIcon -> 2
                else -> 0
            }
        }
        return icons.withIndex()
            .sortedWith(
                compareBy<IndexedValue<DeclaredSiteIcon>> { group(it.value) }
                    .thenBy { entry ->
                        val side = sideOf(entry.value) ?: return@thenBy 0
                        // At least the target: smallest first. Smaller than it: largest first.
                        if (side >= targetPx) side - MAX_SIZE * 2 else -side
                    }
                    .thenByDescending { it.index }
            )
            .map { it.value }
    }

    /**
     * The addresses to try, in order, for the icon of the page at [pageUrl] drawn [targetPx]
     * pixels across: up to [DECLARED_TRIED] of the icons its site declared ([declared], by
     * origin; see [ranked]), then the site's `/favicon.ico`. Empty for a page that is not on the web.
     */
    fun addressesFor(pageUrl: String, declared: Map<String, List<DeclaredSiteIcon>>, targetPx: Int): List<String> {
        val favicon = UrlResolver.faviconUrl(pageUrl) ?: return emptyList()
        val siteIcons = SiteOrigins.originOf(pageUrl)?.let(declared::get).orEmpty()
        val chosen = ranked(siteIcons, targetPx).take(DECLARED_TRIED).map { it.url }
        return (chosen + favicon).distinct()
    }

    /**
     * [current] with [icons] as what the site at [origin] declares now, the site moved to the end,
     * so at most [MAX_SITES] of those heard from most recently are kept; or [current] itself when
     * that changes nothing (the same icons, from the site heard from last).
     */
    fun withSite(
        current: Map<String, List<DeclaredSiteIcon>>,
        origin: String,
        icons: List<DeclaredSiteIcon>
    ): Map<String, List<DeclaredSiteIcon>> {
        val kept = icons.take(MAX_ICONS_PER_SITE)
        if (kept.isEmpty() || (current[origin] == kept && current.keys.lastOrNull() == origin)) return current
        val updated = LinkedHashMap<String, List<DeclaredSiteIcon>>(current)
        updated.remove(origin)
        updated[origin] = kept
        while (updated.size > MAX_SITES) updated.remove(updated.keys.first())
        return updated
    }
}
