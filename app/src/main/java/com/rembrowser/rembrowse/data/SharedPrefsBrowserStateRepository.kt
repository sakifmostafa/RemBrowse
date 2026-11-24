package com.rembrowser.rembrowse.data

import android.content.Context
import android.content.SharedPreferences
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.model.SplitRatios
import com.rembrowser.rembrowse.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class SharedPrefsBrowserStateRepository(
    private val prefs: SharedPreferences
) : BrowserStateRepository {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun loadState(): PersistedBrowserState {
        val raw = prefs.getString(KEY_STATE_JSON, null) ?: return PersistedBrowserState()
        return runCatching { decode(raw) }.getOrElse { PersistedBrowserState() }
    }

    override fun saveState(state: PersistedBrowserState) {
        prefs.edit().putString(KEY_STATE_JSON, encode(state)).apply()
    }

    companion object {
        private const val PREFS_NAME = "rembrowse_state_v1"
        private const val KEY_STATE_JSON = "browser_state_json"

        // v0.02 saved "enhancedPrivacy": true for everyone without asking, and that mode broke
        // Cloudflare-protected sites. The value is stored under a new key so those installs
        // return to the standard level instead of keeping a choice the user never made.
        private const val KEY_ENHANCED_PRIVACY = "enhancedTrackingProtection"
        private const val KEY_THEME_MODE = "themeMode"

        /**
         * The light/dark switch that came before [KEY_THEME_MODE]: read to carry it over, and
         * still written ([legacyDarkThemeSwitch]) for an older build installed over this one.
         */
        private const val KEY_LEGACY_DARK_THEME = "useDarkTheme"

        /**
         * The old switch as 0.14.0 and older read it, should one of them be installed over this
         * version: Dark and Light as they are, System default as dark, the default those builds
         * had. Not the side the device was on at the last save: that would leave the older build
         * light or dark depending on when the state happened to be saved. This version reads
         * [KEY_THEME_MODE] first, so the switch never changes its own choice.
         */
        internal fun legacyDarkThemeSwitch(mode: ThemeMode): Boolean = mode != ThemeMode.LIGHT

        internal fun encode(state: PersistedBrowserState): String {
            val root = JSONObject()
            root.put("layoutMode", state.layoutMode.name)
            root.put("splitHorizontal", state.splitRatios.horizontal.toDouble())
            root.put("splitVertical", state.splitRatios.vertical.toDouble())
            root.put("activePaneIndex", state.activePaneIndex)

            val tabsArray = JSONArray()
            state.tabs.forEach { tab ->
                tabsArray.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("url", tab.url)
                        put("title", tab.title)
                        put("desktopMode", tab.desktopMode)
                    }
                )
            }
            root.put("tabs", tabsArray)

            val paneIdsArray = JSONArray()
            state.paneTabIds.forEach { paneIdsArray.put(it) }
            root.put("paneTabIds", paneIdsArray)
            state.layout?.let { root.put("layout", encodeLayout(it)) }

            val bookmarksArray = JSONArray()
            state.bookmarks.forEach { bookmark ->
                bookmarksArray.put(
                    JSONObject().apply {
                        put("id", bookmark.id)
                        put("title", bookmark.title)
                        put("url", bookmark.url)
                        put("createdAtMillis", bookmark.createdAtMillis)
                    }
                )
            }
            root.put("bookmarks", bookmarksArray)

            root.put(
                "settings",
                JSONObject().apply {
                    put(KEY_THEME_MODE, state.settings.themeMode.name)
                    put(KEY_LEGACY_DARK_THEME, legacyDarkThemeSwitch(state.settings.themeMode))
                    put("accent", state.settings.accent.name)
                    put("systemColors", state.settings.useSystemColors)
                    put("searchEngine", state.settings.searchEngine.name)
                    put(KEY_ENHANCED_PRIVACY, state.settings.enhancedPrivacy)
                    put("restoreTabsOnStartup", state.settings.restoreTabsOnStartup)
                    put("openNewTabsNextToCurrent", state.settings.openNewTabsNextToCurrent)
                    put("desktopSiteByDefault", state.settings.desktopSiteByDefault)
                    put("askBeforeDownloading", state.settings.askBeforeDownloading)
                    put("keepHistory", state.settings.keepHistory)
                    put("pageRendering", state.settings.pageRendering.name)
                    put("swipeNavigation", state.settings.swipeNavigation)
                    put("reverseSwipeDirection", state.settings.reverseSwipeDirection)
                    put("tabRailExpanded", state.settings.tabRailExpanded)
                }
            )

            return root.toString()
        }

        internal fun decode(json: String): PersistedBrowserState {
            val root = JSONObject(json)
            val layoutMode = LayoutMode.fromNameOrDefault(root.optString("layoutMode", ""))
            val splitRatios = SplitRatios(
                horizontal = root.optDouble("splitHorizontal", 0.5).toFloat(),
                vertical = root.optDouble("splitVertical", 0.5).toFloat()
            ).clamped()
            val activePaneIndex = root.optInt("activePaneIndex", 0).coerceAtLeast(0)

            val tabs = mutableListOf<BrowserTab>()
            val tabsArray = root.optJSONArray("tabs")
            if (tabsArray != null) {
                for (i in 0 until tabsArray.length()) {
                    val obj = tabsArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                    tabs.add(
                        BrowserTab(
                            id = id,
                            url = obj.optString("url", BrowserTab.NEW_TAB_URL),
                            title = obj.optString("title", ""),
                            desktopMode = obj.optBoolean("desktopMode", true)
                        )
                    )
                }
            }

            val paneTabIds = mutableListOf<String>()
            val paneIdsArray = root.optJSONArray("paneTabIds")
            if (paneIdsArray != null) {
                for (i in 0 until paneIdsArray.length()) {
                    val id = paneIdsArray.optString(i, "")
                    if (id.isNotBlank()) paneTabIds.add(id)
                }
            }

            val bookmarks = mutableListOf<BookmarkItem>()
            val bookmarksArray = root.optJSONArray("bookmarks")
            if (bookmarksArray != null) {
                for (i in 0 until bookmarksArray.length()) {
                    val obj = bookmarksArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                    val url = obj.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                    bookmarks.add(
                        BookmarkItem(
                            id = id,
                            title = obj.optString("title", url),
                            url = url,
                            createdAtMillis = obj.optLong("createdAtMillis", 0L)
                        )
                    )
                }
            }

            val defaults = BrowserSettings()
            val settingsObj = root.optJSONObject("settings")
            val settings = if (settingsObj == null) {
                defaults
            } else {
                BrowserSettings(
                    themeMode = decodeThemeMode(settingsObj),
                    accent = AccentColor.fromNameOrDefault(settingsObj.optString("accent", "")),
                    useSystemColors = settingsObj.optBoolean("systemColors", defaults.useSystemColors),
                    searchEngine = SearchEngine.fromNameOrDefault(settingsObj.optString("searchEngine", "")),
                    enhancedPrivacy = settingsObj.optBoolean(KEY_ENHANCED_PRIVACY, defaults.enhancedPrivacy),
                    restoreTabsOnStartup = settingsObj.optBoolean("restoreTabsOnStartup", defaults.restoreTabsOnStartup),
                    openNewTabsNextToCurrent = settingsObj.optBoolean(
                        "openNewTabsNextToCurrent",
                        defaults.openNewTabsNextToCurrent
                    ),
                    desktopSiteByDefault = settingsObj.optBoolean("desktopSiteByDefault", defaults.desktopSiteByDefault),
                    askBeforeDownloading = settingsObj.optBoolean("askBeforeDownloading", defaults.askBeforeDownloading),
                    keepHistory = settingsObj.optBoolean("keepHistory", defaults.keepHistory),
                    pageRendering = PageRendering.fromNameOrDefault(settingsObj.optString("pageRendering", "")),
                    swipeNavigation = settingsObj.optBoolean("swipeNavigation", defaults.swipeNavigation),
                    reverseSwipeDirection = settingsObj.optBoolean("reverseSwipeDirection", defaults.reverseSwipeDirection),
                    tabRailExpanded = settingsObj.optBoolean("tabRailExpanded", defaults.tabRailExpanded)
                )
            }

            return PersistedBrowserState(
                layoutMode = layoutMode,
                splitRatios = splitRatios,
                tabs = tabs,
                paneTabIds = paneTabIds,
                activePaneIndex = activePaneIndex,
                bookmarks = bookmarks,
                settings = settings,
                // Absent in states saved before layouts became free-form; unreadable ones are
                // dropped the same way, and the layout is rebuilt from the fields above.
                layout = root.optJSONObject("layout")?.let { decodeLayout(it, depth = 0) }
            )
        }

        /**
         * The saved theme. Earlier versions saved a dark theme switch instead, written by every
         * install whether it was touched or not: that choice is kept, so nobody's theme changes
         * on the update. Only saved settings with neither (none at all, or a new install) follow
         * the device.
         */
        internal fun decodeThemeMode(settings: JSONObject): ThemeMode {
            ThemeMode.fromNameOrNull(settings.optString(KEY_THEME_MODE, ""))?.let { return it }
            if (!settings.has(KEY_LEGACY_DARK_THEME)) return ThemeMode.DEFAULT
            return if (settings.optBoolean(KEY_LEGACY_DARK_THEME, true)) ThemeMode.DARK else ThemeMode.LIGHT
        }

        private fun encodeLayout(node: PaneNode): JSONObject = when (node) {
            is PaneNode.Leaf -> JSONObject().put("tab", node.tabId)
            is PaneNode.Split -> JSONObject()
                .put("split", node.orientation.name)
                .put("ratio", node.ratio.toDouble())
                .put("first", encodeLayout(node.first))
                .put("second", encodeLayout(node.second))
        }

        private fun decodeLayout(obj: JSONObject, depth: Int): PaneNode? {
            if (depth > MAX_LAYOUT_DEPTH) return null
            val tabId = obj.optString("tab", "")
            if (tabId.isNotBlank()) return PaneNode.Leaf(tabId)
            val orientation = SplitOrientation.entries.firstOrNull { it.name == obj.optString("split", "") } ?: return null
            val first = obj.optJSONObject("first")?.let { decodeLayout(it, depth + 1) } ?: return null
            val second = obj.optJSONObject("second")?.let { decodeLayout(it, depth + 1) } ?: return null
            return PaneNode.Split(
                orientation = orientation,
                ratio = PaneNode.clampRatio(obj.optDouble("ratio", 0.5).toFloat()),
                first = first,
                second = second
            )
        }

        /** Four panes never nest deeper than this; anything deeper is not a layout we wrote. */
        private const val MAX_LAYOUT_DEPTH = 8
    }
}
