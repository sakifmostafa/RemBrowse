package com.rembrowser.rembrowse.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.data.InMemorySecretStore
import com.rembrowser.rembrowse.data.InMemoryTerminalStore
import com.rembrowser.rembrowse.data.SecretStore
import com.rembrowser.rembrowse.data.TerminalSettings
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.fakes.TestMainQueue
import com.rembrowser.rembrowse.model.SshAuthMethod
import com.rembrowser.rembrowse.model.SshHost
import com.rembrowser.rembrowse.model.TerminalCursorStyle
import com.rembrowser.rembrowse.model.TerminalFont
import com.rembrowser.rembrowse.model.TerminalThemePreset
import com.rembrowser.rembrowse.model.TerminalTarget
import com.rembrowser.rembrowse.terminal.HostKeyCheck
import com.rembrowser.rembrowse.terminal.SshCredentials
import com.rembrowser.rembrowse.terminal.TerminalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TerminalViewModelTest {

    private val main = TestMainQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = InMemoryTerminalStore()
    private val secrets = InMemorySecretStore()
    private val connector = FakeSshConnector()
    private val titles = mutableMapOf<String, String>()
    private var ids = 0

    private val vm = TerminalViewModel(
        store = store,
        secrets = secrets,
        connector = connector,
        scope = scope,
        postToMain = main::post,
        idGenerator = { "id${++ids}" },
        clock = { 1_000L },
        onTabTitleChanged = { tab, title -> titles[tab] = title }
    )

    @After
    fun tearDown() {
        vm.closeAll()
        scope.cancel()
    }

    private fun idle() {
        main.drain()
        ShadowLooper.idleMainLooper()
    }

    private fun server(auth: SshAuthMethod = SshAuthMethod.PASSWORD, password: String? = null, keyId: String? = null): SshHost =
        vm.saveHost(
            SshHost(id = "", label = "Home server", hostname = "home.example", port = 2222, username = "sakif", auth = auth, keyId = keyId),
            password = password
        )

    private fun status(tab: String) = vm.session(tab).status.value

    @Test
    fun aSavedPassword_connectsWithoutAsking_andTheShellRunsAtTheTerminalsSize() {
        val host = server(password = "secret")
        assertTrue(host.hasSavedPassword)
        assertEquals("secret", secrets.get(SecretStore.hostPassword(host.id)))

        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }

        val request = connector.requests.single()
        assertEquals("home.example", request.hostname)
        assertEquals(2222, request.port)
        assertEquals("sakif", request.username)
        assertEquals(SshCredentials.Password("secret"), request.credentials)
        assertEquals(80, request.columns)
        assertEquals(24, request.rows)
    }

    @Test
    fun aPasswordIsAskedFor_andRememberedWhenTicked() {
        val host = server()
        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the password question") { status("tab") is TerminalStatus.NeedsPassword }
        val asked = status("tab") as TerminalStatus.NeedsPassword
        assertFalse(asked.retry)
        assertTrue("A saved host can keep it", asked.canRemember)

        vm.session("tab").answerPassword("wrong", remember = true)
        main.drainUntil("the second question") { (status("tab") as? TerminalStatus.NeedsPassword)?.retry == true }
        assertNull("Not saved before it works", secrets.get(SecretStore.hostPassword(host.id)))

        vm.session("tab").answerPassword("secret", remember = true)
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }
        idle()
        assertEquals("secret", secrets.get(SecretStore.hostPassword(host.id)))
        assertTrue(vm.hostById(host.id)!!.hasSavedPassword)
    }

    @Test
    fun aTypedAddress_cannotRememberAPassword_andCancellingDisconnects() {
        vm.connect("tab", TerminalTarget.Address("root", "10.0.0.2", 22))
        main.drainUntil("the password question") { status("tab") is TerminalStatus.NeedsPassword }
        assertFalse((status("tab") as TerminalStatus.NeedsPassword).canRemember)

        vm.session("tab").answerPassword(null)
        main.drainUntil("the failure") { status("tab") is TerminalStatus.Disconnected }
        val failed = status("tab") as TerminalStatus.Disconnected
        assertTrue(failed.failedToConnect)
        assertEquals("root@10.0.0.2", failed.label)
    }

    @Test
    fun aNewServerIsConfirmed_thenRemembered() {
        connector.hostKey = HostKeyCheck("", 0, "ssh-ed25519", "AAAAkey1", "SHA256:abc", changed = false)
        val host = server(password = "secret")

        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the key question") { status("tab") is TerminalStatus.ConfirmingHostKey }
        val check = (status("tab") as TerminalStatus.ConfirmingHostKey).check
        assertEquals("home.example", check.hostname)
        assertFalse(check.changed)

        vm.session("tab").answerHostKey(true)
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }
        idle()
        val known = vm.uiState.value.knownHosts.single()
        assertEquals("home.example:2222", known.hostPort)
        assertEquals("SHA256:abc", known.fingerprint)

        // Next time it is known: no question
        vm.connect("tab2", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the second shell") { status("tab2") is TerminalStatus.Connected }

        // Forgotten, it is asked again; refused, there is no shell
        vm.forgetKnownHost("home.example:2222")
        vm.connect("tab3", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the key question again") { status("tab3") is TerminalStatus.ConfirmingHostKey }
        vm.session("tab3").answerHostKey(false)
        main.drainUntil("the refusal") { status("tab3") is TerminalStatus.Disconnected }
        assertTrue((status("tab3") as TerminalStatus.Disconnected).failedToConnect)
    }

    @Test
    fun keyHosts_useTheirKey_andAMissingKeyIsExplained() {
        val key = vm.generateKey("googlebook")
        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(secrets.get(SecretStore.privateKey(key.id))!!.contains("OPENSSH PRIVATE KEY"))
        val host = server(auth = SshAuthMethod.KEY, keyId = key.id)

        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }
        assertTrue(connector.requests.single().credentials is SshCredentials.Key)

        vm.deleteKey(key.id)
        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drain()
        val failed = status("tab") as TerminalStatus.Disconnected
        assertTrue(failed.failedToConnect)
        assertTrue(failed.reason!!, failed.reason.contains("key"))
        assertEquals("No second attempt was made", 1, connector.requests.size)
    }

    @Test
    fun aDeletedHost_isExplained() {
        vm.connect("tab", TerminalTarget.SavedHost("gone"))
        val failed = status("tab") as TerminalStatus.Disconnected
        assertTrue(failed.reason!!.contains("deleted"))
        assertTrue(connector.requests.isEmpty())
    }

    @Test
    fun outputReachesTheScreen_theShellsTitleNamesTheTab_andTypingReachesTheServer() {
        val host = server(password = "secret")
        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }
        idle()
        assertEquals("Until the shell names itself, the tab has the host's name", "Home server", titles["tab"])

        val shell = connector.shells.single()
        shell.print("\u001b]0;sakif@home: ~\u0007Welcome to home\r\n$ ")
        idle()
        val screen = vm.session("tab").emulator.screen.transcriptText
        assertTrue(screen, screen.contains("Welcome to home"))
        assertEquals("sakif@home: ~", titles["tab"])

        vm.session("tab").sendInput("ls -la\r")
        assertEquals("ls -la\r", shell.typed())

        vm.session("tab").resize(120, 40, 9, 18)
        assertEquals(listOf(120 to 40), shell.sizes)
        assertEquals(120, vm.session("tab").emulator.mColumns)
    }

    @Test
    fun theShellEnding_leavesTheScreen_andReconnectStartsAgain() {
        val host = server(password = "secret")
        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the shell") { status("tab") is TerminalStatus.Connected }
        connector.shells.single().print("still here\r\n")
        connector.shells.single().end("Connection reset")
        idle()
        val ended = status("tab") as TerminalStatus.Disconnected
        assertEquals("Connection reset", ended.reason)
        assertFalse(ended.failedToConnect)
        assertTrue(vm.session("tab").emulator.screen.transcriptText.contains("still here"))

        vm.connect("tab", TerminalTarget.SavedHost(host.id))
        main.drainUntil("the new shell") { status("tab") is TerminalStatus.Connected }
        assertEquals(2, connector.shells.size)
    }

    @Test
    fun closingTheTab_endsItsConnection() {
        val host = server(password = "secret")
        vm.connect("a", TerminalTarget.SavedHost(host.id))
        vm.connect("b", TerminalTarget.SavedHost(host.id))
        main.drainUntil("both shells") { status("a") is TerminalStatus.Connected && status("b") is TerminalStatus.Connected }

        vm.retainSessions(setOf("b"))
        assertTrue(connector.shells.first { it.request.hostname == "home.example" }.closed)
        assertFalse(connector.shells.last().closed)
    }

    @Test
    fun hostsKeysAndSettings_areSaved() {
        val host = server(password = "pw")
        assertEquals(listOf(host), store.loadHosts())

        // Editing keeps the id; switching to a key drops the saved password
        val key = vm.generateKey("laptop key")
        val edited = vm.saveHost(host.copy(label = "Home", auth = SshAuthMethod.KEY, keyId = key.id))
        assertEquals(host.id, edited.id)
        assertFalse(edited.hasSavedPassword)
        assertNull(secrets.get(SecretStore.hostPassword(host.id)))
        assertEquals("laptop-key", key.publicKey.substringAfterLast(' '))

        assertTrue(vm.importKey("bad", "not a key", null).isFailure)
        assertEquals(1, vm.uiState.value.keys.size)

        vm.deleteHost(host.id)
        assertTrue(vm.uiState.value.hosts.isEmpty())

        vm.setFontSize(99)
        assertEquals(TerminalSettings.MAX_FONT_SIZE_SP, store.loadSettings().fontSizeSp)
    }

    @Test
    fun theLook_isSavedAsItChanges() {
        vm.setTheme(TerminalThemePreset.DRACULA)
        vm.setFont(TerminalFont.FIRA_MONO)
        vm.setCursorStyle(TerminalCursorStyle.UNDERLINE)
        vm.setSuggestions(false)
        val expected = TerminalSettings(
            theme = TerminalThemePreset.DRACULA,
            font = TerminalFont.FIRA_MONO,
            cursorStyle = TerminalCursorStyle.UNDERLINE,
            suggestions = false
        )
        assertEquals(expected, vm.uiState.value.settings)
        assertEquals(expected, store.loadSettings())
    }

    @Test
    fun theCopyAndPasteSettings_areSavedAsTheyChange() {
        vm.setCopyOnSelect(false)
        vm.setProgramsCanCopy(false)
        vm.setCtrlVPastes(false)
        val expected = TerminalSettings(copyOnSelect = false, programsCanCopy = false, ctrlVPastes = false)
        assertEquals(expected, vm.uiState.value.settings)
        assertEquals(expected, store.loadSettings())

        vm.setProgramsCanCopy(true)
        assertTrue(store.loadSettings().programsCanCopy)
        assertFalse(store.loadSettings().copyOnSelect)
    }

    @Test
    fun commandsRun_areSuggestedFirst_mostRecentFirst() {
        assertEquals("A common command before any history", "git status", vm.suggestCommand("git st"))
        vm.recordCommand("git stash pop")
        vm.recordCommand("git stash list")
        assertEquals("git stash list", vm.suggestCommand("git st"))
        vm.recordCommand("git stash pop")
        assertEquals("Run again: first again", "git stash pop", vm.suggestCommand("git st"))
        assertEquals(listOf("git stash pop", "git stash list"), store.loadCommandHistory())
        assertEquals(2, vm.uiState.value.rememberedCommands)

        vm.recordCommand(" export TOKEN=abc123")
        assertNull("A leading space keeps a command out", vm.suggestCommand(" export T"))
        assertEquals(2, store.loadCommandHistory().size)
    }

    @Test
    fun notRemembering_forgetsWhatWasRemembered_andRemembersNothingNew() {
        vm.recordCommand("ssh backup@nas.local")
        vm.setRememberCommands(false)
        assertEquals(emptyList<String>(), store.loadCommandHistory())
        assertEquals(0, vm.uiState.value.rememberedCommands)
        vm.recordCommand("ssh backup@nas.local")
        assertEquals(emptyList<String>(), store.loadCommandHistory())
        assertEquals("Common commands are still suggested", "ssh-keygen -t ed25519", vm.suggestCommand("ssh-k"))

        vm.setRememberCommands(true)
        vm.recordCommand("uptime -p")
        vm.clearCommandHistory()
        assertEquals(emptyList<String>(), store.loadCommandHistory())
        assertEquals(0, vm.uiState.value.rememberedCommands)
    }
}
