package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageContextMenuTest {

    private val tab = BrowserTab(id = "t", url = "https://example.com/page", canGoBack = true)

    private fun actions(menu: PageContextMenu, canSplit: Boolean = true) =
        pageMenuSections(menu, tab, canSplit).map { section -> section.map { it.action } }

    @Test
    fun aLink_offersOpeningAndCopying() {
        val menu = PageContextMenu(tabId = "t", linkUrl = "https://example.com/a", linkText = "Read more")
        assertEquals(
            listOf(
                listOf(
                    PageMenuAction.OPEN_LINK_IN_NEW_TAB,
                    PageMenuAction.OPEN_LINK_IN_SPLIT,
                    PageMenuAction.COPY_LINK,
                    PageMenuAction.COPY_LINK_TEXT
                )
            ),
            actions(menu)
        )
    }

    @Test
    fun aLink_withNoRoomForAnotherPane_isNotOfferedASplit() {
        val menu = PageContextMenu(tabId = "t", linkUrl = "https://example.com/a")
        assertEquals(
            listOf(listOf(PageMenuAction.OPEN_LINK_IN_NEW_TAB, PageMenuAction.COPY_LINK)),
            actions(menu, canSplit = false)
        )
    }

    @Test
    fun aLinkedPicture_getsBothGroups() {
        val menu = PageContextMenu(
            tabId = "t",
            linkUrl = "https://example.com/a",
            mediaUrl = "https://cdn.example.com/p.jpg",
            mediaType = PageMediaType.IMAGE
        )
        assertEquals(
            listOf(
                listOf(PageMenuAction.OPEN_LINK_IN_NEW_TAB, PageMenuAction.OPEN_LINK_IN_SPLIT, PageMenuAction.COPY_LINK),
                listOf(PageMenuAction.OPEN_MEDIA_IN_NEW_TAB, PageMenuAction.COPY_IMAGE, PageMenuAction.COPY_MEDIA_ADDRESS)
            ),
            actions(menu)
        )
    }

    @Test
    fun scriptLinksAndLocalPictures_areNotOpenedOrCopied() {
        val script = PageContextMenu(tabId = "t", linkUrl = "javascript:void(0)", linkText = "Menu")
        assertEquals(listOf(listOf(PageMenuAction.COPY_LINK_TEXT)), actions(script))

        val mail = PageContextMenu(tabId = "t", linkUrl = "mailto:someone@example.com")
        assertEquals("Copied, not opened in a tab", listOf(listOf(PageMenuAction.COPY_LINK)), actions(mail))

        val blob = PageContextMenu(tabId = "t", mediaUrl = "blob:https://example.com/123", mediaType = PageMediaType.IMAGE)
        assertEquals(emptyList<List<PageMenuAction>>(), actions(blob))
    }

    @Test
    fun nothingUnderThePointer_isThePagesOwnMenu() {
        val sections = pageMenuSections(PageContextMenu(tabId = "t"), tab, canSplit = true)
        assertEquals(
            listOf(
                listOf(PageMenuAction.BACK, PageMenuAction.FORWARD, PageMenuAction.RELOAD),
                listOf(
                    PageMenuAction.BOOKMARK,
                    PageMenuAction.COPY_PAGE_ADDRESS,
                    PageMenuAction.FIND_IN_PAGE,
                    PageMenuAction.PRINT,
                    PageMenuAction.SCREENSHOT
                )
            ),
            sections.map { section -> section.map { it.action } }
        )
        val back = sections[0][0]
        val forward = sections[0][1]
        assertEquals(true, back.enabled)
        assertEquals("Nowhere to go forward to", false, forward.enabled)
        assertEquals("Print is always offered on the page's own menu", true, sections[1].single { it.action == PageMenuAction.PRINT }.enabled)
        assertEquals("So is a screenshot", true, sections[1].single { it.action == PageMenuAction.SCREENSHOT }.enabled)
    }

    @Test
    fun copyText_isWhatEachCopyActionNames() {
        val menu = PageContextMenu(
            tabId = "t",
            linkUrl = "https://example.com/a",
            linkText = "Read more",
            mediaUrl = "https://cdn.example.com/p.jpg",
            mediaType = PageMediaType.IMAGE
        )
        assertEquals("https://example.com/a", pageMenuCopyText(menu, PageMenuAction.COPY_LINK, tab.url))
        assertEquals("Read more", pageMenuCopyText(menu, PageMenuAction.COPY_LINK_TEXT, tab.url))
        assertEquals("https://cdn.example.com/p.jpg", pageMenuCopyText(menu, PageMenuAction.COPY_MEDIA_ADDRESS, tab.url))
        assertEquals(tab.url, pageMenuCopyText(menu, PageMenuAction.COPY_PAGE_ADDRESS, tab.url))
        assertNull(pageMenuCopyText(menu, PageMenuAction.RELOAD, tab.url))
    }

    @Test
    fun copyImage_isOfferedForWebAndDataImages_betweenOpenAndCopyAddress() {
        val expected = listOf(
            listOf(PageMenuAction.OPEN_MEDIA_IN_NEW_TAB, PageMenuAction.COPY_IMAGE, PageMenuAction.COPY_MEDIA_ADDRESS)
        )
        listOf("https://cdn.example.com/p.png", "http://cdn.example.com/p.png").forEach { url ->
            assertEquals(expected, actions(PageContextMenu(tabId = "t", mediaUrl = url, mediaType = PageMediaType.IMAGE)))
        }
        val data = PageContextMenu(tabId = "t", mediaUrl = "data:image/png;base64,iVBORw0KGgo=", mediaType = PageMediaType.IMAGE)
        assertEquals("data: pictures can't be opened in a tab", listOf(listOf(PageMenuAction.COPY_IMAGE, PageMenuAction.COPY_MEDIA_ADDRESS)), actions(data))
        assertEquals(data.mediaUrl, data.copyableImageUrl)
    }

    @Test
    fun copyImage_isNotOfferedForVideoAudioBlobsOrNonImageData() {
        listOf(PageMediaType.VIDEO, PageMediaType.AUDIO).forEach { type ->
            val menu = PageContextMenu(tabId = "t", mediaUrl = "https://cdn.example.com/m.mp4", mediaType = type)
            assertNull(menu.copyableImageUrl)
            assertEquals(listOf(listOf(PageMenuAction.OPEN_MEDIA_IN_NEW_TAB, PageMenuAction.COPY_MEDIA_ADDRESS)), actions(menu))
        }
        assertNull(PageContextMenu(tabId = "t", mediaUrl = "blob:https://example.com/1", mediaType = PageMediaType.IMAGE).copyableImageUrl)
        assertNull(PageContextMenu(tabId = "t", mediaUrl = "data:text/html,<b>x</b>", mediaType = PageMediaType.IMAGE).copyableImageUrl)
        assertNull(PageContextMenu(tabId = "t", linkUrl = "https://example.com/").copyableImageUrl)
    }
}
