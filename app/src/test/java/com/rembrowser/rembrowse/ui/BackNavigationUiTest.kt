package com.rembrowser.rembrowse.ui

import android.app.Application
import android.hardware.input.InputManager
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.PointerDevices
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.shadows.ShadowInputManager
import org.robolectric.util.ReflectionHelpers

/**
 * Which overlay the system's Back closes first. Since predictive back is on (Android 13+), Back
 * never arrives as a key: it goes straight to the Back handlers in RemBrowseScreen, in the reverse
 * of their order there, so these drive the activity's dispatcher, as Android does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class BackNavigationUiTest {

    // An activity of its own, so it can go to the background and come back
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    /** How many times the keyboard was asked to go away */
    private var hideRequests = 0

    @After
    fun tearDown() {
        uiScope.cancel()
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun launch() {
        val imm = ApplicationProvider.getApplicationContext<Application>().getSystemService(InputMethodManager::class.java)
        shadowOf(imm).setSoftInputVisibilityHandler { visible -> if (!visible) hideRequests++ }
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
                        // A text field in the page, which takes the on-screen keyboard
                        paneViewport = { _, modifier ->
                            Box(modifier) {
                                BasicTextField(rememberTextFieldState(), Modifier.testTag("page_text_field"))
                            }
                        },
                        extensionPopupContent = { Box(it) }
                    )
                }
            }
        }
    }

    /** A page with nowhere to go back to; returns its tab. */
    private fun launchOnAPageWithoutHistory(): String {
        launch()
        val tab = browserVm.uiState.value.activeTabId!!
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/", "First", canGoBack = false)
        }
        composeRule.waitForIdle()
        assertFalse(browserVm.uiState.value.canNavigateBack(browserVm.uiState.value.activeTab))
        return tab
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

    // --- The page is offered Back first: its own dialog or popover closes before it is left ---

    @Test
    fun aPageWithADialogOpen_backClosesIt_andTheNextBackGoesBack() {
        val tab = launchOnAPageWithHistory()
        val page = engine.sessions.getValue(tab)
        page.pageTakesBack = true

        pressBack()
        assertEquals(1, page.handleBackCount)
        assertEquals("The page closed its dialog and stays", 0, goBackCount(tab))

        page.pageTakesBack = false
        pressBack()
        assertEquals(2, page.handleBackCount)
        assertEquals(1, goBackCount(tab))
    }

    @Test
    fun backPressedTwiceBeforeThePageAnswers_closesTheDialog_thenGoesBack_once() {
        val tab = launchOnAPageWithHistory()
        val page = engine.sessions.getValue(tab)
        page.answersBackLater = true

        pressBack()
        pressBack()
        assertEquals("The second waits for the first answer", 1, page.handleBackCount)

        composeRule.runOnUiThread { page.answerBack(handled = true) }
        composeRule.waitForIdle()
        assertEquals(2, page.handleBackCount)
        assertEquals(0, goBackCount(tab))

        composeRule.runOnUiThread { page.answerBack(handled = false) }
        composeRule.waitForIdle()
        assertEquals(1, goBackCount(tab))
        assertEquals(0, page.unansweredBackCount)
    }

    @Test
    fun whatTheBrowserShowsInFront_closesBeforeThePageIsAsked() {
        val tab = launchOnAPageWithHistory()
        val page = engine.sessions.getValue(tab)
        page.pageTakesBack = true
        composeRule.runOnUiThread {
            browserVm.openFindInPage()
            chrome.showTabSwitcher = true
        }
        composeRule.waitForIdle()

        pressBack()
        composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
        pressBack()
        assertNull(browserVm.findInPage.value)
        assertEquals("Not asked yet", 0, page.handleBackCount)

        pressBack()
        assertEquals(1, page.handleBackCount)
        assertEquals(0, goBackCount(tab))
    }

    @Test
    fun keyboardUp_backPutsItAway_beforeThePageIsAsked() {
        val tab = launchOnAPageWithHistory()
        val page = engine.sessions.getValue(tab)
        page.pageTakesBack = true
        keyboardUpFor()

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { assertEquals(0, page.handleBackCount) },
            actedOn = {
                assertEquals(1, page.handleBackCount)
                assertEquals(0, goBackCount(tab))
            }
        )
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

    // --- With the on-screen keyboard up (Android 13+): Back puts it away first ---

    /**
     * A text field takes the keyboard ([field], the page's by default): Compose asks Android to
     * show it, and its inset comes up.
     */
    private fun keyboardUpFor(field: String = "page_text_field") {
        composeRule.onNodeWithTag(field).requestFocus()
        composeRule.waitForIdle()
        setKeyboard(up = true)
        assertTrue(imeVisible)
    }

    /**
     * With the keyboard up, Back's first press asks Android to put it away ([hideRequests] counts
     * the asks) and does nothing else; Android then takes the keyboard's inset away
     * ([keyboardGoes]), and the next press does what Back does without a keyboard.
     */
    private fun assertBackPutsTheKeyboardAwayFirst(untouched: () -> Unit, actedOn: () -> Unit) {
        assertTrue(backDispatcher!!.hasEnabledCallbacks())
        val asked = hideRequests

        pressBack()
        assertEquals("Back asked for the keyboard to go", asked + 1, hideRequests)
        untouched()

        keyboardGoes()
        pressBack()
        actedOn()
    }

    @Test
    fun keyboardUp_backPutsItAway_thenClosesTheFindBar() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        keyboardUpFor("find_input")

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { assertNotNull(browserVm.findInPage.value) },
            actedOn = {
                assertNull(browserVm.findInPage.value)
                assertEquals(0, goBackCount(tab))
            }
        )
    }

    @Test
    fun keyboardUp_backPutsItAway_thenClosesTheTabSwitcher() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { chrome.showTabSwitcher = true }
        composeRule.waitForIdle()
        keyboardUpFor("switcher_search")

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { composeRule.onNodeWithTag("tab_switcher").assertIsDisplayed() },
            actedOn = {
                composeRule.onNodeWithTag("tab_switcher").assertDoesNotExist()
                assertEquals(0, goBackCount(tab))
            }
        )
    }

    @Test
    fun keyboardUp_backPutsItAway_thenLeavesFullscreen() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { engine.sessions.getValue(tab).simulateFullScreen(true) }
        composeRule.waitForIdle()
        keyboardUpFor()

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { assertEquals(0, engine.sessions.getValue(tab).exitFullScreenCount) },
            actedOn = {
                assertEquals(1, engine.sessions.getValue(tab).exitFullScreenCount)
                assertEquals(0, goBackCount(tab))
            }
        )
    }

    @Test
    fun keyboardUp_backPutsItAway_thenGoesBackInThePage_evenWhenThePageGainedItsHistoryMeanwhile() {
        // Seen on an Android 16 emulator: a page that gained history as you typed took the first
        // Back, before the keyboard went. Its handler is turned on after the keyboard came up.
        val tab = launchOnAPageWithoutHistory()
        keyboardUpFor()
        composeRule.runOnUiThread {
            browserVm.navigatePane(0, "https://example.com/second")
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/second", "Second", canGoBack = true)
        }
        composeRule.waitForIdle()

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { assertEquals(0, goBackCount(tab)) },
            actedOn = { assertEquals(1, goBackCount(tab)) }
        )
    }

    @Test
    fun keyboardUp_withNothingElseForBackToDo_onAPhone_backIsLeftToTheKeyboardApp() {
        // No handler of the window is on, so the window has no callback with Android and the
        // keyboard app gets Back as it always did (its emoji or clipboard panel closes first).
        // The keyboard's handler turned on here would register the window's callback after the
        // keyboard app's, and take that Back from it.
        launchOnAPageWithoutHistory()
        assertFalse(backDispatcher!!.hasEnabledCallbacks())
        keyboardUpFor()

        assertFalse("Nothing of the window's is on", backDispatcher!!.hasEnabledCallbacks())
        assertEquals(0, hideRequests)
    }

    @Test
    fun keyboardUp_andThenTheFindBarOpens_theKeyboardsBackIsOnWithIt_andComesFirst() {
        // The find bar's handler turns on while the keyboard is up, which registers the window's
        // callback after the keyboard app's: the keyboard's handler is on with it, before it
        val tab = launchOnAPageWithoutHistory()
        keyboardUpFor()
        assertFalse(backDispatcher!!.hasEnabledCallbacks())
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        keyboardUpFor("find_input")

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { assertNotNull(browserVm.findInPage.value) },
            actedOn = {
                assertNull(browserVm.findInPage.value)
                assertEquals(0, goBackCount(tab))
            }
        )
        assertFalse("Nothing left on", backDispatcher!!.hasEnabledCallbacks())
    }

    @Test
    fun keyboardUp_theWindowBackFromTheBackground_andAHandlerTurnedOnAfter_stillComeAfterTheKeyboard() {
        val tab = launchOnAPageWithHistory()
        keyboardUpFor()
        // The window goes to the background and comes back: every handler is turned off and on
        // again, each in its place
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertTrue("Still up", imeVisible)
        // The screenshot overlay's handler, the newest of the others, turned on now
        composeRule.runOnUiThread { chrome.screenCapture.start() }
        composeRule.waitForIdle()

        pressBack()
        assertTrue("The keyboard's Back came first: the overlay waits", chrome.screenCapture.isSelecting)
        assertEquals(0, goBackCount(tab))
        keyboardGoes()
        pressBack()
        assertFalse(chrome.screenCapture.isSelecting)
        assertEquals(0, goBackCount(tab))
    }

    @Test
    @Config(shadows = [ShadowInputManagerWithTouchpad::class])
    fun onALaptop_atTheRoot_keyboardUp_backPutsItAway_thenStaysInRemBrowse() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("A touchpad is connected", PointerDevices.hasMouseOrTouchpad(context))
        val tab = launchOnAPageWithoutHistory()
        keyboardUpFor()

        assertBackPutsTheKeyboardAwayFirst(
            untouched = { },
            actedOn = {
                assertTrue("Back stays in RemBrowse", backDispatcher!!.hasEnabledCallbacks())
                assertEquals(0, goBackCount(tab))
                assertFalse(composeRule.activity.isFinishing)
            }
        )
    }

    @Test
    fun aKeyboardInsetHidingDoesntClear_theSecondBackStillCloses_andTheOneAfterGoesBack() {
        // The inset still reads "up" after Android was asked to put the keyboard away (a keyboard
        // app's toolbar that stays, or nothing on screen at all): Back must not keep asking
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        keyboardUpFor("find_input")
        val asked = hideRequests

        pressBack()
        assertEquals(asked + 1, hideRequests)
        assertNotNull(browserVm.findInPage.value)

        assertTrue("Still reads up", imeVisible)
        pressBack()
        assertNull(browserVm.findInPage.value)
        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    @Config(shadows = [ShadowInputManagerWithTouchpad::class])
    fun onALaptop_aKeyboardInsetStuckUp_stillNeverLetsBackLeaveTheApp() {
        // An inset that reads "up" with no keyboard on screen and nothing to put away
        val tab = launchOnAPageWithHistory()
        setKeyboard(up = true)

        pressBack()
        pressBack()
        assertEquals("The second Back went back in the page", 1, goBackCount(tab))
        composeRule.runOnUiThread {
            engine.sessions.getValue(tab).simulatePageLoad("https://example.com/", "First", canGoBack = false)
        }
        composeRule.waitForIdle()
        assertTrue("Nothing to go back to, still kept", backDispatcher!!.hasEnabledCallbacks())
        pressBack()
        assertEquals(1, goBackCount(tab))
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    fun aKeyboardThatComesStraightBack_isNotPutAwayAgain_butOneThatComesLaterIs() {
        val tab = launchOnAPageWithHistory()
        composeRule.runOnUiThread { browserVm.openFindInPage() }
        composeRule.waitForIdle()
        keyboardUpFor("find_input")
        var asked = hideRequests
        pressBack()
        assertEquals(asked + 1, hideRequests)

        // Gone, and at once back: the next Back goes on to the find bar
        keyboardGoes()
        keyboardUpFor()
        pressBack()
        assertNull(browserVm.findInPage.value)

        // Gone, and back a while later: the keyboard goes first again
        keyboardGoes()
        SystemClock.sleep(KEYBOARD_BACK_AGAIN_MILLIS)
        keyboardUpFor()
        asked = hideRequests
        pressBack()
        assertEquals(asked + 1, hideRequests)
        assertEquals(0, goBackCount(tab))
        keyboardGoes()
        pressBack()
        assertEquals(1, goBackCount(tab))
    }

    @Test
    @Config(sdk = [32])
    fun onScreenKeyboardUp_beforeAndroid13_theHandlersActAsTheyAlwaysDid() {
        // Up to Android 12L Back is a key, and the keyboard app gets it before the window: it goes
        // first without RemBrowse asking, and a keyboard inset that reads "up" must not leave Back
        // with nothing to close (the activity would finish)
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
        assertEquals("Never asked for the keyboard to go", 0, hideRequests)
    }

    @Test
    fun backHidesTheKeyboardFirst_fromAndroid13() {
        for (sdk in listOf(26, 30, 31, 32)) {
            assertFalse("SDK $sdk, keyboard up", backHidesKeyboardFirst(imeVisible = true, sdkInt = sdk))
        }
        for (sdk in listOf(33, 34, 35, 36)) {
            assertTrue("SDK $sdk, keyboard up", backHidesKeyboardFirst(imeVisible = true, sdkInt = sdk))
            assertFalse("SDK $sdk, keyboard down", backHidesKeyboardFirst(imeVisible = false, sdkInt = sdk))
        }
    }

    /** Android puts the keyboard away, as asked: its inset goes. */
    private fun keyboardGoes() = setKeyboard(up = false)

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

/**
 * The InputManager of a laptop: one touchpad (a mouse source, not a touchscreen), which
 * [PointerDevices.hasMouseOrTouchpad] counts. Robolectric's own lists no devices.
 */
@Implements(InputManager::class)
class ShadowInputManagerWithTouchpad : ShadowInputManager() {

    @Implementation
    fun getInputDeviceIds(): IntArray = intArrayOf(TOUCHPAD_ID)

    @Implementation
    fun getInputDevice(id: Int): InputDevice? = if (id == TOUCHPAD_ID) touchpad() else null

    private fun touchpad(): InputDevice = ShadowInputDevice.makeInputDeviceNamed("Touchpad").also {
        ReflectionHelpers.setField(it, "mId", TOUCHPAD_ID)
        ReflectionHelpers.setField(it, "mSources", InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD)
    }

    private companion object {
        const val TOUCHPAD_ID = 7
    }
}
