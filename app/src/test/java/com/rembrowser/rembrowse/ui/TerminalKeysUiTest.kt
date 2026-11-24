package com.rembrowser.rembrowse.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.BrowserKeys
import com.rembrowser.rembrowse.data.InMemorySecretStore
import com.rembrowser.rembrowse.data.InMemoryTerminalStore
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.FakeSshConnector
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A connected terminal with the keyboard, its keys going through the window as MainActivity's
 * do: the window's content root ([BrowserKeys.windowContent]) reads them before the keyboard app,
 * then the activity's dispatchKeyEvent ([BrowserKeys.dispatchKeyEvent]) before the focused view,
 * the terminal's own [TerminalScreenView]. The shell keeps Esc, Ctrl+C and Ctrl+L; the browser's
 * Ctrl+Shift keys, Ctrl+Shift+T among them, reach the browser.
 *
 * The test sets both hooks up itself; MainActivityInputTest checks that the activity's
 * dispatchKeyEvent hands keys to BrowserKeys.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TerminalKeysUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserVm = BrowserViewModel(FakeBrowserEngine(), FakeBrowserStateRepository())
    private val extVm = ExtensionsViewModel(FakeExtensionManager(), FakeAmoApiClient(), uiScope)
    private val downloadsVm = DownloadsViewModel(
        storage = FakeDownloadStorage(),
        history = FakeDownloadHistoryStore(),
        scope = uiScope,
        ioDispatcher = Dispatchers.Unconfined
    )
    private val chrome = BrowserChromeState()
    private val connector = FakeSshConnector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminalVm = TerminalViewModel(
        store = InMemoryTerminalStore(),
        secrets = InMemorySecretStore(),
        connector = connector,
        scope = uiScope,
        postToMain = { mainHandler.post(it) },
        onTabTitleChanged = browserVm::setTerminalTitle
    )
    private val keys = BrowserKeys(browserVm, chrome)

    @After
    fun tearDown() {
        terminalVm.closeAll()
        uiScope.cancel()
    }

    /**
     * The view with the focus, as MainActivity's currentFocus gives it: the window's
     * (Robolectric's activity answers null whatever has the focus).
     */
    private fun focus(): View? = composeRule.activity.window.currentFocus

    /** Waits for [condition], letting the main thread run what was posted to it. */
    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(5_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    /**
     * The screen in the activity's window as MainActivity puts it there, inside the layout that
     * reads keys before the keyboard app, and with the activity's dispatchKeyEvent handing keys to
     * the browser first (the window's callback is the activity, as MainActivity's override is).
     */
    private fun launch() {
        composeRule.runOnUiThread {
            val activity = composeRule.activity
            val screen = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(LocalSiteIconsEnabled provides false) {
                        RemBrowseTheme {
                            RemBrowseScreen(
                                browserViewModel = browserVm,
                                extensionsViewModel = extVm,
                                downloadsViewModel = downloadsVm,
                                chrome = chrome,
                                versionName = "test",
                                onPickLocalXpi = {},
                                terminalViewModel = terminalVm,
                                paneViewport = { _, modifier -> Box(modifier) },
                                extensionPopupContent = { Box(it) }
                            )
                        }
                    }
                }
            }
            activity.setContentView(keys.windowContent(screen) { focus() })
            val toActivity = activity.window.callback
            activity.window.callback = object : Window.Callback by toActivity {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    keys.dispatchKeyEvent(event, focus()) { toActivity.dispatchKeyEvent(it) }
            }
        }
        composeRule.waitForIdle()
    }

    /** A terminal tab connected to a saved host, its screen with the keyboard; returns its shell. */
    private fun connectedTerminal(): FakeSshConnector.FakeShell {
        val host = terminalVm.saveHost(SshHost(id = "", label = "Home", hostname = "home.example", username = "sakif"), password = "secret")
        composeRule.runOnUiThread {
            val target = TerminalTarget.SavedHost(host.id)
            val tab = browserVm.openTerminal(target)
            terminalVm.connect(tab, target)
        }
        waitFor { terminalVm.session(browserVm.uiState.value.activeTabId!!).status.value is TerminalStatus.Connected }
        waitFor { focus() is TerminalScreenView }
        return connector.shells.single()
    }

    /**
     * Presses and lets go of [keyCode] on a physical keyboard, through the window as Android hands
     * it on: down the focused views' ancestors before the keyboard app (there is none here), then
     * to the window's callback, the activity.
     */
    private fun press(keyCode: Int, metaState: Int = 0) {
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(
                    time, time, action, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                )
                if (!decor.dispatchKeyEventPreIme(event)) decor.dispatchKeyEvent(event)
            }
        }
        composeRule.waitForIdle()
    }

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val ctrlShift = ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    @Test
    fun theShellKeepsEscCtrlCAndCtrlL_andCtrlShiftTOpensANewTerminal() {
        launch()
        val shell = connectedTerminal()
        val terminalTab = browserVm.uiState.value.activeTabId!!
        val tabCount = browserVm.uiState.value.tabs.size

        // Esc, Ctrl+C and Ctrl+L: to the shell, as they are typed
        press(KeyEvent.KEYCODE_ESCAPE)
        press(KeyEvent.KEYCODE_C, ctrl)
        press(KeyEvent.KEYCODE_L, ctrl)
        waitFor { shell.typed().length >= 3 }
        assertEquals("\u001b\u0003\u000c", shell.typed())
        assertFalse("Ctrl+L stays the shell's: not the address bar", browserVm.uiState.value.isAddressBarFocused)
        assertEquals(tabCount, browserVm.uiState.value.tabs.size)

        // Ctrl+Shift+T: the browser opens a new terminal, and the shell sees neither its press nor its release
        press(KeyEvent.KEYCODE_T, ctrlShift)
        assertEquals(tabCount + 1, browserVm.uiState.value.tabs.size)
        val opened = browserVm.uiState.value.activeTab!!
        assertTrue("A new terminal, shown", opened.isTerminal && opened.id != terminalTab)
        assertEquals("Nothing more typed", "\u001b\u0003\u000c", shell.typed())
    }
}
