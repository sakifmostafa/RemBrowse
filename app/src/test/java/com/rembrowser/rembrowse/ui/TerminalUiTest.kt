package com.rembrowser.rembrowse.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.data.InMemorySecretStore
import com.rembrowser.rembrowse.data.InMemoryTerminalStore
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.fakes.FakeDownloadHistoryStore
import com.rembrowser.rembrowse.fakes.FakeDownloadStorage
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.model.SettingsSection
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.terminal.HostKeyCheck
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Terminal tabs and Settings → Terminal on the real screen, against a fake SSH server. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class TerminalUiTest {

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

    @After
    fun tearDown() {
        terminalVm.closeAll()
        uiScope.cancel()
    }

    private fun launch() {
        composeRule.setContent {
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
        composeRule.waitForIdle()
    }

    private fun activeStatus(): TerminalStatus = terminalVm.session(browserVm.uiState.value.activeTabId!!).status.value

    /** Waits for [condition] about the view models, letting the main thread run what was posted to it. */
    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(5_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theMoreMenu_showsTheNewTerminalShortcut() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNode(hasTestTag("menu_new_terminal")).assertIsDisplayed()
        composeRule.onNode(hasText("Ctrl+Shift+T") and hasAnyAncestor(hasTestTag("menu_new_terminal")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aNewTerminal_connectsToATypedAddress_askingForThePassword() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_new_terminal").performClick()
        // The picker's heading, and the address bar
        assertEquals(2, composeRule.onAllNodesWithText("New terminal").fetchSemanticsNodes().size)
        assertTrue(browserVm.uiState.value.activeTab!!.isTerminal)

        composeRule.onNodeWithTag("terminal_quick_connect").performTextInput("not an address")
        composeRule.onNodeWithTag("terminal_quick_connect_go").assertIsNotEnabled()
        composeRule.onNodeWithTag("terminal_quick_connect").performTextReplacement("ssh root@10.0.0.2 -p 2222")
        composeRule.onNodeWithTag("terminal_quick_connect_go").assertIsEnabled().performClick()

        waitForTag("terminal_password")
        assertEquals("rem://terminal/ssh/root@10.0.0.2:2222", browserVm.uiState.value.activeTab!!.url)
        assertEquals("10.0.0.2", connector.requests.single().hostname)
        composeRule.onNodeWithText("Password for root").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_password_field").performTextInput("secret")
        composeRule.onNodeWithTag("terminal_password_go").performClick()

        waitFor { activeStatus() is TerminalStatus.Connected }
        composeRule.onNodeWithTag("terminal_password").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_screen").assertIsDisplayed()
        waitFor { browserVm.uiState.value.activeTab!!.title == "root@10.0.0.2:2222" }

        // The shell ends: the screen stays, with a way back
        composeRule.runOnUiThread { connector.shells.single().end(null) }
        waitForTag("terminal_disconnected")
        composeRule.onNodeWithTag("terminal_reconnect").performClick()
        waitForTag("terminal_password")
    }

    @Test
    fun goToAddress_connectsToASavedHost_byPartOfItsName() {
        val host = terminalVm.saveHost(SshHost(id = "", label = "Home server", hostname = "home.example", username = "sakif"))
        launch()
        composeRule.runOnUiThread { chrome.toggleGoToAddress() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("go_to_address_input").performTextInput("home")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("palette_ssh_${host.id}").assertIsDisplayed().assertIsSelected()
        composeRule.onNode(
            hasText("Connect to Home server (sakif@home.example)") and hasAnyAncestor(hasTestTag("palette_ssh_${host.id}")),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("go_to_address_input").performKeyInput { pressKey(Key.Enter) }

        waitFor { connector.requests.isNotEmpty() }
        assertEquals("rem://terminal/host/${host.id}", browserVm.uiState.value.activeTab!!.url)
        assertEquals("home.example", connector.requests.single().hostname)
    }

    @Test
    fun aHostAddedInSettings_connectsFromThere_afterItsKeyIsTrusted() {
        connector.hostKey = HostKeyCheck("", 0, "ssh-ed25519", "AAAAkey", "SHA256:fingerprint", changed = false)
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_add_host").performClick()
        composeRule.onNodeWithTag("host_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("host_label").performTextInput("Home")
        composeRule.onNodeWithTag("host_hostname").performTextInput("home.example")
        composeRule.onNodeWithTag("host_username").performTextInput("sakif")
        composeRule.onNodeWithTag("host_password").performTextInput("secret")
        composeRule.onNodeWithTag("host_save_password").performClick()
        composeRule.onNodeWithTag("host_save").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        val host = terminalVm.uiState.value.hosts.single()
        assertTrue(host.hasSavedPassword)
        composeRule.onNodeWithText("sakif@home.example · saved password").assertIsDisplayed()

        composeRule.onNodeWithTag("terminal_connect_${host.id}").performClick()
        waitForTag("terminal_host_key_new")
        composeRule.onNodeWithText("SHA256:fingerprint").assertIsDisplayed()
        composeRule.onNodeWithTag("host_key_accept").performClick()

        waitFor { activeStatus() is TerminalStatus.Connected }
        assertEquals("rem://terminal/host/${host.id}", browserVm.uiState.value.activeTab!!.url)
        waitFor { terminalVm.uiState.value.knownHosts.isNotEmpty() }

        // Settings now lists the trusted server
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.onNode(hasText("home.example:22")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aGeneratedKey_showsItsPublicHalfToCopy() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.onNodeWithTag("terminal_generate_key").performScrollTo().performClick()
        composeRule.onNodeWithTag("key_generate").performClick()
        waitForTag("public_key_text")
        val key = terminalVm.uiState.value.keys.single()
        composeRule.onNodeWithText(key.publicKey).assertIsDisplayed()
        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
    }

    @Test
    fun anUnreachableServer_isExplained_andCanBeTriedAgain() {
        connector.failure = "Couldn't find nowhere.example:22. Check the host name."
        launch()
        composeRule.runOnUiThread {
            val tab = browserVm.openTerminal()
            browserVm.showTerminalTarget(tab, com.rembrowser.rembrowse.model.TerminalTarget.Address("me", "nowhere.example"))
            terminalVm.connect(tab, com.rembrowser.rembrowse.model.TerminalTarget.Address("me", "nowhere.example"))
        }
        waitForTag("terminal_failed")
        composeRule.onNodeWithText("Couldn't connect to me@nowhere.example").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't find nowhere.example:22. Check the host name.").assertIsDisplayed()

        connector.failure = null
        composeRule.onNodeWithTag("terminal_retry").performClick()
        waitForTag("terminal_password")
    }

    @Test
    fun theHeadersThemeMenu_changesEveryTerminalsColors() {
        launch()
        composeRule.runOnUiThread {
            val tab = browserVm.openTerminal()
            browserVm.showTerminalTarget(tab, com.rembrowser.rembrowse.model.TerminalTarget.Address("me", "server.example"))
        }
        waitForTag("terminal_header")
        composeRule.onNodeWithTag("terminal_theme_button").performClick()
        composeRule.onNodeWithTag("terminal_theme_midnight_green").performClick()
        waitFor { terminalVm.uiState.value.settings.theme == TerminalThemePreset.MIDNIGHT_GREEN }
        waitFor { com.rembrowser.rembrowse.terminal.TerminalTheme.current == TerminalThemePreset.MIDNIGHT_GREEN }

        composeRule.onNodeWithTag("terminal_header").assertIsDisplayed()
        composeRule.runOnUiThread { terminalVm.setTheme(TerminalThemePreset.DEFAULT) }
    }

    private fun openTerminalHeader() {
        launch()
        composeRule.runOnUiThread {
            val tab = browserVm.openTerminal()
            browserVm.showTerminalTarget(tab, com.rembrowser.rembrowse.model.TerminalTarget.Address("me", "server.example"))
        }
        waitForTag("terminal_header")
    }

    @Test
    fun theHeadersPaletteMenu_hasTheTextSize_andStaysOpenWhileItChanges() {
        openTerminalHeader()
        composeRule.onNodeWithTag("terminal_text_size").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_theme_button").performClick()
        composeRule.onNodeWithTag("terminal_theme_menu").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_text_size").assertIsDisplayed()

        val before = terminalVm.uiState.value.settings.fontSizeSp
        composeRule.onNodeWithTag("terminal_text_larger").performClick()
        waitFor { terminalVm.uiState.value.settings.fontSizeSp == before + 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_text_size").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("terminal_text_smaller"))).assertIsEnabled()
        composeRule.runOnUiThread {
            terminalVm.setFontSize(before)
            terminalVm.setTheme(TerminalThemePreset.DEFAULT)
        }
    }

    @Test
    fun textSmaller_isDisabledAtTheSmallestSize() {
        openTerminalHeader()
        composeRule.runOnUiThread { terminalVm.setFontSize(com.rembrowser.rembrowse.data.TerminalSettings.MIN_FONT_SIZE_SP) }
        waitFor { terminalVm.uiState.value.settings.fontSizeSp == com.rembrowser.rembrowse.data.TerminalSettings.MIN_FONT_SIZE_SP }
        composeRule.onNodeWithTag("terminal_theme_button").performClick()
        // The tag is on the button's outer box; the enabled state lives on the clickable inside it
        val inside = { tag: String -> hasClickAction() and hasAnyAncestor(hasTestTag(tag)) }
        composeRule.onNode(inside("terminal_text_smaller")).assertIsNotEnabled()
        composeRule.onNode(inside("terminal_text_larger")).assertIsEnabled()
        composeRule.runOnUiThread {
            terminalVm.setFontSize(com.rembrowser.rembrowse.data.TerminalSettings.DEFAULT_FONT_SIZE_SP)
            terminalVm.setTheme(TerminalThemePreset.DEFAULT)
        }
    }

    @Test
    fun theQuickConnectField_explainsAnAddressItCantRead_untilItCan() {
        launch()
        composeRule.onNodeWithTag("more_button").performClick()
        composeRule.onNodeWithTag("menu_new_terminal").performClick()
        val hint = "Type it as user@host, with :port if it isn't 22"
        composeRule.onNodeWithText(hint).assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_quick_connect_go").assertIsNotEnabled()

        composeRule.onNodeWithTag("terminal_quick_connect").performTextInput("not an address")
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_quick_connect_go").assertIsNotEnabled()

        composeRule.onNodeWithTag("terminal_quick_connect").performTextReplacement("ssh root@10.0.0.2 -p 2222")
        composeRule.onNodeWithText(hint).assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_quick_connect_go").assertIsEnabled()
    }

    @Test
    fun theLookAndSuggestions_areSetInSettings() {
        launch()
        composeRule.runOnUiThread { browserVm.openSettings(SettingsSection.TERMINAL) }
        composeRule.onNodeWithTag("terminal_theme_card_nord").performScrollTo().performClick()
        composeRule.onNodeWithTag("terminal_font_fira_mono").performScrollTo().performClick()
        composeRule.onNodeWithTag("terminal_cursor_bar").performScrollTo().performClick()
        waitFor {
            terminalVm.uiState.value.settings.let {
                it.theme == TerminalThemePreset.NORD && it.font == TerminalFont.FIRA_MONO && it.cursorStyle == TerminalCursorStyle.BAR
            }
        }

        composeRule.runOnUiThread { terminalVm.recordCommand("git stash pop") }
        composeRule.onNodeWithTag("terminal_clear_history").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("terminal_remember_commands").performScrollTo().performClick()
        waitFor { !terminalVm.uiState.value.settings.rememberCommands && terminalVm.uiState.value.rememberedCommands == 0 }
        composeRule.onNodeWithTag("terminal_suggestions").performScrollTo().performClick()
        waitFor { !terminalVm.uiState.value.settings.suggestions }
        composeRule.runOnUiThread { terminalVm.setTheme(TerminalThemePreset.DEFAULT) }
    }
}
