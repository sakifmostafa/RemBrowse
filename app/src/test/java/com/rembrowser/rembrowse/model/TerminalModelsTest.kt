package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModelsTest {

    @Test
    fun terminalAddresses_roundTrip() {
        val targets = listOf(
            TerminalTarget.Picker,
            TerminalTarget.SavedHost("a1b2c3"),
            TerminalTarget.Address("sakif", "home.example"),
            TerminalTarget.Address("root", "10.0.0.2", 2222),
            TerminalTarget.Address("me", "::1", 22)
        )
        targets.forEach { target -> assertEquals(target, TerminalUrls.parse(TerminalUrls.urlFor(target))) }
        assertEquals("rem://terminal/ssh/root@10.0.0.2:2222", TerminalUrls.urlFor(TerminalTarget.Address("root", "10.0.0.2", 2222)))
        assertEquals("rem://terminal/ssh/me@[::1]", TerminalUrls.urlFor(TerminalTarget.Address("me", "::1", 22)))
    }

    @Test
    fun onlyTerminalAddressesAreTerminals() {
        assertTrue(TerminalUrls.isTerminalUrl("rem://terminal"))
        assertTrue(TerminalUrls.isTerminalUrl("REM://Terminal/host/x"))
        assertFalse(TerminalUrls.isTerminalUrl("rem://terminals"))
        assertFalse(TerminalUrls.isTerminalUrl("rem://settings/terminal"))
        assertNull(TerminalUrls.parse("https://example.com"))
        assertEquals("A malformed one is a new terminal", TerminalTarget.Picker, TerminalUrls.parse("rem://terminal/ssh/nonsense"))
        assertTrue(BrowserTab(id = "t", url = "rem://terminal/host/x").isTerminal)
        assertFalse(BrowserTab(id = "t", url = "rem://settings").isTerminal)
        assertEquals("Terminal", BrowserTab(id = "t", url = "rem://terminal").displayTitle)
        assertEquals("sakif@home: ~", BrowserTab(id = "t", url = "rem://terminal", title = "sakif@home: ~").displayTitle)
    }

    @Test
    fun typedAddresses_asSshTakesThem() {
        assertEquals(TerminalTarget.Address("sakif", "home.example", 22), TerminalUrls.parseAddress("sakif@home.example"))
        assertEquals(TerminalTarget.Address("sakif", "home.example", 2222), TerminalUrls.parseAddress(" sakif@home.example:2222 "))
        assertEquals(TerminalTarget.Address("pi", "192.168.1.5", 2200), TerminalUrls.parseAddress("ssh pi@192.168.1.5 -p 2200"))
        assertEquals(TerminalTarget.Address("pi", "192.168.1.5", 2200), TerminalUrls.parseAddress("ssh -p 2200 pi@192.168.1.5"))
        assertEquals(TerminalTarget.Address("me", "fe80::1", 22), TerminalUrls.parseAddress("me@[fe80::1]"))
        assertEquals(TerminalTarget.Address("me", "fe80::1", 2022), TerminalUrls.parseAddress("me@[fe80::1]:2022"))
        assertEquals("The last @ separates user from host", TerminalTarget.Address("a@b", "c", 22), TerminalUrls.parseAddress("a@b@c"))

        listOf("home.example", "@home", "sakif@", "sakif@home:99999", "sakif@home:x", "ssh sakif@home extra", "sa kif@home", "me@[::1")
            .forEach { assertNull(it, TerminalUrls.parseAddress(it)) }
    }

    @Test
    fun hostsAreNamedAndAddressed() {
        val host = SshHost(id = "1", label = "", hostname = "home.example", port = 2222, username = "sakif")
        assertEquals("sakif@home.example:2222", host.address)
        assertEquals("Without a name, the address", host.address, host.displayName)
        assertEquals("Home", host.copy(label = "Home").displayName)
        assertEquals("sakif@home.example", host.copy(port = 22).address)
        assertEquals(SshAuthMethod.PASSWORD, SshAuthMethod.fromNameOrDefault("nonsense"))
        assertEquals("ssh-ed25519", SshKeyInfo("k", "n", "ssh-ed25519 AAAA n", "SHA256:x", 0).type)
    }

    @Test
    fun theTerminalHasASettingsSection() {
        assertEquals(SettingsSection.TERMINAL, SettingsSection.fromUrl("rem://settings/terminal"))
    }
}
