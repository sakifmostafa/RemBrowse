package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.AddonUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebExtension.InstallException.ErrorCodes

/** How GeckoView's answers to an add-on update are read, and when its review is skipped. */
class GeckoAddonUpdatesTest {

    @Test
    fun theReviewIsSkippedOnlyForTheVersionAlreadyDeclined() {
        val underway = AddonUpdateUnderway(declinedVersion = "2.0")
        assertFalse(underway.declineUnasked("2.1"))
        assertEquals("2.1", underway.offeredVersion)
        assertTrue(underway.declineUnasked("2.0"))
        assertTrue(underway.declinedUnasked)
    }

    @Test
    fun withNothingDeclined_everyReviewIsShown() {
        val underway = AddonUpdateUnderway(declinedVersion = null)
        assertFalse(underway.declineUnasked("2.0"))
        assertFalse(underway.declineUnasked(null))
        assertNull(underway.offeredVersion)
    }

    @Test
    fun aRefusedReview_isADeclineOfTheVersionOffered() {
        val asked = AddonUpdateUnderway(declinedVersion = null).apply { declineUnasked("2.0") }
        assertEquals(AddonUpdateResult.Declined("2.0", asked = true), addonUpdateFailure(ErrorCodes.ERROR_USER_CANCELED, asked))

        val unasked = AddonUpdateUnderway(declinedVersion = "2.0").apply { declineUnasked("2.0") }
        assertEquals(AddonUpdateResult.Declined("2.0", asked = false), addonUpdateFailure(ErrorCodes.ERROR_USER_CANCELED, unasked))

        // Cancelled without a review seen: nothing to remember
        assertEquals(
            AddonUpdateResult.Declined(null, asked = true),
            addonUpdateFailure(ErrorCodes.ERROR_USER_CANCELED, AddonUpdateUnderway(null))
        )
    }

    @Test
    fun otherErrors() {
        val underway = AddonUpdateUnderway(null)
        assertEquals(AddonUpdateResult.Postponed, addonUpdateFailure(ErrorCodes.ERROR_POSTPONED, underway))
        assertEquals(AddonUpdateResult.Failed(network = true), addonUpdateFailure(ErrorCodes.ERROR_NETWORK_FAILURE, underway))
        assertEquals(AddonUpdateResult.Failed(), addonUpdateFailure(ErrorCodes.ERROR_CORRUPT_FILE, underway))
        assertEquals(AddonUpdateResult.Failed(), addonUpdateFailure(ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED, underway))
        assertEquals("Not an install error", AddonUpdateResult.Failed(), addonUpdateFailure(null, underway))
    }
}
