package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.data.InMemorySitePermissionStore
import com.rembrowser.rembrowse.engine.GeckoSitePermissions.KeepAllowStep
import com.rembrowser.rembrowse.model.SitePermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

/** Gecko's permission rows as the engine reads, cleans and rewrites them (review fixes 3, 6, 7). */
class GeckoPermissionRowsTest {

    private val page = "https://web.example.org"

    private fun row(type: String, value: Int, uri: String = "$page/", privateMode: Boolean = false) =
        GeckoPermissionRow(uri, principal = "{\"1\":{\"0\":\"$uri\"}}", type = type, value = value, privateMode = privateMode, contextId = "")

    // 3. Both storage access keys are read, under the name Gecko gave them

    @Test
    fun storageAccessKeysNameTheEmbeddedSiteOrOrigin() {
        assertEquals(
            SitePermissionKind.STORAGE_ACCESS to "https://embed.com",
            GeckoSitePermissions.kindOfType("3rdPartyFrameStorage^https://embed.com")
        )
        assertEquals(
            SitePermissionKind.STORAGE_ACCESS to "https://login.embed.com",
            GeckoSitePermissions.kindOfType("3rdPartyStorage^https://login.embed.com")
        )
        assertEquals(SitePermissionKind.LOCATION to null, GeckoSitePermissions.kindOfType("geolocation"))
        assertNull(GeckoSitePermissions.kindOfType("autoplay-media-audible"))
        assertNull(GeckoSitePermissions.kindOfType("3rdPartyStorage^not a web origin"))
        assertEquals("3rdPartyFrameStorage^https://embed.com", GeckoSitePermissions.frameStorageType("https://embed.com"))
    }

    @Test
    fun aDecisionKeyIsThePageTheKindAndTheEmbeddedSite() {
        assertEquals(
            "$page STORAGE_ACCESS https://login.embed.com",
            GeckoSitePermissions.decisionKeyOf(row("3rdPartyStorage^https://login.embed.com", ContentPermission.VALUE_ALLOW))
        )
        assertEquals("$page LOCATION", GeckoSitePermissions.decisionKeyOf(row("geolocation", ContentPermission.VALUE_DENY)))
        assertNull(GeckoSitePermissions.decisionKeyOf(row("persistent-storage", ContentPermission.VALUE_DENY)))
    }

    @Test
    fun localNetworkRowsAreDecisionsOfThePageItself() {
        assertEquals(SitePermissionKind.LOCAL_NETWORK to null, GeckoSitePermissions.kindOfType("local-network"))
        assertEquals(SitePermissionKind.LOCAL_DEVICE to null, GeckoSitePermissions.kindOfType("loopback-network"))
        assertEquals("$page LOCAL_NETWORK", GeckoSitePermissions.decisionKeyOf(row("local-network", ContentPermission.VALUE_ALLOW)))
        assertEquals("$page LOCAL_DEVICE", GeckoSitePermissions.decisionKeyOf(row("loopback-network", ContentPermission.VALUE_DENY)))
    }

    // 6. The start-up clean-up removes leftovers and stale refusals, never the user's Blocks

    @Test
    fun theCleanUpTakesBackLeftoverGrantsAndStaleRefusalsOnly() {
        val leftover = row("geolocation", ContentPermission.VALUE_ALLOW)
        val lasting = row("geolocation", ContentPermission.VALUE_ALLOW, uri = "https://maps.example/")
        val stale = row("desktop-notification", ContentPermission.VALUE_DENY)
        val usersBlock = row("geolocation", ContentPermission.VALUE_DENY, uri = "https://blocked.example/")
        val autoplay = row("autoplay-media-audible", ContentPermission.VALUE_DENY)
        val inPrivate = row("desktop-notification", ContentPermission.VALUE_DENY, privateMode = true)

        val cleaned = GeckoSitePermissions.rowsToClean(
            rows = listOf(leftover, lasting, stale, usersBlock, autoplay, inPrivate),
            leftoverGrants = setOf("$page LOCATION"),
            userDenials = setOf("https://blocked.example LOCATION"),
            clearStaleDenials = true
        )

        assertEquals(listOf(leftover, stale), cleaned)
    }

    @Test
    fun theCleanUpTakesBackALeftoverLocalNetworkGrantButNeverALocalNetworkBlock() {
        val leftover = row("local-network", ContentPermission.VALUE_ALLOW)
        val lasting = row("loopback-network", ContentPermission.VALUE_ALLOW)
        // Local network refusals can only be the user's: the type is newer than the stale refusals
        val block = row("local-network", ContentPermission.VALUE_DENY, uri = "https://blocked.example/")

        val cleaned = GeckoSitePermissions.rowsToClean(
            rows = listOf(leftover, lasting, block),
            leftoverGrants = setOf("$page LOCAL_NETWORK"),
            userDenials = emptySet(),
            clearStaleDenials = true
        )

        assertEquals(listOf(leftover), cleaned)
    }

    @Test
    fun onceStaleRefusalsAreClearedEveryRefusalStays() {
        val cleaned = GeckoSitePermissions.rowsToClean(
            rows = listOf(row("desktop-notification", ContentPermission.VALUE_DENY)),
            leftoverGrants = emptySet(),
            userDenials = emptySet(),
            clearStaleDenials = false
        )
        assertEquals(emptyList<GeckoPermissionRow>(), cleaned)
    }

    @Test
    fun theStoreKeepsTheUsersBlocksUntilTheCleanUpRuns() {
        val store = InMemorySitePermissionStore()
        store.userDenials = setOf("$page LOCATION")
        assertEquals(setOf("$page LOCATION"), store.userDenials)
    }

    // 7. A refusal caused only by Android's permission does not cost the site its Allow

    @Test
    fun theAllowGoesBackOnlyOverGeckosPrompt() {
        assertEquals(KeepAllowStep.RESTORE, GeckoSitePermissions.keepAllowStep(false, ContentPermission.VALUE_PROMPT, 0))
        assertEquals(KeepAllowStep.LOOK_AGAIN, GeckoSitePermissions.keepAllowStep(false, ContentPermission.VALUE_ALLOW, 0))
        assertEquals(KeepAllowStep.LOOK_AGAIN, GeckoSitePermissions.keepAllowStep(true, null, 3))
        // Blocked or removed since: that decision stands
        assertEquals(KeepAllowStep.STOP, GeckoSitePermissions.keepAllowStep(false, ContentPermission.VALUE_DENY, 0))
        assertEquals(KeepAllowStep.STOP, GeckoSitePermissions.keepAllowStep(false, null, 0))
        // Not for ever
        assertEquals(KeepAllowStep.STOP, GeckoSitePermissions.keepAllowStep(false, ContentPermission.VALUE_ALLOW, 20))
    }
}
