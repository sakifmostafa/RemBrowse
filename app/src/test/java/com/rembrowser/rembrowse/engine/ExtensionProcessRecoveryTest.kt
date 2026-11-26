package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery.Companion.RETRY_DELAY_MS
import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery.Companion.RETRY_WINDOW_MS
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

    @Test
    fun inTheBackgroundItWaitsForAWindowThenTurnsItOnAtOnce() {
        recovery.onSpawningDisabled()
        advance(RETRY_WINDOW_MS * 3)
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
        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS - 1)
        assertEquals(0, turnedOn)
        advance(1)
        assertEquals(1, turnedOn)
        assertFalse(recovery.isWaiting)
    }

    @Test
    fun turnedOffAgainSoonAfterItStaysOffUntilAWindowIsNextShown() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS)
        assertEquals(1, turnedOn)

        // It keeps failing while the user is still here
        advance(60_000)
        recovery.onSpawningDisabled()
        advance(RETRY_WINDOW_MS * 2)
        assertEquals(1, turnedOn)
        assertTrue(recovery.isWaiting)

        // The user leaves and comes back
        recovery.onWindowHidden()
        recovery.onWindowShown()
        assertEquals(2, turnedOn)
    }

    @Test
    fun turnedOffAgainLongAfterItTriesAgain() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS)
        assertEquals(1, turnedOn)

        advance(RETRY_WINDOW_MS)
        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS)
        assertEquals(2, turnedOn)
    }

    @Test
    fun turnedOnWhenAWindowCameBackCountsAsRecentToo() {
        recovery.onSpawningDisabled()
        recovery.onWindowShown()
        assertEquals(1, turnedOn)

        advance(30_000)
        recovery.onSpawningDisabled()
        advance(RETRY_WINDOW_MS)
        assertEquals(1, turnedOn)
    }

    @Test
    fun leavingTheScreenCancelsTheWaitAndComingBackTurnsItOnOnce() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        assertEquals(1, pending)

        recovery.onWindowHidden()
        assertEquals(0, pending)
        advance(RETRY_DELAY_MS * 2)
        assertEquals(0, turnedOn)

        recovery.onWindowShown()
        advance(RETRY_DELAY_MS * 2)
        assertEquals(1, turnedOn)
    }

    @Test
    fun anotherWindowStillOnScreenKeepsTheWait() {
        recovery.onWindowShown()
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        recovery.onWindowHidden()
        advance(RETRY_DELAY_MS)
        assertEquals(1, turnedOn)
    }

    @Test
    fun showingWindowsAloneTurnsNothingOn() {
        recovery.onWindowShown()
        recovery.onWindowHidden()
        // An extra stop (an activity stopped before this was watching) must not count below zero
        recovery.onWindowHidden()
        recovery.onWindowShown()
        advance(RETRY_WINDOW_MS)
        assertEquals(0, turnedOn)
        assertFalse(recovery.isWaiting)

        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS)
        assertEquals(1, turnedOn)
    }

    @Test
    fun aSecondReportWhileWaitingDoesNotTurnItOnTwice() {
        recovery.onWindowShown()
        recovery.onSpawningDisabled()
        recovery.onSpawningDisabled()
        advance(RETRY_DELAY_MS)
        assertEquals(1, turnedOn)
        assertEquals(0, pending)
    }
}
