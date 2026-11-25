package com.rembrowser.rembrowse.engine

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoResult
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the system's Back does with the engine's answer to "does the page take it?" */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GeckoBackOfferTest {

    private var handled = 0
    private var unhandled = 0
    private var pageReached = 0

    /** What the page answers once it has had Back: by default at once. */
    private var reachPage: () -> GeckoResult<*>? = { GeckoResult.fromValue(false) }

    private fun offer(processBackPressed: () -> GeckoResult<Boolean>?) {
        offerBackToPage(
            processBackPressed,
            reachPage = { pageReached++; reachPage() },
            onHandled = { handled++ },
            onUnhandled = { unhandled++ }
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun thePageTookIt_nothingElseHappens() {
        offer { GeckoResult.fromValue(true) }
        assertEquals(1, handled)
        assertEquals(0, unhandled)
    }

    @Test
    fun thePageHadNothingToClose_backGoesOn() {
        offer { GeckoResult.fromValue(false) }
        assertEquals(0, handled)
        assertEquals(1, unhandled)
        assertEquals("Nothing to wait for", 0, pageReached)
    }

    @Test
    fun aTakenBack_isReportedOnlyOnceThePageHasCaughtUp() {
        // Until then the engine would answer "taken" again for a dialog already closing
        val reached = GeckoResult<Boolean>()
        reachPage = { reached }
        offer { GeckoResult.fromValue(true) }
        assertEquals(1, pageReached)
        assertEquals(0, handled + unhandled)

        reached.complete(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, handled)
        assertEquals(0, unhandled)
    }

    @Test
    fun aTakenBack_isStillReported_whenThePageCantBeReached() {
        reachPage = { GeckoResult.fromException<Boolean>(IllegalStateException("Gone")) }
        offer { GeckoResult.fromValue(true) }
        reachPage = { throw IllegalStateException("Closed") }
        offer { GeckoResult.fromValue(true) }
        reachPage = { null }
        offer { GeckoResult.fromValue(true) }
        assertEquals(3, handled)
        assertEquals(0, unhandled)
    }

    @Test
    fun noAnswer_counts_asNothingToClose() {
        offer { GeckoResult.fromValue(null) }
        assertEquals(0, handled)
        assertEquals(1, unhandled)
    }

    @Test
    fun anEngineError_backGoesOn() {
        offer { GeckoResult.fromException(IllegalStateException("The session went away")) }
        assertEquals(0, handled)
        assertEquals(1, unhandled)
    }

    @Test
    fun theEngineRefusingToAsk_backGoesOn() {
        offer { throw IllegalStateException("Not open") }
        assertEquals(1, unhandled)
        offer { null }
        assertEquals(2, unhandled)
        assertEquals(0, handled)
    }

    @Test
    fun theAnswerComesLater_andOnlyThenDoesAnythingHappen() {
        val answer = GeckoResult<Boolean>()
        offer { answer }
        assertEquals(0, handled + unhandled)

        answer.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, handled)
        assertEquals(0, unhandled)
    }
}
