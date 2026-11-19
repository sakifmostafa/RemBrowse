package com.rembrowser.rembrowse.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.buildPaletteResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteResultsTest {

    private val commands = listOf(
        PaletteCommand("new_tab", "New tab", Icons.Outlined.Settings, "Ctrl+T") {},
        PaletteCommand("settings", "Open settings", Icons.Outlined.Settings, "Ctrl+,") {}
    )
    private val tabs = listOf(
        BrowserTab(id = "t1"),
        BrowserTab(id = "t2", url = "https://github.com/mozilla", title = "Mozilla on GitHub"),
        BrowserTab(id = "t3", url = "https://www.youtube.com/", title = "YouTube")
    )

    private fun results(query: String, onNavigate: (String) -> Unit = {}, onSelectTab: (String) -> Unit = {}) =
        buildPaletteResults(query, commands, tabs, onNavigate, onSelectTab)

    @Test
    fun emptyQuery_listsEveryCommand() {
        assertEquals(listOf("new_tab", "settings"), results("  ").map { it.id })
    }

    @Test
    fun typedWords_offerAWebSearchFirstThenMatchingCommandsAndTabs() {
        val found = results("tab")
        assertEquals("go", found.first().id)
        assertEquals("Search the web for “tab”", found.first().label)
        assertTrue(found.any { it.id == "new_tab" })
    }

    @Test
    fun typedAddress_offersToOpenIt() {
        var navigated: String? = null
        val found = results("mozilla.org", onNavigate = { navigated = it })

        assertEquals("Open mozilla.org", found.first().label)
        found.first().action()
        assertEquals("mozilla.org", navigated)
    }

    @Test
    fun openTabs_matchByTitleOrAddressButTheStartPageIsNotListed() {
        var selected: String? = null
        val found = results("github", onSelectTab = { selected = it })

        val tabResult = found.single { it.id.startsWith("tab-") }
        assertEquals("Switch to Mozilla on GitHub", tabResult.label)
        tabResult.action()
        assertEquals("t2", selected)

        assertTrue(results("new").none { it.id == "tab-t1" })
    }
}
