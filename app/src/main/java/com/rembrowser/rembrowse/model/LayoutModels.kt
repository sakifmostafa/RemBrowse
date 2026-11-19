package com.rembrowser.rembrowse.model

enum class LayoutMode(
    val label: String,
    val paneCount: Int,
    val columns: Int,
    val rows: Int
) {
    SINGLE_1X1("1x1", 1, 1, 1),
    SPLIT_1X2("1x2", 2, 2, 1),
    SPLIT_2X1("2x1", 2, 1, 2),
    GRID_2X2("2x2", 4, 2, 2);

    companion object {
        fun fromNameOrDefault(name: String?): LayoutMode {
            return entries.firstOrNull { it.name == name } ?: SINGLE_1X1
        }
    }
}

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
    val url: String = DEFAULT_HOME_URL,
    val title: String = "New Tab",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = true
) {
    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
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
    val bookmarks: List<BookmarkItem> = emptyList()
)

data class BrowserUiState(
    val layoutMode: LayoutMode = LayoutMode.SINGLE_1X1,
    val splitRatios: SplitRatios = SplitRatios.DEFAULT,
    val tabs: List<BrowserTab> = emptyList(),
    val paneTabIds: List<String> = emptyList(),
    val activePaneIndex: Int = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val addressBarInput: String = "",
    val isAddressBarFocused: Boolean = false
) {
    val activeTabId: String?
        get() = paneTabIds.getOrNull(activePaneIndex) ?: tabs.firstOrNull()?.id

    val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    fun tabForPane(paneIndex: Int): BrowserTab? {
        val tabId = paneTabIds.getOrNull(paneIndex) ?: return null
        return tabs.firstOrNull { it.id == tabId }
    }

    val isActiveTabBookmarked: Boolean
        get() {
            val currentUrl = activeTab?.url ?: return false
            return bookmarks.any { it.url.equals(currentUrl, ignoreCase = true) }
        }
}
