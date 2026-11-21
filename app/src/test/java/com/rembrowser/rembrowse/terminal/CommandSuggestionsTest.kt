package com.rembrowser.rembrowse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSuggestionsTest {

    @Test
    fun theHistoryComesFirst_thenCommonCommands() {
        val history = listOf("git stash pop", "git status -sb")
        assertEquals("git stash pop", CommandSuggestions.suggest("git st", history))
        assertEquals("git status -sb", CommandSuggestions.suggest("git stat", history))
        assertEquals("git log --oneline -20", CommandSuggestions.suggest("git l", history))
        assertEquals("sudo apt update", CommandSuggestions.suggest("sudo apt u", emptyList()))
    }

    @Test
    fun onlyLongerCommands_thatStartWithExactlyWhatWasTyped() {
        assertNull("Nothing for blank input", CommandSuggestions.suggest("", listOf("ls")))
        assertNull(CommandSuggestions.suggest("   ", listOf("ls")))
        assertNull("Already complete", CommandSuggestions.suggest("htop", listOf("htop")))
        assertNull("Case matters", CommandSuggestions.suggest("Git st", emptyList()))
        assertNull("No match", CommandSuggestions.suggest("zzz", emptyList()))
    }

    @Test
    fun remembering_putsTheCommandFirst_once() {
        var history = emptyList<String>()
        history = CommandSuggestions.remember("ls -la", history)!!
        history = CommandSuggestions.remember("df -h  ", history)!!
        history = CommandSuggestions.remember("ls -la", history)!!
        assertEquals(listOf("ls -la", "df -h"), history)
    }

    @Test
    fun someCommandsAreNotRemembered() {
        assertNull("Blank", CommandSuggestions.remember("  ", emptyList()))
        assertNull("A leading space: the shell way to keep a command out", CommandSuggestions.remember(" mysql -p secret", emptyList()))
        assertNull("Control characters", CommandSuggestions.remember("ls\u001b[A", emptyList()))
        assertNull("Very long: a pasted script", CommandSuggestions.remember("x".repeat(301), emptyList()))
    }

    @Test
    fun theHistoryIsCapped() {
        var history = (1..CommandSuggestions.MAX_HISTORY).map { "echo $it" }
        history = CommandSuggestions.remember("echo new", history)!!
        assertEquals(CommandSuggestions.MAX_HISTORY, history.size)
        assertEquals("echo new", history.first())
        assertTrue("The oldest goes", "echo ${CommandSuggestions.MAX_HISTORY}" !in history)
    }

    @Test
    fun theCommonList_hasNoDuplicates() {
        assertEquals(CommandSuggestions.COMMON.size, CommandSuggestions.COMMON.toSet().size)
    }
}
