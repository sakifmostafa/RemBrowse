package com.rembrowser.rembrowse.ui

import android.app.Application
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
import com.rembrowser.rembrowse.data.InMemorySecretStore
import com.rembrowser.rembrowse.data.InMemoryTerminalStore
import com.rembrowser.rembrowse.data.SecretStore
import com.rembrowser.rembrowse.engine.PointerDevices
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.terminal.TerminalScreenView
import com.rembrowser.rembrowse.terminal.TerminalStatus
import com.rembrowser.rembrowse.ui.components.LocalSiteIconsEnabled
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.DownloadsViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel
import com.rembrowser.rembrowse.viewmodel.TerminalViewModel
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.shadows.ShadowInputManager
import org.robolectric.shadows.ShadowLooper
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

    // Terminals, for the tests of touch selection: one saved host, with its password saved
    private val connector = FakeSshConnector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminalVm = TerminalViewModel(
        store = InMemoryTerminalStore(
            hosts = listOf(SshHost("vps", "Web server", "203.0.113.24", 22, "deploy", SshAuthMethod.PASSWORD, hasSavedPassword = true))
        ),
        secrets = InMemorySecretStore().apply { put(SecretStore.hostPassword("vps"), "secret") },
        connector = connector,
        scope = uiScope,
        postToMain = { mainHandler.post(it) },
        onTabTitleChanged = browserVm::setTerminalTitle
    )

    @After
    fun tearDown() {
        terminalVm.closeAll()
        uiScope.cancel()
    }

    /** Shows the screen; [terminals] shows terminal tabs (without it, they don't show). */
    @OptIn(ExperimentalLayoutApi::class)
    private fun launch(terminals: TerminalViewModel? = null) {
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
                        terminalViewModel = terminals,
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
    private fun launchOnAPageWithHistory(terminals: TerminalViewModel? = null): String {
        launch(terminals)
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

    // --- A terminal's touch selection: Back puts it away, and does nothing else ---
    // These run with real text measurement (NATIVE graphics): without it a terminal has no cell
    // size, so it never takes its view's size and every point is its first cell.

    /**
     * Opens a terminal on the saved host in pane [inPane] (the focused one by default), and waits
     * until it is connected and shows "hello world"; returns its tab and its shell.
     */
    private fun openConnectedTerminal(inPane: Int? = null): Pair<String, FakeSshConnector.FakeShell> {
        val tab = composeRule.runOnUiThread {
            inPane?.let { browserVm.focusPane(it) }
            browserVm.openTerminal(TerminalTarget.SavedHost("vps")).also { terminalVm.connect(it, TerminalTarget.SavedHost("vps")) }
        }
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            terminalVm.session(tab).status.value is TerminalStatus.Connected
        }
        val shell = connector.shells.last()
        composeRule.runOnUiThread { shell.print("hello world\r\n") }
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        return tab to shell
    }

    /** The terminal view showing [tab]. */
    private fun terminalView(tab: String): TerminalScreenView =
        terminalViews(composeRule.activity.window.decorView).single { it.connection === terminalVm.session(tab) }

    private fun terminalViews(view: View): List<TerminalScreenView> = when (view) {
        is TerminalScreenView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { terminalViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private var pointerDownAt = 0L

    /** A finger on the screen at [cell] of [view], or with [mouseButtons], the mouse. */
    private fun pointer(view: TerminalScreenView, action: Int, cell: TerminalScreenView.Cell, mouseButtons: Int? = null) {
        val at = view.touchSelection.cellCenter(cell)
        composeRule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) pointerDownAt = now
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = if (mouseButtons != null) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = at.x; y = at.y; pressure = 1f; size = 1f })
            val source = if (mouseButtons != null) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN
            val event = MotionEvent.obtain(pointerDownAt, now, action, 1, properties, coords, 0, mouseButtons ?: 0, 1f, 1f, 0, 0, source, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /** A finger long-presses [cell] of [view] and lifts: touch selection, with its handles and Android's text toolbar. */
    private fun longPress(view: TerminalScreenView, cell: TerminalScreenView.Cell) {
        pointer(view, MotionEvent.ACTION_DOWN, cell)
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS)
        pointer(view, MotionEvent.ACTION_UP, cell)
        composeRule.waitForIdle()
        assertTrue("Touch selection shows", view.touchSelection.isActive)
        assertNotNull("with the toolbar", view.touchSelection.toolbar)
    }

    /**
     * A tap on the terminal with nothing selected brings up the keyboard, as on a device: the view
     * asks Android for it, and its inset comes up.
     */
    private fun keyboardUpForTheTerminal(view: TerminalScreenView) {
        pointer(view, MotionEvent.ACTION_DOWN, TerminalScreenView.Cell(20, 3))
        pointer(view, MotionEvent.ACTION_UP, TerminalScreenView.Cell(20, 3))
        composeRule.waitForIdle()
        val imm = ApplicationProvider.getApplicationContext<Application>().getSystemService(InputMethodManager::class.java)
        assertTrue("The terminal asked for the keyboard", shadowOf(imm).isSoftInputVisible)
        setKeyboard(up = true)
        assertTrue(imeVisible)
    }

    /** A double click on [cell] of [view]: the word there, selected the mouse's way. */
    private fun doubleClick(view: TerminalScreenView, cell: TerminalScreenView.Cell) {
        repeat(2) {
            pointer(view, MotionEvent.ACTION_DOWN, cell, mouseButtons = MotionEvent.BUTTON_PRIMARY)
            pointer(view, MotionEvent.ACTION_UP, cell, mouseButtons = 0)
        }
        composeRule.waitForIdle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTerminalsTouchSelection_backPutsItAway_andStaysInTheTerminal() {
        launch(terminalVm)
        val (tab, shell) = openConnectedTerminal()
        assertFalse("Nothing for Back to do on a phone yet: Android's, as before", backDispatcher!!.hasEnabledCallbacks())
        val view = terminalView(tab)
        longPress(view, TerminalScreenView.Cell(1, 0))
        assertEquals("hello", view.selectedText())
        assertTrue("RemBrowse's while it shows", backDispatcher!!.hasEnabledCallbacks())

        pressBack()
        assertNull(view.selection)
        assertFalse(view.touchSelection.isActive)
        assertNull("The toolbar closed", view.touchSelection.toolbar)
        assertEquals("Still the tab shown", tab, browserVm.uiState.value.activeTabId)
        assertTrue("and still connected", terminalVm.session(tab).status.value is TerminalStatus.Connected)
        assertFalse(composeRule.activity.isFinishing)
        assertEquals("Nothing typed", "", shell.typed())
        assertFalse("The next Back is Android's again", backDispatcher!!.hasEnabledCallbacks())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aMouseSelection_isNotTouchSelection_andLeavesBackAsItWas() {
        launch(terminalVm)
        val (tab) = openConnectedTerminal()
        val view = terminalView(tab)
        doubleClick(view, TerminalScreenView.Cell(1, 0))
        assertEquals("hello", view.selectedText())
        assertFalse(view.touchSelection.isActive)
        assertFalse("Android's, as before", backDispatcher!!.hasEnabledCallbacks())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inASplit_theTouchSelectionGoesBeforeThePageBesideItGoesBack() {
        val page = launchOnAPageWithHistory(terminalVm)
        composeRule.runOnUiThread { browserVm.setLayoutMode(LayoutMode.SPLIT_1X2) }
        composeRule.waitForIdle()
        val (terminal) = openConnectedTerminal(inPane = 1)
        val view = terminalView(terminal)
        longPress(view, TerminalScreenView.Cell(1, 0))
        // The page beside it has the focus, and somewhere to go back to
        composeRule.runOnUiThread { browserVm.focusPane(0) }
        composeRule.waitForIdle()
        assertEquals(page, browserVm.uiState.value.activeTabId)
        assertTrue(view.touchSelection.isActive)

        pressBack()
        assertFalse(view.touchSelection.isActive)
        assertEquals("The page waits its turn", 0, goBackCount(page))
        pressBack()
        assertEquals(1, goBackCount(page))
        assertEquals(terminal, browserVm.uiState.value.paneTabIds[1])
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun twoTerminalsInASplit_backPutsAwayTheOneWhoseTouchSelectionShows() {
        launch(terminalVm)
        composeRule.runOnUiThread { browserVm.setLayoutMode(LayoutMode.SPLIT_1X2) }
        composeRule.waitForIdle()
        val (left) = openConnectedTerminal(inPane = 0)
        val (right) = openConnectedTerminal(inPane = 1)
        val leftView = terminalView(left)
        val rightView = terminalView(right)
        doubleClick(leftView, TerminalScreenView.Cell(7, 0))
        assertEquals("world", leftView.selectedText())
        longPress(rightView, TerminalScreenView.Cell(1, 0))

        pressBack()
        assertFalse(rightView.touchSelection.isActive)
        assertNull(rightView.selection)
        assertEquals("The mouse's selection beside it stays", "world", leftView.selectedText())
        assertEquals(listOf(left, right), browserVm.uiState.value.paneTabIds)
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keyboardUp_backPutsItAwayFirst_thenTheTouchSelection() {
        launch(terminalVm)
        val (tab) = openConnectedTerminal()
        val view = terminalView(tab)
        keyboardUpForTheTerminal(view)
        longPress(view, TerminalScreenView.Cell(1, 0))
        val asked = hideRequests

        pressBack()
        assertEquals("The first Back asked for the keyboard to go", asked + 1, hideRequests)
        assertTrue("and left the selection", view.touchSelection.isActive)

        // The inset still reads up: the next Back is the touch selection's
        pressBack()
        assertFalse(view.touchSelection.isActive)
        assertNull(view.selection)
        assertEquals(tab, browserVm.uiState.value.activeTabId)
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keyboardUp_whenTheKeyboardGoes_theTerminalsNewSizeTakesTheSelectionWithIt() {
        // As on a device: the inset goes, the terminal grows by the keyboard's height, and a new
        // size clears a selection (its rows would point at other text), touch selection included
        launch(terminalVm)
        val (tab) = openConnectedTerminal()
        val view = terminalView(tab)
        keyboardUpForTheTerminal(view)
        longPress(view, TerminalScreenView.Cell(1, 0))

        pressBack()
        assertTrue(view.touchSelection.isActive)
        keyboardGoes()
        assertFalse("Gone with the new size", view.touchSelection.isActive)
        assertFalse("Back is Android's again, as before", backDispatcher!!.hasEnabledCallbacks())
        assertEquals(tab, browserVm.uiState.value.activeTabId)
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
