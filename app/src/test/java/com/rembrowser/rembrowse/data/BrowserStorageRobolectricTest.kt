package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PaneNode
import com.rembrowser.rembrowse.model.PageRendering
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SplitOrientation
import com.rembrowser.rembrowse.model.SplitRatios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BrowserStorageRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: SharedPrefsBrowserStateRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SharedPrefsBrowserStateRepository(context)
    }

    @Test
    fun saveAndLoadState_persistsLayoutSplitRatiosTabsAndBookmarks() {
        val original = PersistedBrowserState(
            layoutMode = LayoutMode.GRID_2X2,
            splitRatios = SplitRatios(horizontal = 0.35f, vertical = 0.65f),
            tabs = listOf(
                BrowserTab(id = "t1", url = "https://mozilla.org", title = "Mozilla", desktopMode = true),
                BrowserTab(id = "t2", url = "https://github.com", title = "GitHub", desktopMode = false)
            ),
            paneTabIds = listOf("t1", "t2"),
            activePaneIndex = 1,
            bookmarks = listOf(
                BookmarkItem(id = "b1", title = "Add-ons", url = "https://addons.mozilla.org", createdAtMillis = 1234L)
            )
        )

        repository.saveState(original)
        val restored = repository.loadState()

        assertEquals(LayoutMode.GRID_2X2, restored.layoutMode)
        assertEquals(0.35f, restored.splitRatios.horizontal, 0.001f)
        assertEquals(0.65f, restored.splitRatios.vertical, 0.001f)
        assertEquals(2, restored.tabs.size)
        assertEquals("https://github.com", restored.tabs[1].url)
        assertEquals(false, restored.tabs[1].desktopMode)
        assertEquals(listOf("t1", "t2"), restored.paneTabIds)
        assertEquals(1, restored.activePaneIndex)
        assertEquals(1, restored.bookmarks.size)
        assertEquals("https://addons.mozilla.org", restored.bookmarks.first().url)
    }

    @Test
    fun saveAndLoadState_persistsAppearanceAndPrivacySettings() {
        val settings = BrowserSettings(
            useDarkTheme = false,
            accent = AccentColor.BLUE,
            searchEngine = SearchEngine.BRAVE,
            enhancedPrivacy = true,
            // Every one of these is the opposite of its default, so a field that is not
            // written or not read back fails the comparison
            restoreTabsOnStartup = false,
            openNewTabsNextToCurrent = true,
            desktopSiteByDefault = false,
            askBeforeDownloading = false,
            pageRendering = PageRendering.DIRECT,
            swipeNavigation = false,
            reverseSwipeDirection = true
        )
        repository.saveState(PersistedBrowserState(settings = settings))

        assertEquals(settings, repository.loadState().settings)
    }

    @Test
    fun swipeNavigation_isOnUntilTurnedOff_andForDataSavedByAnOlderVersion() {
        assertTrue(BrowserSettings().swipeNavigation)
        assertTrue(repository.loadState().settings.swipeNavigation)

        context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("browser_state_json", """{"tabs":[],"settings":{"accent":"GREEN"}}""")
            .commit()
        assertTrue("No saved value means the default", repository.loadState().settings.swipeNavigation)

        repository.saveState(PersistedBrowserState(settings = BrowserSettings(swipeNavigation = false)))
        assertFalse(repository.loadState().settings.swipeNavigation)
        repository.saveState(PersistedBrowserState(settings = BrowserSettings(swipeNavigation = true)))
        assertTrue(repository.loadState().settings.swipeNavigation)
    }

    @Test
    fun reverseSwipeDirection_isOffUntilTurnedOn_andForDataSavedByAnOlderVersion() {
        assertFalse(BrowserSettings().reverseSwipeDirection)
        assertFalse(repository.loadState().settings.reverseSwipeDirection)

        context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("browser_state_json", """{"tabs":[],"settings":{"swipeNavigation":true}}""")
            .commit()
        assertFalse(repository.loadState().settings.reverseSwipeDirection)

        repository.saveState(PersistedBrowserState(settings = BrowserSettings(reverseSwipeDirection = true)))
        assertTrue(repository.loadState().settings.reverseSwipeDirection)
        repository.saveState(PersistedBrowserState(settings = BrowserSettings(reverseSwipeDirection = false)))
        assertFalse(repository.loadState().settings.reverseSwipeDirection)
    }

    @Test
    fun pageRendering_isStandardUntilChosenAndForANameThisVersionDoesNotKnow() {
        assertEquals(PageRendering.STANDARD, repository.loadState().settings.pageRendering)
        assertEquals(PageRendering.STANDARD, PageRendering.fromNameOrDefault("VULKAN"))
        assertEquals(PageRendering.STANDARD, PageRendering.fromNameOrDefault(null))

        PageRendering.entries.forEach { rendering ->
            repository.saveState(PersistedBrowserState(settings = BrowserSettings(pageRendering = rendering)))
            assertEquals(rendering, repository.loadState().settings.pageRendering)
        }
    }

    @Test
    fun downloadHistory_roundTripsAndTreatsAnInterruptedDownloadAsFailed() {
        val store = SharedPrefsDownloadHistoryStore(context)
        assertEquals(emptyList<DownloadItem>(), store.load())

        val finished = DownloadItem(
            id = "d1",
            fileName = "report (1).pdf",
            sourceUrl = "https://example.com/report.pdf",
            mimeType = "application/pdf",
            totalBytes = 2048,
            receivedBytes = 2048,
            status = DownloadStatus.COMPLETED,
            contentUri = "content://media/external/downloads/42",
            startedAtMillis = 1_700_000_000_000
        )
        val cancelled = DownloadItem(
            id = "d2",
            fileName = "movie.mkv",
            sourceUrl = "https://example.com/movie.mkv",
            mimeType = "video/x-matroska",
            receivedBytes = 512,
            status = DownloadStatus.CANCELLED
        )
        // The process died while this one was running
        val interrupted = cancelled.copy(id = "d3", fileName = "half.iso", status = DownloadStatus.IN_PROGRESS)
        store.save(listOf(finished, cancelled, interrupted))

        val restored = SharedPrefsDownloadHistoryStore(context).load()
        assertEquals(listOf(finished, cancelled, interrupted.copy(status = DownloadStatus.FAILED)), restored)
        assertEquals("No address to open for an unfinished file", null, restored[1].contentUri)
    }

    @Test
    fun downloadHistory_ignoresDamagedData() {
        context.getSharedPreferences("rembrowse_downloads", Context.MODE_PRIVATE)
            .edit()
            .putString("items", """[{"id":"","fileName":"x"},{"id":"ok","fileName":"kept.txt","status":"NOPE"},17]""")
            .commit()

        val restored = SharedPrefsDownloadHistoryStore(context).load()
        assertEquals(listOf("kept.txt"), restored.map { it.fileName })
        assertEquals(DownloadStatus.FAILED, restored.single().status)

        context.getSharedPreferences("rembrowse_downloads", Context.MODE_PRIVATE)
            .edit().putString("items", "not json").commit()
        assertEquals(emptyList<DownloadItem>(), SharedPrefsDownloadHistoryStore(context).load())
    }

    @Test
    fun loadState_fallsBackToDefaultsForDataSavedByAnOlderVersion() {
        // v0.01 stored no "settings" object at all
        context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("browser_state_json", """{"layoutMode":"SPLIT_1X2","tabs":[{"id":"t1","url":"https://mozilla.org"}]}""")
            .commit()

        val restored = repository.loadState()
        assertEquals(LayoutMode.SPLIT_1X2, restored.layoutMode)
        assertEquals(BrowserSettings(), restored.settings)
        assertEquals("https://mozilla.org", restored.tabs.single().url)
    }

    @Test
    fun loadState_dropsTheStrictModeThatVersion002TurnedOnForEveryone() {
        // v0.02 wrote "enhancedPrivacy": true without asking, and that mode broke sites
        context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "browser_state_json",
                """{"tabs":[],"settings":{"useDarkTheme":false,"accent":"GREEN","enhancedPrivacy":true}}"""
            )
            .commit()

        val settings = repository.loadState().settings
        assertEquals("Other choices are kept", AccentColor.GREEN, settings.accent)
        assertEquals(false, settings.useDarkTheme)
        assertEquals("Back to standard protection", false, settings.enhancedPrivacy)
    }

    @Test
    fun defaultExtensionStore_remembersThatTheAdBlockerWasSetUp() {
        val store = SharedPrefsDefaultExtensionStore(context)
        store.defaultBlockerProvisioned = false
        assertEquals(false, SharedPrefsDefaultExtensionStore(context).defaultBlockerProvisioned)

        store.defaultBlockerProvisioned = true
        assertEquals(true, SharedPrefsDefaultExtensionStore(context).defaultBlockerProvisioned)
    }

    @Test
    fun saveAndLoadState_keepsAFreeFormLayout() {
        val layout = PaneNode.Split(
            SplitOrientation.ROW,
            0.6f,
            PaneNode.Leaf("t1"),
            PaneNode.Split(SplitOrientation.COLUMN, 0.3f, PaneNode.Leaf("t2"), PaneNode.Leaf("t3"))
        )
        repository.saveState(
            PersistedBrowserState(
                layoutMode = LayoutMode.GRID_2X2,
                tabs = listOf("t1", "t2", "t3").map { BrowserTab(id = it, url = "https://$it.example/") },
                paneTabIds = listOf("t1", "t2", "t3"),
                layout = layout
            )
        )

        val restored = repository.loadState()

        assertEquals(layout, restored.layout)
        assertEquals(listOf("t1", "t2", "t3"), restored.paneTabIds)
    }

    @Test
    fun loadState_hasNoLayoutTreeForStatesSavedBeforeFreeFormLayouts() {
        val prefs = context.getSharedPreferences("rembrowse_state_v1", Context.MODE_PRIVATE)
        // Exactly what v0.12 wrote for a side-by-side split
        prefs.edit().putString(
            "browser_state_json",
            """{"layoutMode":"SPLIT_1X2","splitHorizontal":0.7,"splitVertical":0.5,"activePaneIndex":1,
               "tabs":[{"id":"t1","url":"https://reddit.com"},{"id":"t2","url":"https://youtube.com"}],
               "paneTabIds":["t1","t2"]}"""
        ).commit()

        val restored = repository.loadState()

        assertNull(restored.layout)
        assertEquals(LayoutMode.SPLIT_1X2, restored.layoutMode)
        assertEquals(0.7f, restored.splitRatios.horizontal, 0.001f)
        assertEquals(listOf("t1", "t2"), restored.paneTabIds)
        assertEquals(1, restored.activePaneIndex)

        // A layout that cannot be read is ignored the same way, and nothing else is lost
        prefs.edit().putString(
            "browser_state_json",
            """{"layoutMode":"SPLIT_2X1","tabs":[{"id":"t1","url":"https://reddit.com"}],"paneTabIds":["t1"],
               "layout":{"split":"DIAGONAL","ratio":"wide","first":{"tab":"t1"}}}"""
        ).commit()
        val damaged = repository.loadState()
        assertNull(damaged.layout)
        assertEquals(LayoutMode.SPLIT_2X1, damaged.layoutMode)
        assertEquals(1, damaged.tabs.size)

        // An out-of-range ratio is brought back into range
        prefs.edit().putString(
            "browser_state_json",
            """{"tabs":[],"layout":{"split":"ROW","ratio":3,"first":{"tab":"t1"},"second":{"tab":"t2"}}}"""
        ).commit()
        assertEquals(
            PaneNode.Split(SplitOrientation.ROW, SplitRatios.MAX_RATIO, PaneNode.Leaf("t1"), PaneNode.Leaf("t2")),
            repository.loadState().layout
        )
    }
}
