package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.BrowsingHistoryStore
import com.rembrowser.rembrowse.data.SiteIconCache
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording, updating and clearing the browsing history. */
class BrowsingHistoryTest {

    private class MemoryHistory(initial: List<HistoryEntry> = emptyList()) : BrowsingHistoryStore {
        var saved: List<HistoryEntry> = initial
        override fun load() = saved
        override fun save(entries: List<HistoryEntry>) { saved = entries }
    }

    private val engine = FakeBrowserEngine()
    private val store = MemoryHistory()
    private var now = 1_000L
    private var ids = 0
    private var iconClears = 0
    private val viewModel = BrowserViewModel(
        engine = engine,
        repository = FakeBrowserStateRepository(),
        idGenerator = { "tab-${++ids}" },
        historyStore = store,
        clock = { now },
        siteIcons = SiteIconCache { iconClears++ }
    )

    private val tab get() = engine.sessions.getValue(viewModel.uiState.value.activeTabId!!)
    private fun urls() = viewModel.history.value.map { it.url }

    @Test
    fun visitedPages_areListedNewestFirst_andSaved() {
        tab.simulatePageLoad("https://one.example/", "One")
        now = 2_000L
        tab.simulatePageLoad("https://two.example/", "Two")

        assertEquals(
            listOf(
                HistoryEntry("https://two.example/", "Two", 2_000L),
                HistoryEntry("https://one.example/", "One", 1_000L)
            ),
            viewModel.history.value
        )
        assertEquals(viewModel.history.value, store.saved)
    }

    @Test
    fun aRevisitedPage_movesToTheTopInsteadOfRepeating() {
        tab.simulatePageLoad("https://one.example/", "One")
        tab.simulatePageLoad("https://two.example/", "Two")
        now = 5_000L
        tab.simulatePageLoad("https://one.example/", "One")

        assertEquals(listOf("https://one.example/", "https://two.example/"), urls())
        assertEquals(5_000L, viewModel.history.value.first().visitedAtMillis)
    }

    @Test
    fun aTitleThatArrivesLater_fillsInTheEntry() {
        tab.simulatePageStart("https://news.example/story")
        tab.simulateHistoryMove("https://news.example/story", canGoBack = true, canGoForward = false)
        assertEquals("", viewModel.history.value.single().title)

        engine.sessions.getValue(viewModel.uiState.value.activeTabId!!).let { session ->
            session.simulatePageLoad("https://news.example/story", "Big story")
        }
        assertEquals("Big story", viewModel.history.value.single().title)
    }

    @Test
    fun internalPages_areNotRecorded() {
        val web = tab
        web.simulatePageLoad("https://first.example/", "First")
        // The settings page opens in a tab of its own and is drawn by the app
        viewModel.openSettings(SettingsSection.HISTORY)
        web.simulatePageLoad("about:blank", "")
        web.simulatePageLoad("moz-extension://abc/popup.html", "Popup")
        web.simulatePageLoad("data:text/html,hello", "")

        assertEquals(listOf("https://first.example/"), urls())
    }

    @Test
    fun turningHistoryOff_stopsRecording_butKeepsWhatIsThere() {
        tab.simulatePageLoad("https://one.example/", "One")
        viewModel.setKeepHistory(false)
        tab.simulatePageLoad("https://two.example/", "Two")

        assertEquals(listOf("https://one.example/"), urls())

        viewModel.setKeepHistory(true)
        tab.simulatePageLoad("https://three.example/", "Three")
        assertEquals(listOf("https://three.example/", "https://one.example/"), urls())
    }

    @Test
    fun removeAndClear() {
        tab.simulatePageLoad("https://one.example/", "One")
        tab.simulatePageLoad("https://two.example/", "Two")

        viewModel.removeHistoryEntry("https://one.example/")
        assertEquals(listOf("https://two.example/"), urls())

        viewModel.clearHistory()
        assertTrue(viewModel.history.value.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun clearingTheHistory_forgetsTheSiteIconsKept() {
        // Each icon is kept under its site's address: left behind, they'd still name the sites
        tab.simulatePageLoad("https://one.example/", "One")
        viewModel.removeHistoryEntry("https://one.example/")
        assertEquals("Visiting and removing one page leave the icons", 0, iconClears)

        viewModel.clearHistory()
        assertEquals(1, iconClears)

        // Also with nothing listed, as when history is off: the icons were still kept
        viewModel.clearHistory()
        assertEquals(2, iconClears)
    }

    @Test
    fun withHistoryOff_theSiteIconsKeptLastTimeAreForgottenAtStart() {
        fun clearsAtStart(keepHistory: Boolean): Int {
            var clears = 0
            BrowserViewModel(
                FakeBrowserEngine(),
                FakeBrowserStateRepository(PersistedBrowserState(settings = BrowserSettings(keepHistory = keepHistory))),
                siteIcons = SiteIconCache { clears++ }
            )
            return clears
        }
        assertEquals(1, clearsAtStart(keepHistory = false))
        assertEquals("Kept with the history", 0, clearsAtStart(keepHistory = true))
    }

    @Test
    fun theOldestPagesDropOffPastTheLimit() {
        repeat(MAX_HISTORY_ENTRIES + 5) { n ->
            now = n.toLong()
            tab.simulatePageLoad("https://site.example/page$n", "Page $n")
        }

        assertEquals(MAX_HISTORY_ENTRIES, viewModel.history.value.size)
        assertEquals("https://site.example/page${MAX_HISTORY_ENTRIES + 4}", urls().first())
        assertEquals("https://site.example/page5", urls().last())
    }

    @Test
    fun historyFromAnEarlierRun_isLoadedAtStart() {
        val earlier = MemoryHistory(listOf(HistoryEntry("https://old.example/", "Old", 10L)))
        val restarted = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository(), historyStore = earlier)

        assertEquals(listOf("https://old.example/"), restarted.history.value.map { it.url })
    }
}
