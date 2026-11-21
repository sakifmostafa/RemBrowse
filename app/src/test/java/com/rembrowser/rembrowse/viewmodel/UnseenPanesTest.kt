package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages nobody can see are paused: behind a fullscreen page, and while the window is away. */
class UnseenPanesTest {

    private val engine = FakeBrowserEngine()
    private var idCounter = 0
    private val viewModel = BrowserViewModel(
        engine = engine,
        repository = FakeBrowserStateRepository(),
        idGenerator = { "tab-${++idCounter}" }
    )

    /** Two panes side by side, plus a third tab in the background. Returns (left, right, hidden). */
    private fun twoPanesAndABackgroundTab(): Triple<String, String, String> {
        viewModel.splitCurrentTab(currentOnLeft = true)
        val (first, right) = viewModel.uiState.value.paneTabIds
        // Opens in the focused (left) pane; the tab that was there keeps its session behind it
        val left = viewModel.openNewTab("https://example.com/")
        assertEquals(listOf(left, right), viewModel.uiState.value.paneTabIds)
        return Triple(left, right, first)
    }

    private fun active(tabId: String) = engine.sessions.getValue(tabId).visibleState

    @Test
    fun theOtherPanesPauseWhileAPageIsFullscreen_andResumeAfter() {
        viewModel.splitCurrentTab(currentOnLeft = true)
        val (left, right) = viewModel.uiState.value.paneTabIds
        assertTrue(active(left) && active(right))

        engine.sessions.getValue(left).simulateFullScreen(true)
        assertTrue("The fullscreen page keeps running", active(left))
        assertFalse("The pane under it is paused", active(right))

        engine.sessions.getValue(left).simulateFullScreen(false)
        assertTrue(active(left) && active(right))

        // Leaving through Back resumes them too
        engine.sessions.getValue(right).simulateFullScreen(true)
        assertFalse(active(left))
        viewModel.exitFullscreen()
        assertTrue(active(left) && active(right))
    }

    @Test
    fun everyPagePausesWhileTheWindowIsAway_andOnlyThePanesComeBack() {
        val (left, right, hidden) = twoPanesAndABackgroundTab()
        assertTrue(active(left) && active(right))
        assertFalse(active(hidden))

        viewModel.setWindowShown(false)
        assertFalse(active(left))
        assertFalse(active(right))
        assertFalse(active(hidden))

        viewModel.setWindowShown(true)
        assertTrue(active(left) && active(right))
        assertFalse("A background tab stays in the background", active(hidden))
    }

    @Test
    fun aCallKeepsRunning_minimised_behindFullscreen_orInTheBackground() {
        val (left, right, hidden) = twoPanesAndABackgroundTab()
        engine.sessions.getValue(right).simulateRecording(true)
        engine.sessions.getValue(hidden).simulateRecording(true)
        assertTrue("A call in a background tab is not frozen", active(hidden))

        viewModel.setWindowShown(false)
        assertFalse(active(left))
        assertTrue("…nor while the window is away", active(right))

        viewModel.setWindowShown(true)
        engine.sessions.getValue(left).simulateFullScreen(true)
        assertTrue("…nor under a fullscreen video", active(right))

        // Once the call ends, the tab is paused like any other
        engine.sessions.getValue(left).simulateFullScreen(false)
        engine.sessions.getValue(hidden).simulateRecording(false)
        assertFalse(active(hidden))
        assertTrue(active(right))
    }

    @Test
    fun aTabShownWhileTheWindowIsAway_waitsForTheWindow() {
        val (_, right, hidden) = twoPanesAndABackgroundTab()
        viewModel.setWindowShown(false)

        viewModel.assignTabToPane(1, hidden)
        assertFalse("Put in a pane, but nobody can see it yet", active(hidden))

        viewModel.setWindowShown(true)
        assertTrue(active(hidden))
        assertFalse(active(right))
        assertEquals(hidden, viewModel.uiState.value.paneTabIds[1])
    }
}
