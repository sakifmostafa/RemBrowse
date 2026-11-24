package com.rembrowser.rembrowse.terminal

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed connection says. Android reports a refused connection, an unreachable network
 * and a connection the device blocked all as ConnectException, with the system's error code in
 * the message; these are its real messages.
 */
class ConnectFailureMessageTest {

    private fun message(hostname: String, port: Int, cause: Exception): String =
        TrileadSshConnector.connectFailureMessage(hostname, port, IOException("There was a problem while connecting to $hostname:$port", cause))

    @Test
    fun aRefusedConnection_onAnotherPort_pointsAtThePort() {
        val text = message(
            "100.101.102.103", 2222,
            ConnectException("failed to connect to /100.101.102.103 (port 2222) from /100.64.0.2 (port 41234) after 15000ms: isConnected failed: ECONNREFUSED (Connection refused)")
        )
        assertTrue(text, text.contains("refused the connection"))
        assertTrue(text, text.contains("port 2222"))
        assertTrue(text, text.contains("SSH usually uses port 22"))
    }

    @Test
    fun aRefusedConnection_onPort22_asksWhetherTheServerRuns() {
        val text = message("homelab.local", 22, ConnectException("Connection refused"))
        assertTrue(text, text.contains("refused the connection"))
        assertTrue(text, text.contains("Is the SSH server running?"))
        assertFalse(text, text.contains("usually uses port"))
    }

    @Test
    fun anUnreachableNetwork_isNotCalledRefused_andATailnetAddressGetsATailscaleHint() {
        val text = message(
            "100.101.102.103", 22,
            ConnectException("failed to connect to /100.101.102.103 (port 22) from /:: (port 0): connect failed: ENETUNREACH (Network is unreachable)")
        )
        assertFalse(text, text.contains("refused"))
        assertTrue(text, text.contains("no network route"))
        assertTrue(text, text.contains("Tailscale"))

        val lan = message("192.168.1.20", 22, NoRouteToHostException("Host unreachable"))
        assertTrue(lan, lan.contains("no network route"))
        assertFalse("Not a tailnet address", lan.contains("Tailscale"))
    }

    @Test
    fun aConnectionTheDeviceBlocked_saysSo() {
        val text = message(
            "100.101.102.103", 22,
            ConnectException("failed to connect to /100.101.102.103 (port 22) from /:: (port 0): connect failed: EACCES (Permission denied)")
        )
        assertTrue(text, text.contains("didn't let RemBrowse connect"))
    }

    @Test
    fun unknownHostsAndTimeouts() {
        assertTrue(message("nas.tail1234.ts.net", 22, UnknownHostException("nas.tail1234.ts.net")).contains("Tailscale"))
        assertTrue(message("example.com", 22, UnknownHostException("example.com")).contains("Couldn't find example.com"))
        val slow = message("100.101.102.103", 22, SocketTimeoutException("connect timed out"))
        assertTrue(slow, slow.contains("didn't answer in time") && slow.contains("Tailscale"))
    }

    @Test
    fun tailnetAddresses() {
        assertTrue(TrileadSshConnector.isTailscaleAddress("100.101.102.103"))
        assertTrue(TrileadSshConnector.isTailscaleAddress("100.64.0.1"))
        assertTrue(TrileadSshConnector.isTailscaleAddress("100.127.255.254"))
        assertTrue(TrileadSshConnector.isTailscaleAddress("ubuntu.tail1234.ts.net."))
        assertFalse(TrileadSshConnector.isTailscaleAddress("100.128.0.1"))
        assertFalse(TrileadSshConnector.isTailscaleAddress("100.63.255.255"))
        assertFalse(TrileadSshConnector.isTailscaleAddress("192.168.1.20"))
        assertFalse(TrileadSshConnector.isTailscaleAddress("homelab.local"))
    }
}
