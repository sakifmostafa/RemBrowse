package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.util.UrlResolver

/** The fixed layouts offered by the Split menu and the keyboard: quick ways to build a [PaneNode]. */
enum class LayoutMode(
    val label: String,
    val description: String,
    val paneCount: Int,
    val columns: Int,
    val rows: Int
) {
    SINGLE_1X1("1x1", "Single pane", 1, 1, 1),
    SPLIT_1X2("1x2", "Side by side", 2, 2, 1),
    SPLIT_2X1("2x1", "Stacked", 2, 1, 2),

    /** One pane on the left, two stacked on the right. */
    SPLIT_1_2("1+2", "One and two", 3, 2, 2),
    GRID_2X2("2x2", "Grid", 4, 2, 2);

    companion object {
        fun fromNameOrDefault(name: String?): LayoutMode {
            return entries.firstOrNull { it.name == name } ?: SINGLE_1X1
        }
    }
}

/** The selectable theme colours. [MAROON] is the RemBrowse brand accent. */
enum class AccentColor(val label: String) {
    MAROON("Maroon"),
    ROSE("Rose"),
    PURPLE("Purple"),
    BLUE("Blue"),
    TEAL("Teal"),
    GREEN("Green");

    companion object {
        fun fromNameOrDefault(name: String?): AccentColor {
            return entries.firstOrNull { it.name == name } ?: MAROON
        }
    }
}

/** Where searches typed into the address bar are sent. */
enum class SearchEngine(val label: String, val queryUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=");

    companion object {
        /** Used until the user picks another engine in Settings. Stored by name, not position. */
        val DEFAULT = GOOGLE

        fun fromNameOrDefault(name: String?): SearchEngine {
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}

/**
 * How a web page's picture gets from the engine to the screen.
 *
 * The engine draws each page into a surface of its own. That surface is shown in one of two
 * ways: as a texture the app draws along with its own window ([STANDARD], [LEGACY]), or handed
 * straight to the system's compositor, behind a hole in the window ([DIRECT]).
 */
enum class PageRendering(val label: String, val description: String) {
    /**
     * A texture in the window, set up once. Everything the browser draws around and over a
     * page (rounded corners, panes over each other, moving panes) behaves as it always has.
     */
    STANDARD(
        "Standard",
        "Recommended. Pages are drawn inside the window, without the extra work for every frame " +
            "that earlier versions did."
    ),

    /**
     * The engine's own default: no copy through the app's window and no waiting for the app's
     * main thread, so it is the smoothest the engine can do. The page lies behind the window.
     */
    DIRECT(
        "Direct (experimental)",
        "Pages go straight to the screen, which can scroll more smoothly still. " +
            "A pane may flicker while it is resized or when it opens."
    ),

    /** [STANDARD] the way RemBrowse 0.13.2 and earlier did it, kept to compare against. */
    LEGACY(
        "As in 0.13.2 and earlier",
        "How earlier versions drew pages. Only useful to compare scrolling against the other two."
    );

    companion object {
        val DEFAULT = STANDARD

        fun fromNameOrDefault(name: String?): PageRendering = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The sections of the settings page. [path] is the last part of its `rem://settings/…` address. */
enum class SettingsSection(val path: String, val label: String) {
    APPEARANCE("appearance", "Appearance"),
    SEARCH("search", "Search"),
    TABS("tabs", "Tabs"),
    PRIVACY("privacy", "Privacy"),
    HISTORY("history", "History"),
    DOWNLOADS("downloads", "Downloads"),
    EXTENSIONS("extensions", "Extensions"),
    ABOUT("about", "About");

    val url: String
        get() = "${BrowserTab.SETTINGS_URL}/$path"

    companion object {
        /** The section a settings address points at; unknown paths open Appearance. */
        fun fromUrl(url: String): SettingsSection {
            val path = url.trim().lowercase().removePrefix(BrowserTab.SETTINGS_URL).trim('/').substringBefore('/')
            return entries.firstOrNull { it.path == path } ?: APPEARANCE
        }
    }
}

data class BrowserSettings(
    val useDarkTheme: Boolean = true,
    val accent: AccentColor = AccentColor.MAROON,
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    /**
     * Also block known fingerprinters, cryptominers and email trackers. Off by default: the
     * standard level is what keeps every site working.
     */
    val enhancedPrivacy: Boolean = false,
    /** Reopen the previous tabs at launch. When off, the browser starts on one new tab. */
    val restoreTabsOnStartup: Boolean = true,
    /** Put a new tab right after the current one instead of at the end of the list. */
    val openNewTabsNextToCurrent: Boolean = false,
    /** New tabs ask sites for their desktop version. A tab can still be switched on its own. */
    val desktopSiteByDefault: Boolean = true,
    /** Show a file's name, size and site, and wait for a yes, before saving it. */
    val askBeforeDownloading: Boolean = true,
    /** Record the pages visited in the browsing history. Turning it off keeps what is there. */
    val keepHistory: Boolean = true,
    /** How pages reach the screen; see [PageRendering]. Pages that are open switch at once. */
    val pageRendering: PageRendering = PageRendering.DEFAULT
)

data class SplitRatios(
    val horizontal: Float = 0.5f,
    val vertical: Float = 0.5f
) {
    fun clamped(): SplitRatios = SplitRatios(
        horizontal = horizontal.coerceIn(MIN_RATIO, MAX_RATIO),
        vertical = vertical.coerceIn(MIN_RATIO, MAX_RATIO)
    )

    /** These ratios, updated from the outermost side-by-side and stacked splits of [layout]. */
    fun following(layout: PaneNode): SplitRatios = SplitRatios(
        horizontal = layout.firstSplit(SplitOrientation.ROW)?.ratio ?: horizontal,
        vertical = layout.firstSplit(SplitOrientation.COLUMN)?.ratio ?: vertical
    )

    companion object {
        const val MIN_RATIO = 0.2f
        const val MAX_RATIO = 0.8f
        val DEFAULT = SplitRatios(0.5f, 0.5f)
    }
}

data class BrowserTab(
    val id: String,
    val url: String = NEW_TAB_URL,
    val title: String = "",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = true,
    /** True only when the engine reports a verified secure (HTTPS) connection. */
    val isSecure: Boolean = false,
    /** The content process behind this tab died and it is waiting for a manual reload. */
    val crashed: Boolean = false,
    /**
     * Created by a page (`window.open`, `target=_blank`) or an extension. Such a tab may sit at
     * `about:blank` while its opener fills it in, so it must show web content, not the start page.
     */
    val openedByPage: Boolean = false
) {
    /** A tab with nothing loaded yet; the UI shows the native start page instead of web content. */
    val isNewTabPage: Boolean
        get() = !openedByPage && (url.isBlank() || url == NEW_TAB_URL)

    /** One of RemBrowse's own pages (`rem://…`), drawn by the app rather than the web engine. */
    val isInternalPage: Boolean
        get() = url.startsWith(INTERNAL_SCHEME, ignoreCase = true)

    val isSettingsPage: Boolean
        get() = url.startsWith(SETTINGS_URL, ignoreCase = true)

    /** True when the tab shows a page from the web (not the start page or an internal page). */
    val isWebPage: Boolean
        get() = !isNewTabPage && !isInternalPage

    /** Title to show in the tab rail, pane headers and tab lists. Never blank. */
    val displayTitle: String
        get() = when {
            isNewTabPage -> NEW_TAB_TITLE
            isSettingsPage -> SETTINGS_TITLE
            title.isNotBlank() -> title
            else -> UrlResolver.extractDisplayHost(url)
        }

    companion object {
        const val NEW_TAB_URL = "about:blank"
        const val NEW_TAB_TITLE = "New tab"
        const val INTERNAL_SCHEME = "rem://"
        const val SETTINGS_URL = "rem://settings"
        const val SETTINGS_TITLE = "Settings"
    }
}

data class BookmarkItem(
    val id: String,
    val title: String,
    val url: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

/**
 * What is saved between launches. [layout] is the arrangement of panes; it is null in a state
 * written by a version that only knew the fixed layouts, which is then rebuilt from
 * [layoutMode], [paneTabIds] and [splitRatios]. Those three are still written, so an older
 * version reading a newer state finds something it understands.
 */
data class PersistedBrowserState(
    val layoutMode: LayoutMode = LayoutMode.SINGLE_1X1,
    val splitRatios: SplitRatios = SplitRatios.DEFAULT,
    val tabs: List<BrowserTab> = emptyList(),
    val paneTabIds: List<String> = emptyList(),
    val activePaneIndex: Int = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val settings: BrowserSettings = BrowserSettings(),
    val layout: PaneNode? = null
)

data class BrowserUiState(
    /** The panes on screen. The placeholder default is replaced before anything is shown. */
    val layout: PaneNode = PaneNode.Leaf(""),
    /**
     * The sizes the Side by side, Stacked and Grid presets are created with. They follow the
     * outermost divider of each direction, so a preset reopens at the size last used.
     */
    val splitRatios: SplitRatios = SplitRatios.DEFAULT,
    val tabs: List<BrowserTab> = emptyList(),
    val activePaneIndex: Int = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val addressBarInput: String = "",
    val isAddressBarFocused: Boolean = false,
    val settings: BrowserSettings = BrowserSettings(),
    /** Page dialogs (alert/confirm/prompt/select/auth) waiting for an answer, oldest first. */
    val pendingPrompts: List<PagePrompt> = emptyList(),
    /** The tab whose page asked to fill the screen (a video, say), or null. Always a visible tab. */
    val fullscreenTabId: String? = null
) {
    /** Tab ids currently shown in a pane, in pane order. */
    val paneTabIds: List<String>
        get() = layout.tabIds

    val paneCount: Int
        get() = layout.paneCount

    /** The preset the layout has the shape of, or null for an arrangement built by hand. */
    val layoutMode: LayoutMode?
        get() = layout.matchingPreset()

    val activeTabId: String?
        get() = paneTabIds.getOrNull(activePaneIndex) ?: tabs.firstOrNull()?.id

    val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    val visibleTabIds: List<String>
        get() = paneTabIds

    fun tabForPane(paneIndex: Int): BrowserTab? {
        val tabId = paneTabIds.getOrNull(paneIndex) ?: return null
        return tabs.firstOrNull { it.id == tabId }
    }

    /** Index of the pane showing [tabId], or -1 when the tab is in the background. */
    fun paneIndexOf(tabId: String): Int = paneTabIds.indexOf(tabId)

    val isActiveTabBookmarked: Boolean
        get() = activeTab?.let { isBookmarked(it.url) } == true

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    val isSplit: Boolean
        get() = paneCount > 1

    /** The oldest pending page dialog that belongs to a tab the user can currently see. */
    val visiblePrompt: PagePrompt?
        get() {
            val visible = visibleTabIds
            return pendingPrompts.firstOrNull { it.tabId in visible }
        }
}
