package com.rembrowser.rembrowse.ui

import android.app.Application
import android.provider.Settings
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which overlay the system's Back closes first. Since predictive back is on (Android 13+), Back
 * never arrives as a key: it goes straight to the Back handlers in RemBrowseScreen, in the reverse
 * of their order there, so these drive the activity's dispatcher, as Android does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class BackNavigationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = FakeBrowserEngine()
    private val browserVm = BrowserViewModel(engine, FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )
    private val chrome = BrowserChromeState()
    private var backDispatcher: OnBackPressedDispatcher? = null
    private var composeView: View? = null

    /** What the screen reads for "the on-screen keyboard is up" */
    private var imeVisible = false

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun launch() {
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            composeView = LocalView.current
            imeVisible = WindowInsets.isImeVisible
            CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                RemBrowseTheme {
                    RemBrowseScreen(
                        browserViewModel = browserVm,
                        extensionsViewModel = extVm,
                        downloadsViewModel = downloadsVm,
                        chrome = chrome,
                        versionName = "test",
                        onPickLocalXpi = {},
                        paneViewport = { _, modifier -> Box(modifier) },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    /** A page that has somewhere to go back to; returns its tab. */
    private fun launchOnAPageWithHistory(): String {
        launch()
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/second")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/second", "Second", canGoBack = true)
        }
        composeRule.waitForIdle()
        return tab
    }

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun goBackCount(tab: String) = engine.sessions.getValue(tab).goBackCount

    @Test
    fun findBar_closesBeforeThePageGoesBack() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.onNodeWithTag("find_bar").assertIsDisplayed()

        pressBack()
        assertNull(browserVm.findInPage.value)
        composeRule.onNodeWithTag("find_bar").assertDoesNotExist()
        assertEquals("The page stays where it is", 0, goBackCount(tab))

        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    fun tabSwitcher_closesBeforeTheFindBarAndThePage() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread {
            browserVm.openFindInPage()
            chrome.showTabSwitcher = true
        }
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        assertNotNull("The find bar waits its turn", browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))

        pressBack()
        assertNull(browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))
        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    fun fullscreen_isLeftBeforeAnythingElse() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread {
            browserVm.openFindInPage()
            chrome.showTabSwitcher = true
            engine.sessions.getValue(tab).simulateFullScreen(true)
        }
        composeRule.waitForIdle()

        pressBack()
        assertEquals(1, engine.sessions.getValue(tab).exitFullScreenCount)
        assertNull(browserVm.uiState.value.fullscreenTabId)
        assertTrue("The switcher is back, still open", chrome.showTabSwitcher)
        assertNotNull(browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))
    }

    @Test
    fun screenshotOverlay_isCancelledBeforeAnythingElse() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread {
            browserVm.openFindInPage()
            chrome.showTabSwitcher = true
            chrome.screenCapture.start()
        }
        composeRule.waitForIdle()
        assertTrue(chrome.screenCapture.isSelecting)

        pressBack()
        assertFalse(chrome.screenCapture.isSelecting)
        assertTrue("The switcher under it stays open", chrome.showTabSwitcher)
        assertNotNull(browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))
    }

    @Test
    fun aTabALinkOpened_closesAndShowsThePageThatOpenedIt() {
        val opener = launchOnAPageWithHistory()
        composeRule.runOnUiThread { engine.sessions.getValue(opener).simulateNewTab("https://example.com/popup") }
        composeRule.waitForIdle()
        val popup = browserVm.uiState.value.activeTabId!!
        assertTrue(popup != opener)

        pressBack()
        assertTrue(browserVm.uiState.value.tabs.none { it.id == popup })
        assertEquals(opener, browserVm.uiState.value.activeTabId)
        assertEquals("Neither page went back in its history", 0, goBackCount(opener))
    }

    @Test
    fun addressBarFocused_backStillGoesBackInThePage() {
        // With the on-screen keyboard up, Android gives Back to the keyboard first (checked on a
        // device); without one (a laptop's keyboard) Back goes to the page, as before
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { chrome.focusAddressBar() }
        composeRule.waitForIdle()
        assertTrue(browserVm.uiState.value.isAddressBarFocused)

        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    fun nothingToGoBackTo_onATouchDevice_backIsLeftToAndroid() {
        // No handler is enabled, so the system's back-to-home preview plays and RemBrowse goes to
        // the background, in a web page and in a terminal alike
        launch()
        composeRule.waitForIdle()
        assertFalse(backDispatcher!!.hasEnabledCallbacks())

        composeRule.runOnUiThread { browserVm.openTerminal(TerminalTarget.Picker) }
        composeRule.waitForIdle()
        assertTrue(browserVm.uiState.value.activeTab!!.isTerminal)
        assertFalse(backDispatcher!!.hasEnabledCallbacks())

        // An overlay over the terminal still closes first
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        assertTrue(backDispatcher!!.hasEnabledCallbacks())
        pressBack()
        assertFalse(chrome.showTabSwitcher)
        assertTrue(browserVm.uiState.value.activeTab!!.isTerminal)
    }

    @Test
    fun onScreenKeyboardUp_everyHandlerStandsAside_soBackPutsTheKeyboardAwayFirst() {
        // Android gives Back to the newest callback; one enabled while the keyboard is up (here
        // the page gains history as you type) must not come before the keyboard's own
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        assertTrue(backDispatcher!!.hasEnabledCallbacks())

        setKeyboard(up = true)
        assertTrue(imeVisible)
        assertFalse(backDispatcher!!.hasEnabledCallbacks())
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        assertFalse(backDispatcher!!.hasEnabledCallbacks())

        // Once it is down, Back closes things again, in the usual order
        setKeyboard(up = false)
        assertTrue(backDispatcher!!.hasEnabledCallbacks())
        pressBack()
        assertFalse(chrome.showTabSwitcher)
        assertNotNull(browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))
    }

    @Test
    @Config(sdk = [32])
    fun onScreenKeyboardUp_beforeAndroid13_theHandlersActAsTheyAlwaysDid() {
        // Up to Android 12L Back is a key, and the keyboard app gets it before the window: it goes
        // first without the handlers standing aside, and a keyboard inset that reads "up" must not
        // leave Back with nothing to close (the activity would finish)
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()

        setKeyboard(up = true)
        assertTrue("The screen sees the keyboard up", imeVisible)
        assertTrue(backDispatcher!!.hasEnabledCallbacks())
        pressBack()
        assertNull(browserVm.findInPage.value)
        assertEquals(0, goBackCount(tab))
        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    fun theKeyboardGate_isForAndroid13AndLater() {
        for (sdk in listOf(26, 30, 31, 32)) {
            assertFalse("SDK $sdk, keyboard up", backHandlersYieldToKeyboard(imeVisible = true, sdkInt = sdk))
        }
        for (sdk in listOf(33, 34, 35, 36)) {
            assertTrue("SDK $sdk, keyboard up", backHandlersYieldToKeyboard(imeVisible = true, sdkInt = sdk))
            assertFalse("SDK $sdk, keyboard down", backHandlersYieldToKeyboard(imeVisible = false, sdkInt = sdk))
        }
    }

    private fun setKeyboard(up: Boolean) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (up) 300 else 0))
            .setVisible(WindowInsetsCompat.Type.ime(), up)
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeView!!, insets) }
        composeRule.waitForIdle()
    }

    @Test
    fun backGesture_shrinksTheTabSwitcher_cancelledItStaysOpen_letGoItCloses() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        val fullWidth = composeRule.onNodeWithTag("tab_switcher").getBoundsInRoot().width

        // Halfway through a swipe from the left edge
        composeRule.runOnUiThread {
            backDispatcher!!.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            backDispatcher!!.dispatchOnBackProgressed(BackEventCompat(120f, 300f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.waitForIdle()
        assertEquals(0.5f, chrome.tabSwitcherBack)
        val shrunk = composeRule.onNodeWithTag("tab_switcher").getBoundsInRoot().width
        assertEquals("95% at half way", fullWidth.value * 0.95f, shrunk.value, 1f)

        // The finger goes back: nothing closes
        composeRule.runOnUiThread { backDispatcher!!.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed()
        assertEquals(0f, chrome.tabSwitcherBack)
        assertEquals(fullWidth, composeRule.onNodeWithTag("tab_switcher").getBoundsInRoot().width)

        // Again, and let go
        composeRule.runOnUiThread {
            backDispatcher!!.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            backDispatcher!!.dispatchOnBackProgressed(BackEventCompat(240f, 300f, 0.9f, BackEventCompat.EDGE_LEFT))
            backDispatcher!!.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        assertEquals(0f, chrome.tabSwitcherBack)
        assertEquals("Only the switcher closed", 0, goBackCount(tab))
    }

    @Test
    fun backGesture_withAnimationsOff_closesTheSwitcherWithoutShrinkingIt() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            launchOnAPageWithHistory()
            composeRule.runOnUiThread { chrome.showTabSwitcher = true }
            composeRule.waitForIdle()
            val fullWidth = composeRule.onNodeWithTag("tab_switcher").getBoundsInRoot().width

            composeRule.runOnUiThread {
                backDispatcher!!.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                backDispatcher!!.dispatchOnBackProgressed(BackEventCompat(240f, 300f, 0.8f, BackEventCompat.EDGE_LEFT))
            }
            composeRule.waitForIdle()
            assertEquals(fullWidth, composeRule.onNodeWithTag("tab_switcher").getBoundsInRoot().width)

            composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }
}
