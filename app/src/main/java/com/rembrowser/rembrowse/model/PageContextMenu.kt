package com.rembrowser.rembrowse.model

/** The kind of media a context menu was opened on. */
enum class PageMediaType { NONE, IMAGE, VIDEO, AUDIO }

/**
 * A context menu asked for on a page, by a right click (or a long press on a touchscreen) the
 * page left to the browser. With a [linkUrl] or a [mediaUrl] it is about that link or picture;
 * with neither it is the page's own menu.
 *
 * @param x where it opens, in pixels from the top-left corner of the page's view; null when not
 *   known, and the menu then opens at the top-left corner of the page.
 * @param serial tells two menus on the same spot apart, so a second right click opens it again.
 */
data class PageContextMenu(
    val tabId: String,
    val linkUrl: String? = null,
    val linkText: String? = null,
    val mediaUrl: String? = null,
    val mediaType: PageMediaType = PageMediaType.NONE,
    val x: Float? = null,
    val y: Float? = null,
    val serial: Long = 0
) {
    /** Only web addresses are opened in a tab; a `mailto:` or `javascript:` link is not. */
    val openableLinkUrl: String? get() = linkUrl?.takeIf(::isWebAddress)
    val openableMediaUrl: String? get() = mediaUrl?.takeIf(::isWebAddress)

    /** Addresses worth putting on the clipboard: anything but script. */
    val copyableLinkUrl: String? get() = linkUrl?.takeUnless(::isScript)
    val copyableMediaUrl: String? get() = mediaUrl?.takeUnless { isScript(it) || it.startsWith("blob:", ignoreCase = true) }

    /**
     * A picture whose pixels can be put on the clipboard: one at a web address (fetched again,
     * with the site's cookies) or inside the page as a `data:image/…` address. A `blob:` picture
     * lives only inside the page.
     */
    val copyableImageUrl: String?
        get() = mediaUrl?.takeIf {
            mediaType == PageMediaType.IMAGE && (isWebAddress(it) || it.startsWith("data:image/", ignoreCase = true))
        }

    val isPageMenu: Boolean get() = linkUrl == null && mediaUrl == null

    private companion object {
        fun isWebAddress(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        fun isScript(url: String): Boolean = url.trimStart().startsWith("javascript:", ignoreCase = true)
    }
}

/** What a page's context menu can do. */
enum class PageMenuAction {
    OPEN_LINK_IN_NEW_TAB,
    OPEN_LINK_IN_SPLIT,
    COPY_LINK,
    COPY_LINK_TEXT,
    OPEN_MEDIA_IN_NEW_TAB,
    COPY_IMAGE,
    COPY_MEDIA_ADDRESS,
    BACK,
    FORWARD,
    RELOAD,
    BOOKMARK,
    COPY_PAGE_ADDRESS,
    FIND_IN_PAGE
}

/** One row of the menu; [enabled] false shows it greyed out (Back with nowhere to go back to). */
data class PageMenuEntry(val action: PageMenuAction, val enabled: Boolean = true)

/**
 * The rows of [menu], in groups that are drawn with a line between them: the link's, the
 * picture's, then the page's own when there was nothing else under the pointer.
 *
 * @param tab the tab the menu belongs to, for Back, Forward and the bookmark.
 * @param canSplit whether another pane can still be opened.
 */
fun pageMenuSections(menu: PageContextMenu, tab: BrowserTab?, canSplit: Boolean): List<List<PageMenuEntry>> = buildList {
    val link = buildList {
        if (menu.openableLinkUrl != null) {
            add(PageMenuEntry(PageMenuAction.OPEN_LINK_IN_NEW_TAB))
            if (canSplit) add(PageMenuEntry(PageMenuAction.OPEN_LINK_IN_SPLIT))
        }
        if (menu.copyableLinkUrl != null) add(PageMenuEntry(PageMenuAction.COPY_LINK))
        if (menu.linkUrl != null && !menu.linkText.isNullOrBlank()) add(PageMenuEntry(PageMenuAction.COPY_LINK_TEXT))
    }
    if (link.isNotEmpty()) add(link)

    val media = buildList {
        if (menu.openableMediaUrl != null) add(PageMenuEntry(PageMenuAction.OPEN_MEDIA_IN_NEW_TAB))
        if (menu.copyableImageUrl != null) add(PageMenuEntry(PageMenuAction.COPY_IMAGE))
        if (menu.copyableMediaUrl != null) add(PageMenuEntry(PageMenuAction.COPY_MEDIA_ADDRESS))
    }
    if (media.isNotEmpty()) add(media)

    if (menu.isPageMenu) {
        add(
            listOf(
                PageMenuEntry(PageMenuAction.BACK, enabled = tab?.canGoBack == true),
                PageMenuEntry(PageMenuAction.FORWARD, enabled = tab?.canGoForward == true),
                PageMenuEntry(PageMenuAction.RELOAD)
            )
        )
        add(
            listOf(
                PageMenuEntry(PageMenuAction.BOOKMARK),
                PageMenuEntry(PageMenuAction.COPY_PAGE_ADDRESS),
                PageMenuEntry(PageMenuAction.FIND_IN_PAGE)
            )
        )
    }
}

/** What a copy [action] of [menu] puts on the clipboard; null for the other actions. */
fun pageMenuCopyText(menu: PageContextMenu, action: PageMenuAction, pageUrl: String?): String? = when (action) {
    PageMenuAction.COPY_LINK -> menu.copyableLinkUrl
    PageMenuAction.COPY_LINK_TEXT -> menu.linkText
    PageMenuAction.COPY_MEDIA_ADDRESS -> menu.copyableMediaUrl
    PageMenuAction.COPY_PAGE_ADDRESS -> pageUrl
    else -> null
}?.takeIf { it.isNotBlank() }
