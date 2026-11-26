package com.rembrowser.rembrowse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "Check for updates" says once the add-ons are checked. */
class AddonUpdateOutcomeTest {

    private fun checked(vararg results: Pair<String, AddonUpdateResult>) =
        AddonUpdateOutcome.Checked(results.map { (name, result) -> AddonUpdateItem("$name@test", name, result) })

    @Test
    fun nothingNewer_isUpToDate() {
        val outcome = checked("uBlock Origin" to AddonUpdateResult.UpToDate, "Proton VPN" to AddonUpdateResult.UpToDate)
        assertEquals("All add-ons are up to date.", outcome.summary)
        assertFalse(outcome.isError)
    }

    @Test
    fun noAddonsAtAll() {
        assertEquals("No add-ons to update.", checked().summary)
    }

    @Test
    fun updatedAddonsAreNamed() {
        assertEquals("Updated uBlock Origin.", checked("uBlock Origin" to AddonUpdateResult.Updated("1.62")).summary)
        assertEquals(
            "Updated uBlock Origin and Proton VPN.",
            checked(
                "uBlock Origin" to AddonUpdateResult.Updated("1.62"),
                "Bitwarden" to AddonUpdateResult.UpToDate,
                "Proton VPN" to AddonUpdateResult.Updated("2.0")
            ).summary
        )
        assertEquals(
            "Updated uBlock Origin, Proton VPN and Dark Reader.",
            checked(
                "uBlock Origin" to AddonUpdateResult.Updated("1.62"),
                "Proton VPN" to AddonUpdateResult.Updated("2.0"),
                "Dark Reader" to AddonUpdateResult.Updated("5.0")
            ).summary
        )
    }

    @Test
    fun declinedPermissions_postponedUpdates_andFailures_eachGetASentence() {
        val outcome = checked(
            "uBlock Origin" to AddonUpdateResult.Updated("1.62"),
            "Proton VPN" to AddonUpdateResult.Declined("2.0"),
            "Dark Reader" to AddonUpdateResult.Postponed,
            "Bitwarden" to AddonUpdateResult.Failed()
        )
        assertEquals(
            "Updated uBlock Origin. Dark Reader will finish updating the next time RemBrowse starts. " +
                "Proton VPN wasn't updated, because you didn't allow its new permissions. " +
                "Couldn't update Bitwarden. Try again later.",
            outcome.summary
        )
        assertTrue(outcome.isError)
    }

    @Test
    fun severalDeclinedOrPostponed_readInThePlural() {
        val outcome = checked(
            "Proton VPN" to AddonUpdateResult.Declined("2.0"),
            "Bitwarden" to AddonUpdateResult.Declined(null),
            "Dark Reader" to AddonUpdateResult.Postponed,
            "uBlock Origin" to AddonUpdateResult.Postponed
        )
        assertEquals(
            "Dark Reader and uBlock Origin will finish updating the next time RemBrowse starts. " +
                "Proton VPN and Bitwarden weren't updated, because you didn't allow their new permissions.",
            outcome.summary
        )
        assertFalse("Declining is a choice, not an error", outcome.isError)
    }

    @Test
    fun aFailedDownload_pointsAtTheConnection() {
        val outcome = checked(
            "Proton VPN" to AddonUpdateResult.Failed(network = true),
            "Bitwarden" to AddonUpdateResult.Failed()
        )
        assertEquals("Couldn't update Proton VPN and Bitwarden. Check your connection and try again.", outcome.summary)
    }

    @Test
    fun aCheckThatCouldNotRun_isAnError() {
        assertTrue(AddonUpdateOutcome.Offline.isError)
        assertTrue(AddonUpdateOutcome.Unavailable.isError)
        assertEquals("Couldn't check for updates. Check your connection and try again.", AddonUpdateOutcome.Offline.summary)
        assertEquals("Couldn't check for updates. Try again later.", AddonUpdateOutcome.Unavailable.summary)
    }
}
