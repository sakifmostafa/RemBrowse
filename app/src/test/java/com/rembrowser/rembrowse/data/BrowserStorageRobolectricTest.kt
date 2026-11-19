package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.AccentColor
import com.rembrowser.rembrowse.model.BookmarkItem
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SearchEngine
import com.rembrowser.rembrowse.model.SplitRatios
import org.junit.Assert.assertEquals
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
            enhancedPrivacy = true
        )
        repository.saveState(PersistedBrowserState(settings = settings))

        assertEquals(settings, repository.loadState().settings)
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
}
