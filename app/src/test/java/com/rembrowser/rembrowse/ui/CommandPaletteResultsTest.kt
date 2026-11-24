package com.rembrowser.rembrowse.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.HistoryEntry
import com.rembrowser.rembrowse.ui.components.PaletteCommand
import com.rembrowser.rembrowse.ui.components.PaletteHost
import com.rembrowser.rembrowse.ui.components.buildPaletteResults
import com.rembrowser.rembrowse.ui.components.goToAddressRowLimit
import com.rembrowser.rembrowse.ui.components.goToAddressTopOffset
import com.rembrowser.rembrowse.ui.components.limitKeepingGoRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun typedTextThatNamesNoCommand_offersAWebSearchFirstThenCommandsContainingIt() {
        // Two letters that are not a whole word: no command is put first
        val found = results("ta")
        assertEquals("go", found.first().id)
        assertEquals("Search the web for “ta”", found.first().label)
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

    @Test
    fun matchingPagesFromTheHistory_areOfferedAfterTabs_unlessAlreadyOpen() {
        val history = listOf(
            HistoryEntry("https://github.com/sakif/repo", "My repo", 3L),
            HistoryEntry(tabs[1].url, tabs[1].title, 2L),
            HistoryEntry("https://news.example/", "News", 1L)
        )
        var navigated: String? = null
        val results = buildPaletteResults("repo", commands, emptyList(), onNavigate = { navigated = it }, onSelectTab = {}, history = history)

        val fromHistory = results.filter { it.id.startsWith("history-") }
        assertEquals(listOf("My repo"), fromHistory.map { it.label })
        fromHistory.single().action()
        assertEquals("https://github.com/sakif/repo", navigated)

        // A page already open in a tab is offered as that tab, not again from the history
        val openQuery = buildPaletteResults("Mozilla", commands, tabs, {}, {}, history)
        assertTrue(openQuery.any { it.id == "tab-t2" })
        assertTrue(openQuery.none { it.id == "history-${tabs[1].url}" })
    }

    // The commands as RemBrowseScreen's paletteCommands() lists them, with their keywords and flags
    private fun browserCommands(isSplit: Boolean = false) = listOf(
        PaletteCommand("search", "Search the web", icon, "Ctrl+L", inGoToAddress = false) {},
        PaletteCommand("go_to_address", "Go to address…", icon, "Ctrl+G", keywords = listOf("url"), inGoToAddress = false) {},
        PaletteCommand("switch_tab", "Switch to tab…", icon, keywords = listOf("tabs")) {},
        PaletteCommand("split_view", if (isSplit) "Exit split view" else "Split view", icon, "Ctrl+\\", promotable = false) {},
        PaletteCommand("new_tab", "New tab", icon, "Ctrl+T") {},
        PaletteCommand("new_terminal", "New terminal", icon, "Ctrl+Shift+T", keywords = listOf("terminal", "ssh", "shell", "console")) {},
        PaletteCommand("close_tab", "Close tab", icon, "Ctrl+W", promotable = false) {},
        PaletteCommand("bookmarks", "Bookmarks", icon, keywords = listOf("favourites", "favorites")) {},
        PaletteCommand("find_in_page", "Find in page", icon, "Ctrl+F") {},
        PaletteCommand("print", "Print…", icon, "Ctrl+P", keywords = listOf("pdf")) {},
        PaletteCommand("screenshot", "Take screenshot", icon, "Ctrl+Alt+S", keywords = listOf("screenshot", "capture", "snip")) {},
        PaletteCommand("history", "History", icon, "Ctrl+H", keywords = listOf("recent")) {},
        PaletteCommand("downloads", "Downloads", icon, "Ctrl+J", keywords = listOf("files")) {},
        PaletteCommand("extensions", "Extensions", icon, keywords = listOf("addons")) {},
        PaletteCommand("site_permissions", "Site permissions", icon) {},
        PaletteCommand("settings", "Open settings", icon, "Ctrl+,", keywords = listOf("preferences", "options")) {}
    )

    private val icon get() = Icons.Outlined.Settings
    private val browserCommands = browserCommands()

    private fun ranked(query: String, hosts: List<PaletteHost> = emptyList(), commands: List<PaletteCommand> = browserCommands) =
        buildPaletteResults(query, commands, emptyList(), {}, {}, emptyList(), hosts).map { it.id }

    /** As Go to address ranks them: without the commands it leaves out. */
    private fun rankedInGoTo(query: String) = ranked(query, commands = browserCommands.filter { it.inGoToAddress })

    @Test
    fun theStartOfAWord_putsTheCommandAboveTheWebSearch() {
        assertEquals(listOf("new_terminal", "go"), ranked("ter").take(2))
        assertEquals(listOf("new_terminal", "go"), ranked("terminal").take(2))
        assertEquals(listOf("print", "go"), ranked("pri").take(2))
        assertEquals(listOf("bookmarks", "go"), ranked("book").take(2))
        assertEquals(listOf("settings", "go"), ranked("set").take(2))
    }

    @Test
    fun aWholeKeyword_ofThreeLettersOrMore_putsTheCommandFirst() {
        assertEquals(listOf("print", "go"), ranked("pdf").take(2))
        assertEquals(listOf("new_terminal", "go"), ranked("SSH").take(2))
    }

    @Test
    fun theStartOfAKeyword_needsFourLetters() {
        // Not shell, console, addons or capture ("add" does start "Go to address…" in the palette)
        listOf("she", "con", "add", "cap").forEach { typed -> assertEquals(typed, "go", rankedInGoTo(typed).first()) }
        assertEquals("go_to_address", ranked("add").first())
        assertEquals(listOf("new_terminal", "go"), ranked("shel").take(2))
        assertEquals(listOf("screenshot", "go"), ranked("capt").take(2))
        assertEquals(listOf("settings", "go"), ranked("pref").take(2))
    }

    @Test
    fun commandsThatCloseOrRearrange_areNeverPutFirst_butAreStillListed() {
        listOf("close", "clo", "split").forEach { typed ->
            val found = ranked(typed)
            assertEquals(typed, "go", found.first())
            assertTrue(typed, found.any { it == "close_tab" || it == "split_view" })
        }
        val whileSplit = ranked("exit", commands = browserCommands(isSplit = true))
        assertEquals(listOf("go", "split_view"), whileSplit.take(2))
    }

    @Test
    fun goToAddress_leavesOutSearchTheWebAndItself() {
        listOf("search", "sea", "web", "the", "address", "url").forEach { typed ->
            val found = rankedInGoTo(typed)
            assertEquals(typed, "go", found.first())
            assertTrue(typed, found.none { it == "search" || it == "go_to_address" })
        }
        // The palette still offers them by name
        assertEquals("search", ranked("web").first())
        assertEquals("go_to_address", ranked("url").first())
    }

    @Test
    fun twoLettersThatAreNotAWholeWord_putNothingFirst_butStillListTheCommand() {
        val found = ranked("pr")
        assertEquals("go", found.first())
        assertTrue("print" in found)
    }

    @Test
    fun twoLetterWholeWords_areSearchesNotCommands() {
        // "to" is a word of "Switch to tab…" and "in" of "Find in page": listed, but below the search
        val to = ranked("to")
        assertEquals("go", to.first())
        assertTrue("switch_tab" in to)
        val inPage = ranked("in")
        assertEquals("go", inPage.first())
        assertTrue("find_in_page" in inPage)
        assertEquals("go", ranked("TO").first())
    }

    @Test
    fun textWithSpaces_namesACommandOnlyAsTheStartOfItsLabel() {
        assertEquals(listOf("new_terminal", "go"), ranked("new ter").take(2))
        assertEquals(listOf("new_terminal", "go"), ranked("New  Terminal").take(2))
        assertEquals("go", ranked("history of rome").first())
        assertFalse("history" in ranked("history of rome"))
    }

    @Test
    fun addresses_areOpenedFirst_evenWhenAWordMatches() {
        val news = buildPaletteResults("news.com", browserCommands, emptyList(), {}, {})
        assertEquals("go", news.first().id)
        assertEquals("Open news.com", news.first().label)
        assertEquals("go", ranked("localhost:3000").first())
        assertEquals("go", ranked("history/").first())
    }

    @Test
    fun withNothingTyped_everyCommandIsListedInItsOwnOrder() {
        assertEquals(browserCommands.map { it.id }, ranked(""))
    }

    @Test
    fun aWholeWordBeatsTheStartOfOne_theLabelBeatsAKeyword_andTheShorterLabelWinsATie() {
        val commands = listOf(
            PaletteCommand("downloads", "Downloads", icon, keywords = listOf("files")) {},
            PaletteCommand("open_files", "Open files", icon) {},
            PaletteCommand("recently_closed", "Recently closed", icon) {},
            PaletteCommand("history", "History", icon, keywords = listOf("recent")) {},
            PaletteCommand("close_tab", "Close tab", icon) {},
            PaletteCommand("new_tab", "New tab", icon) {}
        )
        fun order(query: String) = buildPaletteResults(query, commands, emptyList(), {}, {}).map { it.id }

        // The label's word before the same word as a keyword, though "Downloads" is shorter
        assertEquals(listOf("open_files", "downloads", "go"), order("files"))
        // A whole keyword before the start of a label's word
        assertEquals(listOf("history", "recently_closed", "go"), order("recent"))
        // Both whole words of their labels: the shorter label first
        assertEquals(listOf("new_tab", "close_tab", "go"), order("tab"))
    }

    private val hosts = listOf(
        PaletteHost("h1", "my-server", "root@10.0.0.5") {},
        PaletteHost("h2", "master", "pi@192.168.1.20:2222") {},
        PaletteHost("h3", "", "root@nas") {},
        PaletteHost("h4", "Home server", "sakif@home.example") {},
        PaletteHost("h5", "Phone", "u0_a123@localhost:8022") {}
    )

    @Test
    fun theStartOfAWordOfAHostsName_putsItAboveTheWebSearch() {
        var connected: String? = null
        val withAction = listOf(PaletteHost("h1", "my-server", "root@10.0.0.5") { connected = "h1" })
        val found = buildPaletteResults("serv", browserCommands, emptyList(), {}, {}, hosts = withAction)
        assertEquals("ssh_h1", found.first().id)
        assertEquals("Connect to my-server (root@10.0.0.5)", found.first().label)
        assertEquals("go", found[1].id)
        found.first().action()
        assertEquals("h1", connected)

        assertEquals(listOf("ssh_h1", "ssh_h4", "go"), ranked("serv", hosts).take(3))
        assertEquals(listOf("ssh_h2", "go"), ranked("mast", hosts).take(2))
        assertEquals(listOf("ssh_h2", "go"), ranked("master", hosts).take(2))
        // Named by both its name and its address: one row
        assertEquals(listOf("ssh_h4", "go"), ranked("home", hosts).take(2))
        assertEquals(1, ranked("home", hosts).count { it == "ssh_h4" })
    }

    @Test
    fun theMiddleOfAWord_findsNoHost() {
        // "ter" is inside "master": the command it names comes first, and no host at all
        val ter = ranked("ter", hosts)
        assertEquals(listOf("new_terminal", "go"), ter.take(2))
        assertTrue(ter.none { it.startsWith("ssh_") })
        assertTrue(ranked("aster", hosts).none { it.startsWith("ssh_") })
        // Fewer than 3 letters find no host
        assertTrue(ranked("my", hosts).none { it.startsWith("ssh_") })
    }

    @Test
    fun theStartOfAWordOfAHostsAddress_listsItBelowTheWebSearch() {
        assertEquals(listOf("go", "ssh_h1", "ssh_h3"), ranked("root", hosts).take(3))
        val nas = buildPaletteResults("nas", emptyList(), emptyList(), {}, {}, hosts = hosts)
        assertEquals(listOf("go", "ssh_h3"), nas.map { it.id })
        // One without a name is shown by its address once
        assertEquals("Connect to root@nas", nas[1].label)
        assertEquals(listOf("go", "ssh_h2"), ranked("192.168", hosts).take(2))
    }

    @Test
    fun bareLocalhost_isOpened_withTheHostOnItBelow() {
        val found = buildPaletteResults("localhost", browserCommands, emptyList(), {}, {}, hosts = hosts)
        assertEquals("Open localhost", found.first().label)
        assertEquals("ssh_h5", found[1].id)
    }

    @Test
    fun ssh_listsEverySavedHost_andSshWithAPartFindsThatHost() {
        assertEquals(listOf("new_terminal", "ssh_h1", "ssh_h2", "ssh_h3", "ssh_h4", "ssh_h5", "go"), ranked("ssh", hosts))
        assertEquals(listOf("ssh_h1", "go"), ranked("ssh my", hosts).take(2))
        assertEquals(listOf("ssh_h2", "go"), ranked("ssh pi", hosts).take(2))
        // "ssh" makes it a connection, not an address
        assertEquals(listOf("ssh_h1", "go"), ranked("ssh root@10.0.0.5", hosts).take(2))
    }

    @Test
    fun withNothingTyped_hostsFollowTheCommands() {
        assertEquals(browserCommands.map { it.id } + listOf("ssh_h1", "ssh_h2", "ssh_h3", "ssh_h4", "ssh_h5"), ranked(" ", hosts))
    }

    @Test
    fun aShortList_keepsTheWebSearchRow() {
        // Commands and hosts above it would push it out of the 4 rows a compact window shows
        val rows = buildPaletteResults("ssh", browserCommands, emptyList(), {}, {}, hosts = hosts)
        assertEquals(listOf("new_terminal", "ssh_h1", "ssh_h2", "go"), limitKeepingGoRow(rows, 4).map { it.id })
        assertEquals(listOf("new_terminal", "ssh_h1", "ssh_h2", "ssh_h3", "ssh_h4", "go"), limitKeepingGoRow(rows, 6).map { it.id })
    }

    @Test
    fun goToAddress_showsFewerRows_whenTheWindowAboveTheKeyboardIsShort() {
        assertEquals(6, goToAddressRowLimit(compact = false, windowHeight = 800.dp, keyboardHeight = 0.dp))
        assertEquals(4, goToAddressRowLimit(compact = true, windowHeight = 800.dp, keyboardHeight = 0.dp))
        assertEquals(3, goToAddressRowLimit(compact = false, windowHeight = 600.dp, keyboardHeight = 250.dp))
        // A phone held sideways: the popup moves up, and with the keyboard up only 2 rows fit
        assertEquals(12.dp, goToAddressTopOffset(compact = false, windowHeight = 393.dp))
        assertEquals(96.dp, goToAddressTopOffset(compact = false, windowHeight = 800.dp))
        assertEquals(5, goToAddressRowLimit(compact = false, windowHeight = 393.dp, keyboardHeight = 0.dp))
        assertEquals(2, goToAddressRowLimit(compact = false, windowHeight = 393.dp, keyboardHeight = 200.dp))
    }
}
