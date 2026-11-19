package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.util.UrlResolver

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
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=");

    companion object {
        fun fromNameOrDefault(name: String?): SearchEngine {
            return entries.firstOrNull { it.name == name } ?: DUCKDUCKGO
        }
    }
}

/** The sections of the settings page. [path] is the last part of its `rem://settings/…` address. */
enum class SettingsSection(val path: String, val label: String) {
    APPEARANCE("appearance", "Appearance"),
    SEARCH("search", "Search"),
    PRIVACY("privacy", "Privacy"),
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
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    /**
     * Also block known fingerprinters, cryptominers and email trackers. Off by default: the
     * standard level is what keeps every site working.
     */
    val enhancedPrivacy: Boolean = false
)

data class SplitRatios(
    val horizontal: Float = 0.5f,
    val vertical: Float = 0.5f
) {
    fun clamped(): SplitRatios = SplitRatios(
        horizontal = horizontal.coerceIn(MIN_RATIO, MAX_RATIO),
        vertical = vertical.coerceIn(MIN_RATIO, MAX_RATIO)
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

data class PersistedBrowserState(
    val layoutMode: LayoutMode = LayoutMode.SINGLE_1X1,
    val splitRatios: SplitRatios = SplitRatios.DEFAULT,
    val tabs: List<BrowserTab> = emptyList(),
    val paneTabIds: List<String> = emptyList(),
    val activePaneIndex: Int = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val settings: BrowserSettings = BrowserSettings()
)

data class BrowserUiState(
    val layoutMode: LayoutMode = LayoutMode.SINGLE_1X1,
    val splitRatios: SplitRatios = SplitRatios.DEFAULT,
    val tabs: List<BrowserTab> = emptyList(),
    val paneTabIds: List<String> = emptyList(),
    val activePaneIndex: Int = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val addressBarInput: String = "",
    val isAddressBarFocused: Boolean = false,
    val settings: BrowserSettings = BrowserSettings(),
    /** Page dialogs (alert/confirm/prompt/select/auth) waiting for an answer, oldest first. */
    val pendingPrompts: List<PagePrompt> = emptyList()
) {
    val activeTabId: String?
        get() = paneTabIds.getOrNull(activePaneIndex) ?: tabs.firstOrNull()?.id

    val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    /** Tab ids currently shown in a pane, in pane order. */
    val visibleTabIds: List<String>
        get() = paneTabIds.take(layoutMode.paneCount)

    fun tabForPane(paneIndex: Int): BrowserTab? {
        val tabId = paneTabIds.getOrNull(paneIndex) ?: return null
        return tabs.firstOrNull { it.id == tabId }
    }

    /** Index of the pane showing [tabId], or -1 when the tab is in the background. */
    fun paneIndexOf(tabId: String): Int = visibleTabIds.indexOf(tabId)

    val isActiveTabBookmarked: Boolean
        get() = activeTab?.let { isBookmarked(it.url) } == true

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    val isSplit: Boolean
        get() = layoutMode.paneCount > 1

    /** The oldest pending page dialog that belongs to a tab the user can currently see. */
    val visiblePrompt: PagePrompt?
        get() {
            val visible = visibleTabIds
            return pendingPrompts.firstOrNull { it.tabId in visible }
        }
}
