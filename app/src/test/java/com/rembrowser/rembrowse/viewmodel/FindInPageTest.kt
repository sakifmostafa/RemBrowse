package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.engine.FindResult
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.ui.components.findCountText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FindInPageTest {

    private val engine = FakeBrowserEngine()
    private var idCounter = 0
    private val viewModel = BrowserViewModel(
        engine = engine,
        repository = FakeBrowserStateRepository(),
        idGenerator = { "tab-${++idCounter}" }
    )

    /** The focused tab, showing a web page. */
    private fun webPage(url: String = "https://example.com/"): String {
        val tabId = viewModel.uiState.value.activeTabId!!
        viewModel.navigatePane(0, url)
        engine.sessions.getValue(tabId).simulatePageLoad(url, "Example")
        return tabId
    }

    private fun session(tabId: String) = engine.sessions.getValue(tabId)

    @Test
    fun opensOnAWebPage_andAgainOnlyPutsTheCursorBack() {
        viewModel.openFindInPage()
        assertNull("The start page has nothing to find", viewModel.findInPage.value)

        val tab = webPage()
        viewModel.openFindInPage()
        assertEquals(tab, viewModel.findInPage.value!!.tabId)
        assertEquals(1, viewModel.findInPage.value!!.focusRequests)

        viewModel.updateFindQuery("cat")
        viewModel.openFindInPage()
        assertEquals("The text is kept", "cat", viewModel.findInPage.value!!.query)
        assertEquals(2, viewModel.findInPage.value!!.focusRequests)
    }

    @Test
    fun typingSearchesAndTheCountArrives() {
        val tab = webPage()
        viewModel.openFindInPage()

        viewModel.updateFindQuery("cat")
        assertEquals(Triple("cat", false, false), session(tab).finds.last())
        assertNull("Nothing to show until the page answers", viewModel.findInPage.value!!.result)

        session(tab).answerFind(FindResult("cat", found = true, current = 1, total = 3))
        assertEquals(FindResult("cat", true, 1, 3), viewModel.findInPage.value!!.result)
    }

    @Test
    fun anAnswerForTextSinceChangedIsDropped() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("cat")
        viewModel.updateFindQuery("cats")

        session(tab).answerFind(FindResult("cat", found = true, current = 1, total = 3))
        assertNull(viewModel.findInPage.value!!.result)
    }

    @Test
    fun nextPreviousAndMatchCase() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("Cat")

        viewModel.findNext()
        assertEquals(Triple("Cat", false, false), session(tab).finds.last())
        viewModel.findPrevious()
        assertEquals(Triple("Cat", true, false), session(tab).finds.last())

        viewModel.setFindMatchCase(true)
        assertEquals(Triple("Cat", false, true), session(tab).finds.last())
        viewModel.findNext()
        assertEquals("Match case stays on", Triple("Cat", false, true), session(tab).finds.last())
    }

    @Test
    fun emptyTextClearsTheHighlightingAndSearchesNothing() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("cat")
        val searches = session(tab).finds.size

        viewModel.updateFindQuery("")
        assertEquals(1, session(tab).findClearCount)
        viewModel.findNext()
        assertEquals(searches, session(tab).finds.size)
    }

    @Test
    fun closingClearsTheHighlighting() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("cat")

        viewModel.closeFindInPage()
        assertNull(viewModel.findInPage.value)
        assertEquals(1, session(tab).findClearCount)
    }

    @Test
    fun theBarClosesWhenAnotherTabTakesFocus() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("cat")

        viewModel.openNewTab("https://example.org/")
        assertNull(viewModel.findInPage.value)
        assertEquals("The old page's highlighting is taken off", 1, session(tab).findClearCount)
    }

    @Test
    fun aNewPageInTheTabForgetsTheCount_butKeepsTheBar() {
        val tab = webPage()
        viewModel.openFindInPage()
        viewModel.updateFindQuery("cat")
        session(tab).answerFind(FindResult("cat", found = true, current = 2, total = 5))

        session(tab).simulatePageStart("https://example.com/next")

        val find = viewModel.findInPage.value
        assertNotNull(find)
        assertEquals("cat", find!!.query)
        assertNull(find.result)
    }

    @Test
    fun theCountReads() {
        assertEquals("3/12", findCountText(FindResult("a", found = true, current = 3, total = 12)))
        assertEquals("No results", findCountText(FindResult("a", found = false, current = 0, total = 0)))
        assertEquals("3/1000+", findCountText(FindResult("a", found = true, current = 3, total = -1)))
    }
}
