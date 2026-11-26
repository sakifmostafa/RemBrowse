package com.rembrowser.rembrowse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extensions closing and updating the browser's tabs (tabs.remove, tabs.update). */
class ExtensionTabControlTest {

    private class Session(val name: String)

    private val tabA = Session("tab-a")
    private val tabB = Session("tab-b")
    private val popup = Session("popup")

    private val tabSessions = mutableListOf(tabA, tabB)
    private val registered = mutableListOf<Pair<String, String>>()
    private val closed = mutableListOf<String>()
    private val selected = mutableListOf<String>()

    private val control = ExtensionTabControl<Session, String>(
        sessions = { tabSessions.toList() },
        register = { session, extension -> registered += session.name to extension },
        tabIdOf = { session -> if (session in tabSessions) session.name else null },
        closeTab = { closed += it },
        selectTab = { selected += it }
    )

    @Test
    fun everyExtensionGetsTheDelegateOnEveryTab() {
        control.onExtensionsListed(listOf("vpn@proton.ch", "uBlock0@raymondhill.net"))
        assertEquals(
            setOf(
                "tab-a" to "vpn@proton.ch", "tab-a" to "uBlock0@raymondhill.net",
                "tab-b" to "vpn@proton.ch", "tab-b" to "uBlock0@raymondhill.net"
            ),
            registered.toSet()
        )
        assertEquals(4, registered.size)
    }

    @Test
    fun aTabCreatedLaterGetsItForEveryExtension() {
        control.onExtensionsListed(listOf("vpn@proton.ch", "uBlock0@raymondhill.net"))
        registered.clear()
        val tabC = Session("tab-c").also { tabSessions += it }
        control.onSessionCreated(tabC)
        assertEquals(listOf("tab-c" to "vpn@proton.ch", "tab-c" to "uBlock0@raymondhill.net"), registered)
    }

    @Test
    fun anExtensionAddedLaterGetsItOnEveryTab() {
        control.onExtensionsListed(listOf("vpn@proton.ch"))
        registered.clear()
        control.onExtensionsListed(listOf("vpn@proton.ch", "new@example.com"))
        assertTrue("tab-a" to "new@example.com" in registered)
        assertTrue("tab-b" to "new@example.com" in registered)
    }

    @Test
    fun aTabCreatedBeforeAnyExtensionIsKnownGetsItOnceTheyAre() {
        control.onSessionCreated(tabA)
        assertTrue(registered.isEmpty())
        control.onExtensionsListed(listOf("vpn@proton.ch"))
        assertTrue("tab-a" to "vpn@proton.ch" in registered)
    }

    @Test
    fun removingABrowserTabClosesItAndIsAllowed() {
        assertTrue(control.close(tabB))
        assertEquals(listOf("tab-b"), closed)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun aSessionThatIsNotATabIsRefusedAndNothingCloses() {
        assertFalse(control.close(popup))
        assertFalse(control.update(popup, active = true))
        assertTrue(closed.isEmpty())
        assertTrue(selected.isEmpty())
    }

    @Test
    fun updatingWithActiveBringsTheTabToTheFront() {
        assertTrue(control.update(tabA, active = true))
        assertEquals(listOf("tab-a"), selected)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun updatingWithoutActiveIsAllowedButSelectsNothing() {
        // A new address alone is loaded by Gecko itself once allowed
        assertTrue(control.update(tabA, active = null))
        assertTrue(control.update(tabA, active = false))
        assertTrue(selected.isEmpty())
        assertTrue(closed.isEmpty())
    }

    @Test
    fun aTabAlreadyClosedIsRefused() {
        tabSessions.remove(tabB)
        assertFalse(control.close(tabB))
        assertTrue(closed.isEmpty())
    }
}
