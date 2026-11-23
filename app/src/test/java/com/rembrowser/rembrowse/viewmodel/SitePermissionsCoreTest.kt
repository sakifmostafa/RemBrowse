package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.engine.GeckoSitePermissions
import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import com.rembrowser.rembrowse.model.SitePermissionKind.NOTIFICATIONS
import com.rembrowser.rembrowse.model.SitePermissionKind.PROTECTED_CONTENT
import com.rembrowser.rembrowse.model.SitePermissionKind.STORAGE_ACCESS
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Checks of the permission core's main paths; the full suite lives elsewhere. */
class SitePermissionsCoreTest {

    private lateinit var engine: FakeBrowserEngine
    private lateinit var viewModel: BrowserViewModel
    private var idCounter = 0

    @Before
    fun setUp() {
        engine = FakeBrowserEngine()
        viewModel = BrowserViewModel(engine, FakeBrowserStateRepository(), idGenerator = { "tab-${++idCounter}" })
        engine.sessions.getValue("tab-1").simulatePageLoad("https://web.example.org/chat", "Chat")
    }

    @Test
    fun allowIsRememberedAndAnswersTheNextRequestWithoutAsking() {
        val first = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(MICROPHONE))
        val request = viewModel.permissionRequests.value.single()
        assertEquals(setOf(MICROPHONE), request.kinds)
        assertEquals("web.example.org", request.displayHost)
        assertTrue(request.offersAllowOnce)

        viewModel.answerPermission(request.id, PermissionAnswer.ALLOW)

        assertEquals(PermissionAnswer.ALLOW, first.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
        assertEquals(SitePermissionState.ALLOW, viewModel.sitePermissions.value.single().state)
        val second = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(MICROPHONE))
        assertEquals(PermissionAnswer.ALLOW, second.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun blockIsRememberedAndRefusesWithoutAsking() {
        engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(NOTIFICATIONS))
        viewModel.answerPermission(viewModel.permissionRequests.value.single().id, PermissionAnswer.BLOCK)

        val again = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(NOTIFICATIONS))

        assertEquals(PermissionAnswer.DISMISS, again.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
        assertEquals(SitePermissionState.BLOCK, engine.storedPermissions.single().state)
    }

    @Test
    fun allowOnceLastsWhileTheTabStaysOnTheSite() {
        engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(LOCATION))
        viewModel.answerPermission(viewModel.permissionRequests.value.single().id, PermissionAnswer.ALLOW_ONCE)
        assertTrue(engine.storedPermissions.isEmpty())

        val sameSite = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(LOCATION))
        assertEquals(PermissionAnswer.ALLOW_ONCE, sameSite.answer)

        engine.sessions.getValue("tab-1").simulatePageStart("https://other.example.com/")
        engine.sessions.getValue("tab-1").simulatePageStart("https://web.example.org/chat")
        val afterLeaving = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(LOCATION))
        assertNull(afterLeaving.answer)
        assertEquals(1, viewModel.permissionRequests.value.size)
    }

    @Test
    fun aNewDocumentOrClosingTheTabDismissesTheRequest() {
        val stays = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(CAMERA))
        // A change within the page (pushState, a fragment) is not a new document
        engine.sessions.getValue("tab-1").simulateHistoryMove("https://web.example.org/chat#a", canGoBack = true, canGoForward = false)
        assertNull(stays.answer)

        engine.sessions.getValue("tab-1").simulatePageStart("https://elsewhere.example.net/")
        assertEquals(PermissionAnswer.DISMISS, stays.answer)

        val tab2 = viewModel.openNewTab("https://web.example.org/")
        engine.sessions.getValue(tab2).simulatePageLoad("https://web.example.org/", "Chat")
        val closing = engine.simulatePermissionRequest(tab2, "https://web.example.org", setOf(CAMERA))
        viewModel.closeTab(tab2)
        assertEquals(PermissionAnswer.DISMISS, closing.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun cameraAndMicrophoneAskOnlyForWhatIsUndecided() {
        engine.simulatePermissionRequest(
            "tab-1", "https://web.example.org", setOf(CAMERA, MICROPHONE),
            stored = mapOf(CAMERA to SitePermissionState.ALLOW)
        )
        val request = viewModel.permissionRequests.value.single()
        assertEquals(setOf(MICROPHONE), request.kinds)
    }

    @Test
    fun allowOnceIsNotOfferedForNotificationsAndCountsAsDismiss() {
        val ask = engine.simulatePermissionRequest("tab-1", "https://web.example.org", setOf(NOTIFICATIONS))
        val request = viewModel.permissionRequests.value.single()
        assertFalse(request.offersAllowOnce)

        viewModel.answerPermission(request.id, PermissionAnswer.ALLOW_ONCE)

        assertEquals(PermissionAnswer.DISMISS, ask.answer)
    }

    @Test
    fun settingsChangesReachTheEngine() {
        viewModel.setSitePermission("https://web.example.org", LOCATION, SitePermissionState.BLOCK)
        assertEquals(SitePermissionState.BLOCK, viewModel.sitePermissions.value.single().state)

        viewModel.setSitePermission("https://web.example.org", LOCATION, null)
        assertTrue(viewModel.sitePermissions.value.isEmpty())

        viewModel.setSitePermission("https://web.example.org", CAMERA, SitePermissionState.ALLOW)
        viewModel.clearSitePermissions("https://web.example.org")
        assertTrue(viewModel.sitePermissions.value.isEmpty())
        assertEquals(listOf("https://web.example.org"), engine.clearedPermissionOrigins)
    }

    @Test
    fun originsAreNormalised() {
        assertEquals("https://example.org", SiteOrigins.originOf("HTTPS://Example.ORG:443/path?q#f"))
        assertEquals("http://localhost:8080", SiteOrigins.originOf("http://user@localhost:8080/"))
        assertEquals("https://xn--bcher-kva.example", SiteOrigins.originOf("https://bücher.example/"))
        // Never shown by Gecko in this run, so nothing vouches for the Unicode form
        assertEquals("xn--bcher-kva.example", SiteOrigins.displayHost("https://xn--bcher-kva.example"))
        assertNull(SiteOrigins.originOf("about:blank"))
        assertNull(SiteOrigins.originOf("file:///sdcard/a.html"))
    }

    @Test
    fun eitherLocationPermissionIsEnough() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        val camera = android.Manifest.permission.CAMERA
        assertTrue(GeckoSitePermissions.holdsEnough(listOf(fine, coarse), setOf(coarse)))
        assertFalse(GeckoSitePermissions.holdsEnough(listOf(fine, camera), setOf(fine)))
        assertTrue(GeckoSitePermissions.holdsEnough(listOf(camera), setOf(camera)))
    }

    // --- queueing ---

    private val site = "https://web.example.org"

    private fun secondTab(url: String = "$site/"): String {
        val id = viewModel.openNewTab(url)
        engine.sessions.getValue(id).simulatePageLoad(url, "Page")
        return id
    }

    @Test
    fun requestsQueueOldestFirstAndTheCardShowsTheFirstOfATab() {
        val first = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        val second = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        val third = engine.simulatePermissionRequest("tab-1", site, setOf(NOTIFICATIONS))

        assertEquals(listOf(first.id, second.id, third.id), viewModel.permissionRequests.value.map { it.id })

        viewModel.answerPermission(first.id, PermissionAnswer.BLOCK)
        assertEquals(listOf(second.id, third.id), viewModel.permissionRequests.value.map { it.id })
        assertNull(second.answer)
        assertNull(third.answer)
    }

    @Test
    fun eachTabKeepsItsOwnRequestAndAnsweringOneLeavesTheOther() {
        val tab2 = secondTab("https://other.example.net/")
        val one = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        val two = engine.simulatePermissionRequest(tab2, "https://other.example.net", setOf(LOCATION))

        val requests = viewModel.permissionRequests.value
        assertEquals("tab-1", requests.single { it.id == one.id }.tabId)
        assertEquals(tab2, requests.single { it.id == two.id }.tabId)

        viewModel.answerPermission(one.id, PermissionAnswer.ALLOW)

        assertEquals(PermissionAnswer.ALLOW, one.answer)
        assertNull(two.answer)
        assertEquals(listOf(two.id), viewModel.permissionRequests.value.map { it.id })
    }

    @Test
    fun answeringAnUnknownOrAlreadyAnsweredRequestChangesNothing() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        viewModel.answerPermission("no-such-request", PermissionAnswer.ALLOW)
        assertNull(ask.answer)
        assertEquals(1, viewModel.permissionRequests.value.size)

        viewModel.answerPermission(ask.id, PermissionAnswer.DISMISS)
        viewModel.answerPermission(ask.id, PermissionAnswer.ALLOW)

        assertEquals(PermissionAnswer.DISMISS, ask.answer)
        assertTrue(engine.storedPermissions.isEmpty())
    }

    @Test
    fun theSameRequestReportedTwiceIsQueuedOnce() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        engine.sessions.getValue("tab-1").listener.onPermissionRequested(ask.ask)
        assertEquals(1, viewModel.permissionRequests.value.size)
    }

    // --- answers reach the engine ---

    @Test
    fun everyAnswerReachesTheEngineAndOnlyAllowAndBlockAreRemembered() {
        val expectations = mapOf(
            PermissionAnswer.ALLOW to SitePermissionState.ALLOW,
            PermissionAnswer.BLOCK to SitePermissionState.BLOCK,
            PermissionAnswer.ALLOW_ONCE to null,
            PermissionAnswer.DISMISS to null
        )
        for ((answer, remembered) in expectations) {
            engine.storedPermissions.clear()
            val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
            viewModel.answerPermission(ask.id, answer)

            assertEquals("engine answer for $answer", answer, ask.answer)
            assertEquals("remembered for $answer", remembered, engine.storedPermissions.singleOrNull()?.state)
            assertEquals(remembered, viewModel.sitePermissions.value.singleOrNull()?.state)
            // Reset also ends an allow-once grant, so the next round asks again
            viewModel.clearSitePermissions(site)
        }
    }

    @Test
    fun allowOnCameraAndMicrophoneRemembersBothKinds() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA, MICROPHONE))
        viewModel.answerPermission(ask.id, PermissionAnswer.ALLOW)

        assertEquals(setOf(CAMERA, MICROPHONE), viewModel.sitePermissions.value.map { it.kind }.toSet())
        assertEquals(setOf(CAMERA, MICROPHONE), engine.storedPermissions.map { it.kind }.toSet())
    }

    @Test
    fun cameraAndMicrophoneOfferAllowOnceButCookiesAndDrmDoNot() {
        engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA, MICROPHONE))
        assertTrue(viewModel.permissionRequests.value.single().offersAllowOnce)

        val drm = engine.simulatePermissionRequest("tab-1", site, setOf(PROTECTED_CONTENT))
        val cookies = engine.simulatePermissionRequest(
            "tab-1", site, setOf(STORAGE_ACCESS), embeddedOrigin = "https://comments.example.com"
        )
        val byId = viewModel.permissionRequests.value.associateBy { it.id }
        assertFalse(byId.getValue(drm.id).offersAllowOnce)
        assertFalse(byId.getValue(cookies.id).offersAllowOnce)
    }

    @Test
    fun allowAnswersTheSameQuestionWaitingFromTheSiteInAnotherTab() {
        val tab2 = secondTab()
        val one = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        val two = engine.simulatePermissionRequest(tab2, site, setOf(CAMERA))

        viewModel.answerPermission(one.id, PermissionAnswer.ALLOW)

        assertEquals(PermissionAnswer.ALLOW, two.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun blockAnswersTheSameQuestionButNotAnotherSitesOrAnotherKind() {
        val tab2 = secondTab()
        val tab3 = secondTab("https://elsewhere.example.net/")
        val one = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        val sameSite = engine.simulatePermissionRequest(tab2, site, setOf(CAMERA))
        val otherKind = engine.simulatePermissionRequest(tab2, site, setOf(LOCATION))
        val otherSite = engine.simulatePermissionRequest(tab3, "https://elsewhere.example.net", setOf(CAMERA))

        viewModel.answerPermission(one.id, PermissionAnswer.BLOCK)

        assertEquals(PermissionAnswer.BLOCK, sameSite.answer)
        assertNull(otherKind.answer)
        assertNull(otherSite.answer)
        assertEquals(2, viewModel.permissionRequests.value.size)
    }

    @Test
    fun allowForOneEmbeddedSiteDoesNotAnswerAnotherEmbeddedSite() {
        val one = engine.simulatePermissionRequest("tab-1", site, setOf(STORAGE_ACCESS), "https://ads.example.com")
        val two = engine.simulatePermissionRequest("tab-1", site, setOf(STORAGE_ACCESS), "https://comments.example.com")

        viewModel.answerPermission(one.id, PermissionAnswer.ALLOW)

        assertNull(two.answer)
        assertEquals(listOf(two.id), viewModel.permissionRequests.value.map { it.id })
        val entry = viewModel.sitePermissions.value.single()
        assertEquals("https://ads.example.com", entry.embeddedOrigin)
    }

    @Test
    fun allowOnceAnswersTheTabsOtherRequestsItCoversButNotAnotherTabs() {
        val tab2 = secondTab()
        val one = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA, MICROPHONE))
        val sameTabCovered = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        val otherTab = engine.simulatePermissionRequest(tab2, site, setOf(CAMERA))

        viewModel.answerPermission(one.id, PermissionAnswer.ALLOW_ONCE)

        assertEquals(PermissionAnswer.ALLOW_ONCE, sameTabCovered.answer)
        assertNull(otherTab.answer)
        assertEquals(listOf(otherTab.id), viewModel.permissionRequests.value.map { it.id })
    }

    @Test
    fun allowOnceDoesNotCoverAKindThatWasNotGranted() {
        val camera = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        viewModel.answerPermission(camera.id, PermissionAnswer.ALLOW_ONCE)

        val mic = engine.simulatePermissionRequest("tab-1", site, setOf(MICROPHONE))

        assertNull(mic.answer)
        assertEquals(setOf(MICROPHONE), viewModel.permissionRequests.value.single().kinds)
    }

    @Test
    fun aRememberedBlockRefusesAnyRequestThatIncludesIt() {
        val ask = engine.simulatePermissionRequest(
            "tab-1", site, setOf(CAMERA, MICROPHONE),
            stored = mapOf(MICROPHONE to SitePermissionState.BLOCK)
        )
        assertEquals(PermissionAnswer.DISMISS, ask.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun aRequestWhoseKindsAreAllAllowedIsAnsweredAllow() {
        val ask = engine.simulatePermissionRequest(
            "tab-1", site, setOf(CAMERA, MICROPHONE),
            stored = mapOf(CAMERA to SitePermissionState.ALLOW, MICROPHONE to SitePermissionState.ALLOW)
        )
        assertEquals(PermissionAnswer.ALLOW, ask.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun aRequestFromATabShowingTheStartPageIsRefusedWithoutAsking() {
        val startPage = viewModel.openNewTab()
        val ask = engine.simulatePermissionRequest(startPage, site, setOf(CAMERA))
        assertEquals(PermissionAnswer.DISMISS, ask.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    // --- withdrawing ---

    @Test
    fun theEngineWithdrawingARequestRemovesItFromTheQueue() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        val other = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))

        engine.simulatePermissionWithdrawn(ask)

        assertTrue(ask.withdrawn)
        assertEquals(listOf(other.id), viewModel.permissionRequests.value.map { it.id })
        assertTrue(engine.storedPermissions.isEmpty())
    }

    @Test
    fun closingATabDismissesOnlyItsOwnRequests() {
        val tab2 = secondTab("https://other.example.net/")
        val keep = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        val gone = engine.simulatePermissionRequest(tab2, "https://other.example.net", setOf(LOCATION))

        viewModel.closeTab(tab2)

        assertEquals(PermissionAnswer.DISMISS, gone.answer)
        assertNull(keep.answer)
        assertEquals(listOf(keep.id), viewModel.permissionRequests.value.map { it.id })
        assertTrue(engine.storedPermissions.isEmpty())
    }

    @Test
    fun anyNewDocumentInTheTabDismissesItsRequestsEvenOfTheSameSite() {
        val reloaded = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        // A reload, a redirect or a link within the site loads a new document all the same
        engine.sessions.getValue("tab-1").simulatePageStart("$site/chat")
        assertEquals(PermissionAnswer.DISMISS, reloaded.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())

        val elsewhere = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        // Same host on another port is another origin
        engine.sessions.getValue("tab-1").simulatePageStart("https://web.example.org:8443/")
        assertEquals(PermissionAnswer.DISMISS, elsewhere.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun aPageOnTheOtherSchemeDismissesTheRequest() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        engine.sessions.getValue("tab-1").simulatePageStart("http://web.example.org/")
        assertEquals(PermissionAnswer.DISMISS, ask.answer)
    }

    @Test
    fun anotherTabsNavigationLeavesARequestAlone() {
        val tab2 = secondTab("https://other.example.net/")
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        engine.sessions.getValue(tab2).simulatePageStart("https://third.example.net/")
        assertNull(ask.answer)
        assertEquals(1, viewModel.permissionRequests.value.size)
    }

    @Test
    fun shuttingDownDismissesWhatIsWaiting() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        viewModel.shutdown()
        assertEquals(PermissionAnswer.DISMISS, ask.answer)
        assertTrue(viewModel.permissionRequests.value.isEmpty())
    }

    @Test
    fun storageAccessNamesTheEmbeddedHost() {
        engine.simulatePermissionRequest("tab-1", site, setOf(STORAGE_ACCESS), "https://comments.example.com")
        val request = viewModel.permissionRequests.value.single()
        assertEquals("web.example.org", request.displayHost)
        assertEquals("comments.example.com", request.embeddedHost)
        assertEquals(setOf(STORAGE_ACCESS), request.kinds)
    }

    @Test
    fun aRequestFromAPortedOrIdnSiteIsNamedReadably() {
        engine.simulatePermissionRequest("tab-1", "http://192.168.1.20:8080", setOf(LOCATION))
        assertEquals("192.168.1.20:8080", viewModel.permissionRequests.value.single().displayHost)
    }

    // --- allow once ends ---

    @Test
    fun forgettingADecisionEndsAnAllowOnceGrantSoTheSiteAsksAgain() {
        val first = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        viewModel.answerPermission(first.id, PermissionAnswer.ALLOW_ONCE)
        assertEquals(PermissionAnswer.ALLOW_ONCE, engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION)).answer)

        viewModel.setSitePermission(site, LOCATION, null)

        val again = engine.simulatePermissionRequest("tab-1", site, setOf(LOCATION))
        assertNull(again.answer)
        assertEquals(1, viewModel.permissionRequests.value.size)
    }

    @Test
    fun resettingASiteEndsItsAllowOnceGrants() {
        val first = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        viewModel.answerPermission(first.id, PermissionAnswer.ALLOW_ONCE)

        viewModel.clearSitePermissions(site)

        assertNull(engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA)).answer)
    }

    @Test
    fun closingATabEndsItsAllowOnceGrant() {
        val tab2 = secondTab()
        val first = engine.simulatePermissionRequest(tab2, site, setOf(CAMERA))
        viewModel.answerPermission(first.id, PermissionAnswer.ALLOW_ONCE)
        viewModel.closeTab(tab2)

        val tab3 = secondTab()
        // The grant belonged to the closed tab, not to the site
        assertNull(engine.simulatePermissionRequest(tab3, site, setOf(CAMERA)).answer)
    }

    @Test
    fun anAllowOnceGrantDoesNotCarryToAnotherTabOnTheSameSite() {
        val first = engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        viewModel.answerPermission(first.id, PermissionAnswer.ALLOW_ONCE)
        val tab2 = secondTab()
        assertNull(engine.simulatePermissionRequest(tab2, site, setOf(CAMERA)).answer)
    }

    // --- settings ---

    @Test
    fun setSitePermissionNormalisesTheOriginBeforeTheEngineSeesIt() {
        viewModel.setSitePermission("HTTPS://Web.Example.ORG:443/chat?x=1", CAMERA, SitePermissionState.ALLOW)
        val change = engine.sitePermissionChanges.single()
        assertEquals("https://web.example.org", change.origin)
        assertEquals(CAMERA, change.kind)
        assertEquals(SitePermissionState.ALLOW, change.state)
        assertNull(change.embeddedOrigin)
        assertEquals("https://web.example.org", viewModel.sitePermissions.value.single().origin)
    }

    @Test
    fun setSitePermissionIgnoresAnOriginThatIsNotAWebSite() {
        viewModel.setSitePermission("about:blank", CAMERA, SitePermissionState.ALLOW)
        viewModel.setSitePermission("file:///sdcard/a.html", CAMERA, SitePermissionState.BLOCK)
        viewModel.clearSitePermissions("javascript:alert(1)")
        assertTrue(engine.sitePermissionChanges.isEmpty())
        assertTrue(engine.clearedPermissionOrigins.isEmpty())
        assertTrue(viewModel.sitePermissions.value.isEmpty())
    }

    @Test
    fun switchingADecisionReplacesItAndKeepsOneRowPerKind() {
        viewModel.setSitePermission(site, LOCATION, SitePermissionState.ALLOW)
        viewModel.setSitePermission(site, LOCATION, SitePermissionState.BLOCK)

        assertEquals(SitePermissionState.BLOCK, viewModel.sitePermissions.value.single().state)
        assertEquals(
            listOf(SitePermissionState.ALLOW, SitePermissionState.BLOCK),
            engine.sitePermissionChanges.map { it.state }
        )
    }

    @Test
    fun forgettingNullRemovesOnlyThatKindOfThatSite() {
        viewModel.setSitePermission(site, LOCATION, SitePermissionState.ALLOW)
        viewModel.setSitePermission(site, CAMERA, SitePermissionState.ALLOW)
        viewModel.setSitePermission("https://other.example.net", LOCATION, SitePermissionState.ALLOW)

        viewModel.setSitePermission(site, LOCATION, null)

        val left = viewModel.sitePermissions.value.map { it.origin to it.kind }
        assertEquals(setOf(site to CAMERA, "https://other.example.net" to LOCATION), left.toSet())
        assertEquals(left.toSet(), engine.storedPermissions.map { it.origin to it.kind }.toSet())
    }

    @Test
    fun cookieDecisionsAreKeptPerEmbeddedSite() {
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://ads.example.com")
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://comments.example.com")
        assertEquals(2, viewModel.sitePermissions.value.size)
        assertEquals(
            listOf("https://ads.example.com", "https://comments.example.com"),
            engine.sitePermissionChanges.map { it.embeddedOrigin }
        )

        // Blocking one leaves the other
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.BLOCK, "https://ads.example.com")
        val byEmbedded = viewModel.sitePermissions.value.associate { it.embeddedOrigin to it.state }
        assertEquals(SitePermissionState.BLOCK, byEmbedded["https://ads.example.com"])
        assertEquals(SitePermissionState.ALLOW, byEmbedded["https://comments.example.com"])

        // Forgetting one leaves the other
        viewModel.setSitePermission(site, STORAGE_ACCESS, null, "https://ads.example.com")
        assertEquals(listOf("https://comments.example.com"), viewModel.sitePermissions.value.map { it.embeddedOrigin })
        assertEquals(listOf("https://comments.example.com"), engine.storedPermissions.map { it.embeddedOrigin })
    }

    @Test
    fun cookieDecisionWithoutAnEmbeddedSiteSwitchesEveryListedOneButAddsNone() {
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://ads.example.com")
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://comments.example.com")

        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.BLOCK)
        assertEquals(
            listOf(SitePermissionState.BLOCK, SitePermissionState.BLOCK),
            viewModel.sitePermissions.value.map { it.state }
        )
        assertEquals(2, viewModel.sitePermissions.value.size)

        // Nothing is listed for a site without cookie decisions, so none appears
        viewModel.setSitePermission("https://fresh.example.net", STORAGE_ACCESS, SitePermissionState.ALLOW)
        assertEquals(2, viewModel.sitePermissions.value.size)
    }

    @Test
    fun forgettingCookiesWithoutAnEmbeddedSiteRemovesAllOfThatSite() {
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://ads.example.com")
        viewModel.setSitePermission(site, STORAGE_ACCESS, SitePermissionState.ALLOW, "https://comments.example.com")
        viewModel.setSitePermission(site, LOCATION, SitePermissionState.ALLOW)

        viewModel.setSitePermission(site, STORAGE_ACCESS, null)

        assertEquals(listOf(LOCATION), viewModel.sitePermissions.value.map { it.kind })
    }

    @Test
    fun clearSitePermissionsForgetsEverythingOfOneSiteOnly() {
        viewModel.setSitePermission(site, LOCATION, SitePermissionState.ALLOW)
        viewModel.setSitePermission(site, CAMERA, SitePermissionState.BLOCK)
        viewModel.setSitePermission("http://web.example.org", LOCATION, SitePermissionState.ALLOW)

        viewModel.clearSitePermissions("$site/some/page")

        assertEquals(listOf("http://web.example.org"), viewModel.sitePermissions.value.map { it.origin })
        assertEquals(listOf(site), engine.clearedPermissionOrigins)
    }

    @Test
    fun refreshListsTheEnginesDecisionsSortedBySiteThenKind() {
        fun entry(origin: String, kind: SitePermissionKind) =
            SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, SitePermissionState.ALLOW)
        engine.storedPermissions += listOf(
            entry("https://b.example", CAMERA),
            entry("https://a.example", NOTIFICATIONS),
            entry("https://a.example", CAMERA)
        )

        viewModel.refreshSitePermissions()

        assertEquals(
            listOf("https://a.example" to CAMERA, "https://a.example" to NOTIFICATIONS, "https://b.example" to CAMERA),
            viewModel.sitePermissions.value.map { it.origin to it.kind }
        )
    }

    // --- showTabForSite ---

    @Test
    fun showTabForSitePicksTheTabFocusedLastOnThatSite() {
        val tab2 = secondTab("https://other.example.net/")
        val tab3 = secondTab("$site/other")
        // tab-1 and tab-3 are both on the site; focus tab-1 after tab-3
        viewModel.selectTab(tab3)
        viewModel.selectTab("tab-1")
        viewModel.selectTab(tab2)

        assertEquals("tab-1", viewModel.showTabForSite("$site/inbox"))
        assertEquals("tab-1", viewModel.uiState.value.activeTabId)

        viewModel.selectTab(tab3)
        viewModel.selectTab(tab2)
        assertEquals(tab3, viewModel.showTabForSite("$site/inbox"))
        assertEquals(tab3, viewModel.uiState.value.activeTabId)
    }

    @Test
    fun showTabForSiteOpensANewTabWhenNoneIsOnThatOrigin() {
        val before = viewModel.uiState.value.tabs.size

        val shown = viewModel.showTabForSite("https://nowhere.example.net/page")

        val state = viewModel.uiState.value
        assertEquals(before + 1, state.tabs.size)
        assertEquals(shown, state.activeTabId)
        assertEquals("https://nowhere.example.net/page", state.activeTab?.url)
    }

    @Test
    fun showTabForSiteTreatsAnotherPortOrSchemeAsAnotherSite() {
        secondTab("$site:8443/")
        secondTab("http://web.example.org/")
        val before = viewModel.uiState.value.tabs.size

        val shown = viewModel.showTabForSite("$site/inbox")

        // tab-1 is on https://web.example.org
        assertEquals("tab-1", shown)
        assertEquals(before, viewModel.uiState.value.tabs.size)
    }

    @Test
    fun showTabForSiteRefusesAnAddressThatIsNotAWebPage() {
        val before = viewModel.uiState.value.tabs.size
        assertNull(viewModel.showTabForSite("about:blank"))
        assertNull(viewModel.showTabForSite("javascript:alert(1)"))
        assertNull(viewModel.showTabForSite("file:///sdcard/a.html"))
        assertEquals(before, viewModel.uiState.value.tabs.size)
    }

    @Test
    fun showTabForSiteLeavesFullscreenOfAnotherTab() {
        val tab2 = secondTab("https://video.example.net/")
        engine.sessions.getValue(tab2).simulateFullScreen(true)
        assertNotEquals(null, viewModel.uiState.value.fullscreenTabId)

        viewModel.showTabForSite("$site/inbox")

        assertNull(viewModel.uiState.value.fullscreenTabId)
        assertEquals("tab-1", viewModel.uiState.value.activeTabId)
    }

    // --- origins ---

    @Test
    fun portsAreKeptOnlyWhenTheyAreNotTheSchemesUsualOne() {
        assertEquals("http://example.org", SiteOrigins.originOf("http://example.org:80/"))
        assertEquals("https://example.org", SiteOrigins.originOf("https://example.org:443/"))
        assertEquals("https://example.org:80", SiteOrigins.originOf("https://example.org:80/"))
        assertEquals("http://example.org:443", SiteOrigins.originOf("http://example.org:443/"))
        assertEquals("https://example.org:8443", SiteOrigins.originOf("https://example.org:8443/x"))
        assertEquals("https://example.org", SiteOrigins.originOf("https://example.org:/x"))
    }

    @Test
    fun httpAndHttpsAreDifferentSites() {
        assertNotEquals(SiteOrigins.originOf("http://example.org/"), SiteOrigins.originOf("https://example.org/"))
        assertEquals("http://example.org", SiteOrigins.originOf("HTTP://EXAMPLE.org"))
    }

    @Test
    fun ipAddressesKeepTheirPortAndIpv6KeepsItsBrackets() {
        assertEquals("http://192.168.1.20:8080", SiteOrigins.originOf("http://192.168.1.20:8080/a?b#c"))
        assertEquals("http://10.0.0.1", SiteOrigins.originOf("http://10.0.0.1:80"))
        assertEquals("http://[::1]:3000", SiteOrigins.originOf("http://[::1]:3000/"))
        assertEquals("https://[2001:db8::1]", SiteOrigins.originOf("https://[2001:DB8::1]:443/"))
        assertEquals("192.168.1.20:8080", SiteOrigins.displayHost("http://192.168.1.20:8080"))
        assertEquals("[::1]:3000", SiteOrigins.displayHost("http://[::1]:3000"))
        assertEquals("[2001:db8::1]", SiteOrigins.displayHost("https://[2001:db8::1]"))
    }

    @Test
    fun displayHostShowsThePortOnlyWhenTheOriginHasOne() {
        assertEquals("example.org", SiteOrigins.displayHost("https://example.org"))
        assertEquals("example.org:8443", SiteOrigins.displayHost("https://example.org:8443"))
        assertEquals("localhost:8080", SiteOrigins.displayHost("http://localhost:8080"))
    }

    @Test
    fun displayHostOfSomethingThatIsNotAnOriginIsReturnedAsGiven() {
        assertEquals("about:blank", SiteOrigins.displayHost("about:blank"))
        assertEquals("", SiteOrigins.displayHost(""))
        assertEquals("ftp://example.org", SiteOrigins.displayHost("ftp://example.org"))
    }

    @Test
    fun oddOrInvalidAddressesHaveNoOrigin() {
        listOf(
            null, "", "   ", "example.org", "//example.org/", "ftp://example.org/", "javascript:alert(1)",
            "data:text/html,hi", "blob:https://example.org/uuid", "https://", "https:///path", "http://:80/",
            "https://example.org:abc/", "view-source:https://example.org/", "rem://settings"
        ).forEach { assertNull("origin of '$it'", SiteOrigins.originOf(it)) }
    }

    @Test
    fun anAddressIsReadThroughWhitespaceUserinfoQueryAndFragment() {
        assertEquals("https://example.org", SiteOrigins.originOf("  https://example.org/path  "))
        assertEquals("https://example.org", SiteOrigins.originOf("https://user:secret@example.org/"))
        assertEquals("https://example.org", SiteOrigins.originOf("https://example.org?x=1"))
        assertEquals("https://example.org", SiteOrigins.originOf("https://example.org#top"))
    }

    @Test
    fun allowOnceIsOfferedForCameraMicrophoneAndLocationOnly() {
        val offered = SitePermissionKind.entries.filter { it.offersAllowOnce }.toSet()
        assertEquals(setOf(CAMERA, MICROPHONE, LOCATION), offered)
    }
}
