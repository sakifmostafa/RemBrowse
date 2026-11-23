package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.fakes.FakeBrowserEngine
import com.rembrowser.rembrowse.fakes.FakeBrowserStateRepository
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind.CAMERA
import com.rembrowser.rembrowse.model.SitePermissionKind.LOCATION
import com.rembrowser.rembrowse.model.SitePermissionKind.MICROPHONE
import com.rembrowser.rembrowse.model.SitePermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The view-model and model sides of the fixes from the site permissions review. */
class SitePermissionsReviewFixesTest {

    private lateinit var engine: FakeBrowserEngine
    private lateinit var viewModel: BrowserViewModel
    private var idCounter = 0
    private val site = "https://meet.example.org"

    @Before
    fun setUp() {
        engine = FakeBrowserEngine()
        viewModel = BrowserViewModel(engine, FakeBrowserStateRepository(), idGenerator = { "tab-${++idCounter}" })
        engine.sessions.getValue("tab-1").simulatePageLoad("$site/room", "Room")
    }

    private fun tabOn(url: String): String {
        val id = viewModel.openNewTab(url)
        engine.sessions.getValue(id).simulatePageLoad(url, "Page")
        return id
    }

    // 1. Camera and microphone belong to the page in the address bar

    @Test
    fun aSiteAllowedOnItsOwnLendsNothingToAPageThatEmbedsIt() {
        engine.storedPermissions += SitePermissionEntry(site, "meet.example.org", CAMERA, SitePermissionState.ALLOW)
        val evil = tabOn("https://evil.example/")

        // The engine reports the page's site, whatever frame inside it asked
        val ask = engine.simulatePermissionRequest(evil, "https://evil.example", setOf(CAMERA))

        assertNull(ask.answer)
        assertEquals("evil.example", viewModel.permissionRequests.value.single().displayHost)
    }

    // 2. Hosts keep the characters that make them different sites

    @Test
    fun sharpSAndFinalSigmaAreNotFoldedIntoAnotherSite() {
        assertEquals("https://xn--fa-hia.de", SiteOrigins.originOf("https://faß.de/"))
        assertNotEquals(SiteOrigins.originOf("https://fass.de/"), SiteOrigins.originOf("https://faß.de/"))
        assertNotEquals(SiteOrigins.originOf("https://σ.example/"), SiteOrigins.originOf("https://ς.example/"))
        // Punycode from Gecko is kept as it is
        assertEquals("https://xn--fa-hia.de", SiteOrigins.originOf("https://XN--FA-HIA.de/"))
    }

    @Test
    fun punycodeMatchesRfc3492() {
        assertEquals("bcher-kva", SiteOrigins.Punycode.encode("bücher"))
        assertEquals("mnchen-3ya", SiteOrigins.Punycode.encode("münchen"))
        assertEquals("fa-hia", SiteOrigins.Punycode.encode("faß"))
    }

    // M1. The card, Settings and notifications never show a host more readably than Gecko does

    private fun ace(label: String) = "xn--" + SiteOrigins.Punycode.encode(label)

    @Test
    fun aFractionSlashLabelIsShownInPunycode() {
        // paypal.com\u2044signin.evil.example would read as paypal.com with a path
        val host = "paypal." + ace("com\u2044signin") + ".evil.example"
        assertEquals(host, SiteOrigins.displayHost("https://$host"))
    }

    @Test
    fun aDivisionSlashLabelIsShownInPunycode() {
        val host = "paypal." + ace("com\u2215signin") + ".evil.example"
        assertEquals(host, SiteOrigins.displayHost("https://$host"))
    }

    @Test
    fun anAllCyrillicLookAlikeIsShownInPunycode() {
        val cyrillic = ace("\u0440\u0430\u0443\u0440\u0430l".replace("l", "\u04cf"))
        assertEquals("$cyrillic.com", SiteOrigins.displayHost("https://$cyrillic.com"))
        val mixed = ace("p\u0430ypal")
        assertEquals("$mixed.com", SiteOrigins.displayHost("https://$mixed.com"))
    }

    @Test
    fun geckosOwnDisplayFormIsShownAndItsPunycodeStaysPunycode() {
        // Gecko showed this host in Unicode, as the address bar does
        SiteOrigins.rememberDisplayForm("https://www.k\u00f6ln-test.example/page")
        val koeln = SiteOrigins.originOf("https://k\u00f6ln-test.example")!!
        assertEquals("www.k\u00f6ln-test.example", SiteOrigins.displayHost(SiteOrigins.originOf("https://www.k\u00f6ln-test.example")!!))
        // Its site, as an embedded page's site is named
        assertEquals("k\u00f6ln-test.example", SiteOrigins.displayHost(koeln))
        // Gecko kept this one in punycode: so do we
        val kept = ace("\u0440\u0430\u0443\u0440\u0430\u04cf-kept")
        SiteOrigins.rememberDisplayForm("https://$kept.com/")
        assertEquals("$kept.com", SiteOrigins.displayHost("https://$kept.com"))
        // The decision's key is the ASCII origin whatever is shown
        assertEquals("https://" + ace("k\u00f6ln-test") + ".example", koeln)
    }

    // 3. Storage access is granted to the embedded page's whole site

    @Test
    fun aSiteIsTheSchemeAndRegistrableDomain() {
        assertEquals("https://embed.com", SiteOrigins.siteOf("https://www.embed.com:8443/frame"))
        assertEquals("https://embed.co.uk", SiteOrigins.siteOf("https://login.embed.co.uk/"))
        assertEquals("http://192.168.1.20", SiteOrigins.siteOf("http://192.168.1.20:8080/"))
        assertEquals("http://localhost", SiteOrigins.siteOf("http://localhost:3000/"))
        assertNull(SiteOrigins.siteOf("about:blank"))
    }

    // 4. Taking a device away stops the pages using it

    @Test
    fun blockingTheCameraReloadsOnlyThatSitesTabsUsingTheCamera() {
        val cameraHere = engine.sessions.getValue("tab-1")
        cameraHere.simulateCaptureDevices(setOf(CAMERA, MICROPHONE))
        val micOnlyHere = engine.sessions.getValue(tabOn("$site/call"))
        micOnlyHere.simulateCaptureDevices(setOf(MICROPHONE))
        val idleHere = engine.sessions.getValue(tabOn("$site/lobby"))
        val cameraElsewhere = engine.sessions.getValue(tabOn("https://other.example/"))
        cameraElsewhere.simulateCaptureDevices(setOf(CAMERA))

        viewModel.setSitePermission(site, CAMERA, SitePermissionState.BLOCK)

        assertEquals(1, cameraHere.reloadCount)
        assertEquals(0, micOnlyHere.reloadCount)
        assertEquals(0, idleHere.reloadCount)
        assertEquals(0, cameraElsewhere.reloadCount)
    }

    @Test
    fun removingOrClearingReloadsButAllowingDoesNot() {
        val page = engine.sessions.getValue("tab-1")
        page.simulateCaptureDevices(setOf(MICROPHONE))

        viewModel.setSitePermission(site, MICROPHONE, SitePermissionState.ALLOW)
        assertEquals(0, page.reloadCount)

        viewModel.setSitePermission(site, MICROPHONE, null)
        assertEquals(1, page.reloadCount)

        page.simulateCaptureDevices(setOf(MICROPHONE))
        viewModel.clearSitePermissions(site)
        assertEquals(2, page.reloadCount)
    }

    @Test
    fun aPageThatStoppedCapturingIsLeftAlone() {
        val page = engine.sessions.getValue("tab-1")
        page.simulateCaptureDevices(setOf(CAMERA))
        page.simulateCaptureDevices(emptySet())

        viewModel.setSitePermission(site, CAMERA, SitePermissionState.BLOCK)

        assertEquals(0, page.reloadCount)
    }

    @Test
    fun blockingLocationReloadsNothing() {
        val page = engine.sessions.getValue("tab-1")
        page.simulateCaptureDevices(setOf(CAMERA))

        viewModel.setSitePermission(site, LOCATION, SitePermissionState.BLOCK)

        assertEquals(0, page.reloadCount)
    }

    // M2. The reload follows the page the engine committed to, not the address bar

    @Test
    fun aNavigationThatNeverCommittedDoesNotHideALiveStream() {
        val page = engine.sessions.getValue("tab-1")
        page.simulateCaptureDevices(setOf(CAMERA))
        // A download or a 204: the page start moved the address bar, the call page stayed
        page.simulatePageStart("https://files.example.net/report.pdf")

        viewModel.setSitePermission(site, CAMERA, SitePermissionState.BLOCK)

        assertEquals(1, page.reloadCount)
    }

    @Test
    fun aTabWhoseAddressBarOnlyShowsTheSiteIsNotReloaded() {
        val other = engine.sessions.getValue(tabOn("https://other.example/"))
        other.simulateCaptureDevices(setOf(CAMERA))
        other.simulatePageStart("$site/never-committed")

        viewModel.setSitePermission(site, CAMERA, SitePermissionState.BLOCK)

        assertEquals(0, other.reloadCount)
    }

    // L4. Settings show what is stored once Android has answered

    @Test
    fun anAllowAndroidRefusedLeavesSettings() {
        engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        viewModel.answerPermission(viewModel.permissionRequests.value.single().id, PermissionAnswer.ALLOW)
        assertEquals(SitePermissionState.ALLOW, viewModel.sitePermissions.value.single().state)

        // The engine kept nothing: Android refused the app the camera
        engine.simulateSitePermissionsChanged { removeAll { it.origin == site } }

        assertEquals(emptyList<SitePermissionEntry>(), viewModel.sitePermissions.value)
    }

    // L6. A Block from the card ends other tabs' streams on the site, as one in Settings does

    @Test
    fun blockFromTheCardReloadsOtherTabsUsingThatDevice() {
        val onceCamera = engine.sessions.getValue(tabOn("$site/stage"))
        onceCamera.simulateCaptureDevices(setOf(CAMERA))
        val onceMic = engine.sessions.getValue(tabOn("$site/audio"))
        onceMic.simulateCaptureDevices(setOf(MICROPHONE))

        engine.simulatePermissionRequest("tab-1", site, setOf(CAMERA))
        viewModel.answerPermission(viewModel.permissionRequests.value.single().id, PermissionAnswer.BLOCK)

        assertEquals(1, onceCamera.reloadCount)
        assertEquals(0, onceMic.reloadCount)
    }

    // 9. Any new document withdraws the requests of the one before

    @Test
    fun aReloadOfTheSameAddressWithdrawsTheRequest() {
        val ask = engine.simulatePermissionRequest("tab-1", site, setOf(MICROPHONE))
        engine.sessions.getValue("tab-1").simulatePageStart("$site/room")
        assertEquals(PermissionAnswer.DISMISS, ask.answer)
    }

    @Test
    fun allowThisTimeSurvivesANewDocumentOfTheSameSite() {
        engine.simulatePermissionRequest("tab-1", site, setOf(MICROPHONE))
        viewModel.answerPermission(viewModel.permissionRequests.value.single().id, PermissionAnswer.ALLOW_ONCE)

        engine.sessions.getValue("tab-1").simulatePageStart("$site/other-room")
        val again = engine.simulatePermissionRequest("tab-1", site, setOf(MICROPHONE))

        assertEquals(PermissionAnswer.ALLOW_ONCE, again.answer)
    }
}
