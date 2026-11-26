package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.InMemoryAddonUpdatePreferences
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AddonUpdateItem
import com.rembrowser.rembrowse.model.AddonUpdateOutcome
import com.rembrowser.rembrowse.model.AddonUpdateResult
import com.rembrowser.rembrowse.model.InstalledExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-on update check: when it runs, what it checks, declined updates, failures and what it
 * says. Its scope is the test's background scope, which the test ends, as the app's window ends
 * the real one; the daily schedule never finishes on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonUpdaterTest {

    private val manager = FakeExtensionManager().apply {
        installedExtensions.value = listOf(
            addon("ublock@test", "uBlock Origin"),
            addon("vpn@test", "Proton VPN", enabled = false),
            addon("reader@test", "Dark Reader")
        )
        builtInAddonIds = listOf("extension-popups@rembrowse", "page-menu@rembrowse")
    }
    private val preferences = InMemoryAddonUpdatePreferences()

    private fun addon(id: String, name: String, enabled: Boolean = true) =
        InstalledExtension(id = id, name = name, version = "1.0", description = "", enabled = enabled)

    /** Virtual time as the wall clock, from a fixed day. */
    private fun TestScope.now() = BASE_MILLIS + testScheduler.currentTime

    private fun TestScope.updater(online: () -> Boolean = { true }) = AddonUpdater(
        extensionManager = manager,
        preferences = preferences,
        scope = backgroundScope,
        isOnline = online,
        clock = { now() },
        startDelayMillis = START_DELAY
    )

    private val checkedIds get() = manager.addonUpdateCalls.map { it.first }

    @Test
    fun theFirstCheckWaitsForTheStartDelay_thenChecksEveryAddonOnOrOff_butNoBuiltIn() = runTest {
        updater().start()
        advanceTimeBy(START_DELAY - 1)
        assertTrue("Nothing checked during startup", manager.addonUpdateCalls.isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf("ublock@test", "vpn@test", "reader@test"), checkedIds)
        assertEquals(BASE_MILLIS + START_DELAY, preferences.lastCheckedMillis)
    }

    @Test
    fun aRecentCheckHoldsOffUntilADayHasPassed_thenItChecksDailyWhileRunning() = runTest {
        preferences.lastCheckedMillis = now() - 23 * HOUR
        updater().start()
        advanceTimeBy(START_DELAY + 1)
        assertTrue("Checked 23 hours ago: not due", manager.addonUpdateCalls.isEmpty())

        advanceTimeBy(HOUR)
        assertEquals("Due once 24 hours have passed", 3, manager.addonUpdateCalls.size)

        advanceTimeBy(23 * HOUR)
        assertEquals(3, manager.addonUpdateCalls.size)
        advanceTimeBy(HOUR + 1)
        assertEquals("And again a day later, still running", 6, manager.addonUpdateCalls.size)
    }

    @Test
    fun theSwitchOff_stopsAutomaticChecks_butCheckForUpdatesStillChecks() = runTest {
        preferences.updateAutomatically = false
        val updater = updater()
        updater.start()
        advanceTimeBy(3 * 24 * HOUR)
        assertTrue(manager.addonUpdateCalls.isEmpty())

        updater.checkNow()
        runCurrent()
        assertEquals(3, manager.addonUpdateCalls.size)
    }

    @Test
    fun turningTheSwitchBackOn_checksAtOnceWhenADayHasPassed() = runTest {
        preferences.updateAutomatically = false
        preferences.lastCheckedMillis = now() - 2 * 24 * HOUR
        val updater = updater()
        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertTrue(manager.addonUpdateCalls.isEmpty())

        updater.setUpdateAutomatically(true)
        runCurrent()
        assertTrue(preferences.updateAutomatically)
        assertTrue(updater.uiState.value.updateAutomatically)
        assertEquals(3, manager.addonUpdateCalls.size)
    }

    @Test
    fun aCheckIsDue_afterADay_beforeTheFirstOne_orWhenTheClockWentBack() = runTest {
        val updater = updater()
        assertTrue("Never checked", updater.isCheckDue())
        preferences.lastCheckedMillis = now() - 24 * HOUR + 1
        assertFalse(updater.isCheckDue())
        preferences.lastCheckedMillis = now() - 24 * HOUR
        assertTrue(updater.isCheckDue())
        preferences.lastCheckedMillis = now() + HOUR
        assertTrue("A clock set back doesn't hold checks off", updater.isCheckDue())
        preferences.updateAutomatically = false
        assertFalse(updater.isCheckDue())
    }

    @Test
    fun theScheduleLooksAgainWhenTheNextCheckIsDue_atLeastEveryHour() = runTest {
        val updater = updater()
        preferences.lastCheckedMillis = now() - 24 * HOUR + 10 * 60_000L
        assertEquals(10 * 60_000L, updater.untilNextLook())
        preferences.lastCheckedMillis = now() - 24 * HOUR + 1_000L
        assertEquals("At least a minute", 60_000L, updater.untilNextLook())
        preferences.lastCheckedMillis = now()
        assertEquals(HOUR, updater.untilNextLook())
        preferences.lastCheckedMillis = now() - 25 * HOUR
        assertEquals("Still due after its look (offline): an hour", HOUR, updater.untilNextLook())
        preferences.updateAutomatically = false
        preferences.lastCheckedMillis = now() - 24 * HOUR + 10 * 60_000L
        assertEquals("Switched off: looks hourly for the switch", HOUR, updater.untilNextLook())
    }

    @Test
    fun aDeclinedUpdate_isDeclinedAgainByAutomaticChecks_butCheckForUpdatesAsksAgain() = runTest {
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Declined("2.0")
        val updater = updater()
        updater.checkNow()
        runCurrent()
        assertEquals(mapOf("vpn@test" to "2.0"), preferences.declinedUpdates)

        // The next automatic check, a day later
        manager.addonUpdateCalls.clear()
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Declined("2.0", asked = false)
        updater.start()
        advanceTimeBy(24 * HOUR + START_DELAY)
        assertEquals(
            listOf("ublock@test" to null, "vpn@test" to "2.0", "reader@test" to null),
            manager.addonUpdateCalls
        )
        assertEquals(mapOf("vpn@test" to "2.0"), preferences.declinedUpdates)

        manager.addonUpdateCalls.clear()
        updater.checkNow()
        runCurrent()
        assertEquals("Asked for: no version is declined unasked", null, manager.addonUpdateCalls.single { it.first == "vpn@test" }.second)
    }

    @Test
    fun aNewerVersionDeclined_replacesTheOneRemembered() = runTest {
        preferences.declinedUpdates = mapOf("vpn@test" to "2.0")
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Declined("2.1")
        updater().checkNow()
        runCurrent()
        assertEquals(mapOf("vpn@test" to "2.1"), preferences.declinedUpdates)
    }

    @Test
    fun anUpdate_forgetsTheDecline_andRefreshesTheInstalledList() = runTest {
        preferences.declinedUpdates = mapOf("vpn@test" to "2.0", "reader@test" to "5.0")
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Updated("2.0")
        val refreshesBefore = manager.refreshCount
        updater().checkNow()
        runCurrent()

        assertEquals(mapOf("reader@test" to "5.0"), preferences.declinedUpdates)
        assertEquals("One refresh, for the one update", refreshesBefore + 1, manager.refreshCount)
        assertEquals("2.0", manager.installedExtensions.value.single { it.id == "vpn@test" }.version)
    }

    @Test
    fun declinesOfRemovedAddonsAreForgotten() {
        val items = listOf(
            AddonUpdateItem("a", "A", AddonUpdateResult.Declined("3")),
            AddonUpdateItem("b", "B", AddonUpdateResult.Updated("2")),
            AddonUpdateItem("c", "C", AddonUpdateResult.Declined(version = null)),
            AddonUpdateItem("d", "D", AddonUpdateResult.Failed(network = true))
        )
        val before = mapOf("b" to "2", "d" to "7", "gone" to "1")
        assertEquals(mapOf("a" to "3", "d" to "7"), declinesAfter(before, items, setOf("a", "b", "c", "d")))
    }

    @Test
    fun oneAddonFailing_doesNotStopTheOthers() = runTest {
        manager.addonUpdateThrows["ublock@test"] = IllegalStateException("engine gone")
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Failed(network = true)
        manager.addonUpdateResults["reader@test"] = AddonUpdateResult.Updated("5.1")
        val updater = updater()
        updater.checkNow()
        runCurrent()

        val outcome = updater.uiState.value.outcome as AddonUpdateOutcome.Checked
        assertEquals(
            listOf(AddonUpdateResult.Failed(), AddonUpdateResult.Failed(network = true), AddonUpdateResult.Updated("5.1")),
            outcome.items.map { it.result }
        )
        assertTrue(outcome.isError)
        assertEquals(
            "Updated Dark Reader. Couldn't update uBlock Origin and Proton VPN. Check your connection and try again.",
            outcome.summary
        )
        assertEquals("Counted as a check: failures wait for the next one", now(), preferences.lastCheckedMillis)
    }

    @Test
    fun anAddonWaitingOnItsReview_holdsUpNoOtherAddon() = runTest {
        val review = CompletableDeferred<Unit>()
        manager.addonUpdateGateFor["vpn@test"] = review
        manager.addonUpdateResults["vpn@test"] = AddonUpdateResult.Updated("2.0")
        manager.addonUpdateResults["ublock@test"] = AddonUpdateResult.Updated("1.62")
        val updater = updater()
        updater.checkNow()
        runCurrent()

        assertEquals("All asked at once", 3, manager.addonUpdateCalls.size)
        assertEquals("Installed and shown while the review is open", "1.62", manager.installedExtensions.value.first().version)
        assertTrue("The check itself waits for the review", updater.uiState.value.checking)

        review.complete(Unit)
        runCurrent()
        assertFalse(updater.uiState.value.checking)
        assertEquals("Updated uBlock Origin and Proton VPN.", updater.uiState.value.outcome?.summary)
    }

    @Test
    fun aFailureOutsideAnyOneAddon_isReported_andTheScheduleCarriesOn() = runTest {
        var connectivityBroken = true
        val updater = updater(online = { if (connectivityBroken) throw SecurityException("platform bug") else true })
        updater.checkNow()
        runCurrent()
        assertEquals(AddonUpdateOutcome.Unavailable, updater.uiState.value.outcome)
        assertFalse(updater.uiState.value.checking)

        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertTrue(manager.addonUpdateCalls.isEmpty())
        connectivityBroken = false
        advanceTimeBy(HOUR)
        assertEquals("Tried again at the next look", 3, manager.addonUpdateCalls.size)
    }

    @Test
    fun aLaterAutomaticCheck_replacesWhatTheLastRequestedOneSaid() = runTest {
        var online = false
        val updater = updater(online = { online })
        updater.checkNow()
        runCurrent()
        assertEquals(AddonUpdateOutcome.Offline, updater.uiState.value.outcome)

        online = true
        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertNull("Back to when add-ons were last checked", updater.uiState.value.outcome)
        assertEquals(BASE_MILLIS + START_DELAY, updater.uiState.value.lastCheckedMillis)
    }

    @Test
    fun offline_checkForUpdatesSaysSo_andTheCheckIsNotCounted() = runTest {
        var online = false
        val updater = updater(online = { online })
        updater.checkNow()
        runCurrent()

        assertEquals(AddonUpdateOutcome.Offline, updater.uiState.value.outcome)
        assertEquals(
            "Couldn't check for updates. Check your connection and try again.",
            updater.uiState.value.outcome?.summary
        )
        assertTrue(manager.addonUpdateCalls.isEmpty())
        assertNull(preferences.lastCheckedMillis)

        // The schedule tries again at its next look, within the hour, once there is a connection
        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertTrue(manager.addonUpdateCalls.isEmpty())
        online = true
        advanceTimeBy(HOUR)
        assertEquals(3, manager.addonUpdateCalls.size)
    }

    @Test
    fun anAddonListThatCantBeRead_isReported() = runTest {
        manager.addonTargetsResult = Result.failure(IllegalStateException("no runtime"))
        val updater = updater()
        updater.checkNow()
        runCurrent()
        assertEquals(AddonUpdateOutcome.Unavailable, updater.uiState.value.outcome)
        assertEquals("Couldn't check for updates. Try again later.", updater.uiState.value.outcome?.summary)
        assertNull(preferences.lastCheckedMillis)
    }

    @Test
    fun automaticChecksSayNothing_butShowWhenTheyRan() = runTest {
        manager.addonUpdateResults["ublock@test"] = AddonUpdateResult.Updated("1.62")
        val updater = updater()
        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertEquals(1, manager.addonUpdateCalls.count { it.first == "ublock@test" })
        assertNull(updater.uiState.value.outcome)
        assertEquals(BASE_MILLIS + START_DELAY, updater.uiState.value.lastCheckedMillis)
    }

    @Test
    fun aCheckAskedForDuringAnAutomaticOne_waitsForIt_thenRunsItsOwn() = runTest {
        val gate = CompletableDeferred<Unit>()
        manager.addonUpdateGate = gate
        val updater = updater()
        updater.start()
        advanceTimeBy(START_DELAY + 1)
        assertEquals(3, manager.addonUpdateCalls.size)

        updater.checkNow()
        runCurrent()
        assertEquals("Waits for the automatic check", 3, manager.addonUpdateCalls.size)
        assertTrue(updater.uiState.value.checking)

        gate.complete(Unit)
        runCurrent()
        assertEquals(6, manager.addonUpdateCalls.size)
        assertFalse(updater.uiState.value.checking)
        assertEquals("All add-ons are up to date.", updater.uiState.value.outcome?.summary)
    }

    private companion object {
        const val BASE_MILLIS = 1_795_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val START_DELAY = 30_000L
    }
}
