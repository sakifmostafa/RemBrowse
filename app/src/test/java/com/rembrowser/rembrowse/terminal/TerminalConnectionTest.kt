package com.rembrowser.rembrowse.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.fakes.FakeSshConnector
import com.rembrowser.rembrowse.fakes.TestMainQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
