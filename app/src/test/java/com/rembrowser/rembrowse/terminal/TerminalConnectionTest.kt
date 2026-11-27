package com.rembrowser.rembrowse.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.fakes.TestMainQueue
import java.time.Duration
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TerminalConnectionTest {

    private val main = TestMainQueue()
    private val hostKeys = object : HostKeyStore {
        override fun knownKey(hostname: String, port: Int): Pair<String, String>? = null
        override fun remember(check: HostKeyCheck) {}
    }
    private val spec = TerminalConnectSpec("server", "server", 22, "me", SshCredentials.Password("secret"))

    private fun connectionTo(connector: FakeSshConnector) =
        TerminalConnection("tab", connector, hostKeys, main::post) { it.run() }

    @Test
    fun aShellThatEndsBeforeConnectReturns_endsDisconnected_neverConnected() {
        val connector = FakeSshConnector(endBeforeReturning = FakeSshConnector.EarlyEnd(null))
        val connection = connectionTo(connector)
        connection.connect(spec)
        main.drain()
        assertEquals(TerminalStatus.Disconnected("server", null), connection.status.value)
        assertFalse(connection.isConnected)
        assertTrue("Nothing is kept as the shell", connector.shells.single().closed)
    }

    @Test
    fun aShellThatDropsBeforeConnectReturns_keepsItsReason() {
        val connector = FakeSshConnector(endBeforeReturning = FakeSshConnector.EarlyEnd("The connection was lost."))
        val connection = connectionTo(connector)
        connection.connect(spec)
        main.drain()
        assertEquals(TerminalStatus.Disconnected("server", "The connection was lost."), connection.status.value)
    }

    @Test
    fun aShellThatEndsLater_endsDisconnected() {
        val connector = FakeSshConnector()
        val connection = connectionTo(connector)
        connection.connect(spec)
        main.drain()
        assertTrue(connection.isConnected)
        connector.shells.single().end(null)
        main.drain()
        assertEquals(TerminalStatus.Disconnected("server", null), connection.status.value)
    }

    // --- A program's copy (OSC 52) ---

    private val server = FakeSshConnector()
    private val terminal = connectionTo(server)

    /** What reached the view's copy handler. */
    private val copies = mutableListOf<String>()

    private fun connected(): FakeSshConnector.FakeShell {
        terminal.onProgramCopy = { copies += it }
        terminal.connect(spec)
        main.drain()
        return server.shells.last()
    }

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** What tmux sends when you copy in it (`set-clipboard` external or on). */
    private fun osc52(text: String) = "\u001b]52;c;${base64(text)}\u0007"

    private fun FakeSshConnector.FakeShell.printed(text: String) {
        print(text)
        main.drain()
    }

    @Test
    fun aProgramsCopy_rightAfterTheUsersInput_goesToTheView() {
        val shell = connected()
        terminal.sendInput("y")
        shell.printed(osc52("git log --oneline"))
        assertEquals(listOf("git log --oneline"), copies)

        // Any selection target, ended with ST as well as BEL
        shell.printed("\u001b]52;;${base64("second")}\u001b\\")
        shell.printed("\u001b]52;p;${base64("third ✓")}\u0007")
        assertEquals(listOf("git log --oneline", "second", "third ✓"), copies)
    }

    @Test
    fun aProgramsCopy_withNoInputOrInputOver10sAgo_isDropped() {
        val shell = connected()
        shell.printed(osc52("planted"))
        assertEquals("Nothing typed yet", emptyList<String>(), copies)

        terminal.sendInput("y")
        ShadowSystemClock.advanceBy(Duration.ofMillis(TerminalConnection.PROGRAM_COPY_WINDOW_MS + 1))
        shell.printed(osc52("planted"))
        assertEquals("Typed more than 10 seconds ago", emptyList<String>(), copies)
    }

    @Test
    fun aCopyArrivingAfterTheDeviceSlept_isDropped() {
        val shell = connected()
        terminal.sendInput("y")
        // The screen goes off right after: uptime stops while the device sleeps, real time doesn't
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(5))
        shell.printed(osc52("planted while asleep"))
        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun pastesAndMouseReports_countOnceTheViewMarksThem_butTheEmulatorsOwnAnswersDont() {
        val shell = connected()
        // The server asks where the cursor is; the emulator answers by itself
        shell.printed("\u001b[6n")
        assertTrue("The emulator answered", shell.typed().endsWith("R"))
        shell.printed(osc52("planted"))
        assertEquals(emptyList<String>(), copies)

        terminal.markUserInput()
        shell.printed(osc52("clicked"))
        assertEquals(listOf("clicked"), copies)
    }

    @Test
    fun anEmptyCopyIsIgnored_andAQueryIsNeverAnswered() {
        val shell = connected()
        terminal.sendInput("y")
        shell.printed("\u001b]52;c;\u0007")
        shell.printed("\u001b]52;c;?\u0007")
        shell.printed("\u001b]52;c;?\u001b\\")
        assertEquals(emptyList<String>(), copies)
        assertEquals("Only what was typed went to the server", "y", shell.typed())
    }

    @Test
    fun withNoViewShowingTheTerminal_aCopyIsDropped() {
        val shell = connected()
        terminal.sendInput("y")
        terminal.onProgramCopy = null
        shell.printed(osc52("hidden"))
        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun aNewConnection_doesntCountInputSentToTheLastOne() {
        connected()
        terminal.sendInput("y")
        terminal.connect(spec)
        main.drain()
        server.shells.last().printed(osc52("from the next server"))
        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun aLongCopy_arrivesWhole() {
        val shell = connected()
        // 60,000 characters are 80,000 of base64: more than the 16 KB other control strings may hold
        val text = "0123456789".repeat(6_000)
        terminal.sendInput("y")
        shell.printed(osc52(text))
        assertEquals(1, copies.size)
        assertEquals(text, copies.single())
    }

    @Test
    fun aCopyTooLongToKeep_isDroppedWhole_andTheTerminalCarriesOn() {
        val shell = connected()
        terminal.sendInput("y")
        // More than the 100 KB the emulator keeps of an OSC 52, line breaks and all
        val tooLong = "QUFB".repeat(30_000) + "\r\n" + "QUFB".repeat(5_000)
        shell.printed("before\r\n\u001b]52;c;$tooLong\u0007after\r\n")
        val screen = terminal.emulator.screen.transcriptText
        assertFalse("No base64 on the screen", screen.contains("QUFB"))
        assertTrue(screen, screen.contains("before\nafter"))
        assertEquals(emptyList<String>(), copies)

        // Ended with ST too, and the next copy and escape sequences work as before
        shell.printed("\u001b]52;c;${"QUFB".repeat(30_000)}\u001b\\\u001b[1mbold\u001b[0m\r\n")
        assertFalse(terminal.emulator.screen.transcriptText.contains("QUFB"))
        assertTrue(terminal.emulator.screen.transcriptText.contains("bold"))
        terminal.sendInput("y")
        shell.printed(osc52("fine"))
        assertEquals(listOf("fine"), copies)
    }

    @Test
    fun anOverLongCopyThatNeverEnds_hidesWhatFollowsOnlyForSoLong() {
        val shell = connected()
        // As `printf '\e]52;c;'; head -c 120000 /dev/zero | base64 -w0` sends: no terminator
        shell.printed("\u001b]52;c;" + "AAAA".repeat(40_000))
        shell.printed("hidden\r\n")
        assertFalse(terminal.emulator.screen.transcriptText.contains("hidden"))

        // About a scrollback's worth more, and the terminal gives up on it
        shell.printed("x".repeat(1_100_000) + "\r\nshown again\r\n")
        val screen = terminal.emulator.screen.transcriptText
        assertTrue(screen.takeLast(200), screen.contains("shown again"))
        assertFalse("None of the copy itself", screen.contains("AAAA"))
    }

    @Test
    fun aNewConnection_readsTheServerAfresh_andKeepsTheScreen() {
        val shell = connected()
        shell.printed("from the first server\r\n")
        // The connection drops halfway through a long copy that never ended
        shell.printed("\u001b]52;c;" + "AAAA".repeat(40_000))
        shell.end("Connection reset by peer")
        main.drain()
        terminal.reconnect()
        main.drain()
        server.shells.last().printed("Welcome to Ubuntu\r\n")
        val screen = terminal.emulator.screen.transcriptText
        assertTrue(screen, screen.contains("from the first server"))
        assertTrue(screen, screen.contains("Welcome to Ubuntu"))

        // Nor does an escape sequence cut short take the next server's first letter
        server.shells.last().printed("\u001b[")
        terminal.reconnect()
        main.drain()
        server.shells.last().printed("Welcome back\r\n")
        assertTrue(terminal.emulator.screen.transcriptText.contains("Welcome back"))
    }
}
