package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.DeclaredSiteIconStore
import com.rembrowser.rembrowse.data.SiteIconCache
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.BrowserSettings
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.model.PersistedBrowserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icons pages name for themselves: kept by site for the tab rail, the pinned sites and the
 * lists, saved with the browsing history, and forgotten with it.
 */
class DeclaredSiteIconsTest {

    private class MemoryStore(initial: Map<String, List<DeclaredSiteIcon>> = emptyMap()) : DeclaredSiteIconStore {
        var saved = initial
        var saves = 0
        override fun load() = saved
        override fun save(sites: Map<String, List<DeclaredSiteIcon>>) {
            saved = sites
            saves++
        }
    }

    private val discordIcon = listOf(DeclaredSiteIcon("https://discord.com/assets/favicon.ico"))

    private fun browser(store: MemoryStore, keepHistory: Boolean = true, iconClears: () -> Unit = {}): Pair<FakeBrowserEngine, BrowserViewModel> {
        val engine = FakeBrowserEngine()
        var ids = 0
        val viewModel = BrowserViewModel(
            engine = engine,
            repository = FakeBrowserStateRepository(PersistedBrowserState(settings = BrowserSettings(keepHistory = keepHistory))),
            idGenerator = { "tab-${++ids}" },
            siteIcons = SiteIconCache { iconClears() },
            declaredIconStore = store
        )
        return engine to viewModel
    }

    private fun FakeBrowserEngine.activeTab(viewModel: BrowserViewModel) = sessions.getValue(viewModel.uiState.value.activeTabId!!)

    @Test
    fun aPagesIcons_areKeptForItsSite_andSaved() {
        val store = MemoryStore()
        val (engine, viewModel) = browser(store)
        val tab = engine.activeTab(viewModel)
        tab.simulatePageLoad("https://discord.com/channels/@me", "Discord")
        tab.listener.onSiteIconsDeclared(tab.tabId, "https://discord.com/channels/@me", discordIcon)

        assertEquals(mapOf("https://discord.com" to discordIcon), viewModel.declaredSiteIcons.value)
        assertEquals(viewModel.declaredSiteIcons.value, store.saved)

        // The same icons again: nothing to save
        tab.listener.onSiteIconsDeclared(tab.tabId, "https://discord.com/app", discordIcon)
        assertEquals(1, store.saves)
    }

    @Test
    fun iconsSavedLastTime_areThereAtStart_forPinnedSitesAndRestoredTabs() {
        val (_, viewModel) = browser(MemoryStore(mapOf("https://discord.com" to discordIcon)))
        assertEquals(discordIcon, viewModel.declaredSiteIcons.value["https://discord.com"])
    }

    @Test
    fun clearingTheHistory_forgetsThem_withTheIconFiles() {
        val store = MemoryStore(mapOf("https://discord.com" to discordIcon))
        var iconClears = 0
        val (_, viewModel) = browser(store, iconClears = { iconClears++ })

        viewModel.clearHistory()

        assertTrue(viewModel.declaredSiteIcons.value.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertEquals(1, iconClears)
    }

    @Test
    fun withHistoryOff_theyAreForgottenAtStart_andKeptForThisRunOnly() {
        val store = MemoryStore(mapOf("https://discord.com" to discordIcon))
        val (engine, viewModel) = browser(store, keepHistory = false)
        assertTrue(viewModel.declaredSiteIcons.value.isEmpty())
        assertTrue(store.saved.isEmpty())

        val tab = engine.activeTab(viewModel)
        tab.listener.onSiteIconsDeclared(tab.tabId, "https://discord.com/channels/@me", discordIcon)
        assertEquals("Shown while RemBrowse runs", discordIcon, viewModel.declaredSiteIcons.value["https://discord.com"])
        assertTrue("Not saved", store.saved.isEmpty())
    }

    @Test
    fun sitesSeenWhileHistoryWasOff_areNotSaved_whenItIsTurnedBackOn() {
        val store = MemoryStore()
        val (engine, viewModel) = browser(store, keepHistory = false)
        val tab = engine.activeTab(viewModel)
        tab.listener.onSiteIconsDeclared(tab.tabId, "https://private.example/", listOf(DeclaredSiteIcon("https://private.example/i.png")))

        viewModel.setKeepHistory(true)
        tab.listener.onSiteIconsDeclared(tab.tabId, "https://discord.com/channels/@me", discordIcon)

        assertEquals(mapOf("https://discord.com" to discordIcon), store.saved)
        assertEquals(
            "Both shown while RemBrowse runs",
            setOf("https://private.example", "https://discord.com"),
            viewModel.declaredSiteIcons.value.keys
        )
    }

    @Test
    fun iconsForATabThatIsGone_areIgnored() {
        val store = MemoryStore()
        val (engine, viewModel) = browser(store)
        val listener = engine.activeTab(viewModel).listener
        listener.onSiteIconsDeclared("no-such-tab", "https://discord.com/", discordIcon)
        assertTrue(viewModel.declaredSiteIcons.value.isEmpty())
        assertEquals(0, store.saves)
    }
}
