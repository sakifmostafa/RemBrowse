package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery.Companion.RETRY_DELAYS_MS
import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery.Companion.STAYED_UP_MS
import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery.Companion.retryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the engine's extension process is turned back on after the engine gave up on it. */
class ExtensionProcessRecoveryTest {

    private var time = 1_000_000L
    private var turnedOn = 0
    private val scheduled = mutableListOf<Scheduled>()

    private class Scheduled(val at: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val recovery = ExtensionProcessRecovery(
        enableSpawning = { turnedOn++ },
        schedule = { delayMs, action ->
            val entry = Scheduled(time + delayMs, action)
            scheduled += entry
            ExtensionProcessRecovery.Cancellable { entry.cancelled = true }
        },
        now = { time }
    )

    /** Moves the clock on by [ms], running whatever falls due, as the main thread would. */
    private fun advance(ms: Long) {
        time += ms
        scheduled.filter { it.at <= time && !it.cancelled }.forEach {
            scheduled.remove(it)
            it.action()
        }
        scheduled.removeAll { it.at <= time }
    }

    private val pending get() = scheduled.count { !it.cancelled }

    /** Turned off while on screen: nothing happens until [delayMs] has passed, then it is turned on. */
    private fun assertTurnedOnAfter(delayMs: Long) {
        val before = turnedOn
        recovery.onSpawningDisabled()
        advance(delayMs - 1)
        assertEquals("turned on before $delayMs ms", before, turnedOn)
        advance(1)
        assertEquals("not turned on after $delayMs ms", before + 1, turnedOn)
    }

    @Test
    fun theWaitsGrowFrom5SecondsTo30Minutes() {
        assertEquals(listOf(5_000L, 60_000L, 300_000L, 900_000L, 1_800_000L), RETRY_DELAYS_MS)
        assertEquals(5_000L, retryDelayMs(0))
        assertEquals(1_800_000L, retryDelayMs(4))
        assertEquals(1_800_000L, retryDelayMs(40))
        assertEquals(5_000L, retryDelayMs(-1))
    }

    @Test
    fun inTheBackgroundItWaitsForAWindowThenTurnsItOnAtOnce() {
        recovery.onSpawningDisabled()
        advance(STAYED_UP_MS * 3)
        assertEquals(0, turnedOn)
        assertEquals(0, pending)
        assertTrue(recovery.isWaiting)

        recovery.onWindowShown()
        assertEquals(1, turnedOn)
        assertFalse(recovery.isWaiting)
    }

    @Test
    fun onScreenItTurnsItOnAfterAShortWait() {
        recovery.onWindowShown()
        assertTurnedOnAfter(5_000L)
        assertFalse(recovery.isWaiting)
    }

    @Test
    fun turnedOffAgainSoonAfterOnScreenItRetriesWithGrowingWaits() {
        // A window stays on screen the whole time, as on a laptop
        recovery.onWindowShown()
        assertTurnedOnAfter(5_000L)
        advance(30_000)
        assertTurnedOnAfter(60_000L)
        advance(30_000)
        assertTurnedOnAfter(5 * 60_000L)
        advance(30_000)
        assertTurnedOnAfter(15 * 60_000L)
        advance(30_000)
        assertTurnedOnAfter(30 * 60_000L)
        advance(30_000)
        assertTurnedOnAfter(30 * 60_000L)
        assertEquals(6, turnedOn)
    }

    @Test
    fun onceItStaysUpTheWaitsStartAgain() {
        recovery.onWindowShown()
        assertTurnedOnAfter(5_000L)
        advance(30_000)
        assertTurnedOnAfter(60_000L)
        advance(30_000)
        assertTurnedOnAfter(5 * 60_000L)

        // Up for ten minutes: recovered
        advance(STAYED_UP_MS)
        assertTurnedOnAfter(5_000L)
        advance(STAYED_UP_MS - 1)
        assertTurnedOnAfter(60_000L)
    }

    @Test
    fun turnedOnWhenAWindowCameBackCountsAsRecentToo() {
        recovery.onSpawningDisabled()
        recovery.onWindowShown()
        assertEquals(1, turnedOn)

        advance(30_000)
        assertTurnedOnAfter(60_000L)
    }

    @Test
    fun aWindowShownDuringALongWaitTurnsItOnAtOnce() {
        recovery.onWindowShown()
        assertTurnedOnAfter(5_000L)
        advance(30_000)
        assertTurnedOnAfter(60_000L)
        advance(30_000)
        recovery.onSpawningDisabled()
        assertEquals(1, pending)

        recovery.onWindowHidden()
        assertEquals(0, pending)
        recovery.onWindowShown()
        assertEquals(3, turnedOn)
        advance(30 * 60_000L)
        assertEquals(3, turnedOn)
    }

    @Test
    fun leavingTheScreenCancelsTheWaitAndComingBackTurnsItOnOnce() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        assertEquals(1, pending)

        recovery.onWindowHidden()
        assertEquals(0, pending)
        advance(60_000)
        assertEquals(0, turnedOn)

        recovery.onWindowShown()
        advance(60_000)
        assertEquals(1, turnedOn)
    }

    @Test
    fun anotherWindowStillOnScreenKeepsTheWait() {
        recovery.onWindowShown()
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        recovery.onWindowHidden()
        advance(5_000)
        assertEquals(1, turnedOn)
    }

    @Test
    fun showingWindowsAloneTurnsNothingOn() {
        recovery.onWindowShown()
        recovery.onWindowHidden()
        // An extra stop (an activity stopped before this was watching) must not count below zero
        recovery.onWindowHidden()
        recovery.onWindowShown()
        advance(STAYED_UP_MS)
        assertEquals(0, turnedOn)
        assertFalse(recovery.isWaiting)

        assertTurnedOnAfter(5_000L)
    }

    @Test
    fun aSecondReportWhileWaitingDoesNotTurnItOnTwice() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        recovery.onSpawningDisabled()
        advance(5_000)
        assertEquals(1, turnedOn)
        assertEquals(0, pending)
    }
}
