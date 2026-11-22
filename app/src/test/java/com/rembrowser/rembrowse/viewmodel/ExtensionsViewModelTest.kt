package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.engine.ExtensionInstallCancelledException
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeDefaultExtensionStore
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ArchivedAdBlocker
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.ExtensionsTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModelTest {

    private lateinit var extensionManager: FakeExtensionManager
    private lateinit var amoApiClient: FakeAmoApiClient

    private val ublockAddon = AmoAddon(
        id = "uBlock0@raymondhill.net",
        slug = "ublock-origin",
        name = "uBlock Origin",
        summary = "Efficient blocker",
        iconUrl = null,
        author = "Raymond Hill",
        rating = 4.8,
        weeklyDownloads = 500000L,
        version = "1.61.2",
        xpiUrl = "https://addons.mozilla.org/android/downloads/file/123/ublock.xpi"
    )

    @Before
    fun setUp() {
        extensionManager = FakeExtensionManager()
        amoApiClient = FakeAmoApiClient().apply {
            featuredResult = Result.success(listOf(ublockAddon))
            searchResult = Result.success(listOf(ublockAddon))
        }
    }

    @Test
    fun selectDiscoverTab_loadsFeaturedAmoAddons() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.selectTab(ExtensionsTab.DISCOVER_AMO)

            val state = viewModel.uiState.value
            assertEquals(ExtensionsTab.DISCOVER_AMO, state.selectedTab)
            assertEquals(1, state.catalogAddons.size)
            assertEquals("uBlock Origin", state.catalogAddons.first().name)
            assertFalse(state.isLoadingCatalog)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun submitSearch_queriesAmoApiAndUpdatesCatalog() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.onSearchQueryChange("  ublock  ")
            viewModel.submitSearch()

            assertEquals(listOf("ublock"), amoApiClient.searchedQueries)
            assertEquals(1, viewModel.uiState.value.catalogAddons.size)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installAddon_installsXpiAndUpdatesInstalledList() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.installAddon(ublockAddon)

            assertEquals(listOf(ublockAddon.xpiUrl), extensionManager.installedUris)
            assertEquals(1, viewModel.uiState.value.installedExtensions.size)
            assertNotNull(viewModel.uiState.value.statusMessage)
            assertNull(viewModel.uiState.value.errorMessage)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installFromUri_rejectsInvalidUriWithErrorMessage() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.installFromUri("javascript:alert(1)")

            assertTrue(extensionManager.installedUris.isEmpty())
            assertNotNull(viewModel.uiState.value.errorMessage)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun enableDisableAndUninstall_delegateToExtensionManager() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.installAddon(ublockAddon)
            val extId = viewModel.uiState.value.installedExtensions.first().id

            viewModel.setExtensionEnabled(extId, false)
            assertFalse(viewModel.uiState.value.installedExtensions.first().enabled)

            viewModel.uninstallExtension(extId)
            assertTrue(viewModel.uiState.value.installedExtensions.isEmpty())
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installFromUri_rejectsCleartextHttpLinks() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            var finished = false
            viewModel.installFromUri("http://example.com/extension.xpi") { finished = true }

            assertTrue("Code must not be fetched over plain HTTP", extensionManager.installedUris.isEmpty())
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertTrue("The caller still gets to clean up", finished)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun install_cancelledAtThePermissionPromptIsNotReportedAsAnError() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            extensionManager.nextInstallResult = Result.failure(ExtensionInstallCancelledException())
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.installAddon(ublockAddon)

            val state = viewModel.uiState.value
            assertFalse(state.isInstalling)
            assertNull("Choosing Cancel is not a failure", state.errorMessage)
            assertNull(state.statusMessage)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun install_failureShowsTheEnginesExplanation() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            extensionManager.nextInstallResult =
                Result.failure(IllegalStateException("This extension isn't signed by Mozilla, so it can't be installed."))
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            viewModel.installAddon(ublockAddon)

            assertEquals(
                "This extension isn't signed by Mozilla, so it can't be installed.",
                viewModel.uiState.value.errorMessage
            )
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun install_onlyOneRunsAtATimeAndCleanupAlwaysHappens() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            extensionManager.installGate = gate
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)
            val finished = mutableListOf<String>()

            viewModel.installFromUri("https://example.com/first.xpi") { finished += "first" }
            assertTrue(viewModel.uiState.value.isInstalling)
            viewModel.installFromUri("https://example.com/second.xpi") { finished += "second" }

            assertEquals("The second request must not start", 1, extensionManager.installedUris.size)
            assertEquals(listOf("second"), finished)

            gate.complete(Unit)
            assertFalse(viewModel.uiState.value.isInstalling)
            assertEquals(listOf("second", "first"), finished)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun catalog_failureIsShownInlineAndASlowRequestCannotOverwriteANewerOne() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope)

            amoApiClient.featuredResult = Result.failure(java.io.IOException("You appear to be offline."))
            viewModel.loadFeaturedAddons()
            assertEquals("You appear to be offline.", viewModel.uiState.value.catalogError)
            assertFalse(viewModel.uiState.value.isLoadingCatalog)

            // A slow "featured" request is still in flight when the user searches
            val gate = CompletableDeferred<Unit>()
            amoApiClient.featuredGate = gate
            amoApiClient.featuredResult = Result.success(emptyList())
            viewModel.loadFeaturedAddons()
            viewModel.onSearchQueryChange("ublock")
            viewModel.submitSearch()
            assertEquals(1, viewModel.uiState.value.catalogAddons.size)

            gate.complete(Unit)
            assertEquals("The stale response is discarded", 1, viewModel.uiState.value.catalogAddons.size)
            assertNull(viewModel.uiState.value.catalogError)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun firstLaunch_installsTheDefaultAdBlockerWithoutAPromptAndOnlyOnce() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore()
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)

            assertEquals(1, extensionManager.trustedInstalls.size)
            val (url, id) = extensionManager.trustedInstalls.single()
            assertEquals(DefaultAdBlocker.ID, id)
            assertEquals("The catalog lists uBlock Origin, so its package is used", ublockAddon.xpiUrl, url)
            assertTrue(viewModel.uiState.value.isAdBlockerOn)
            assertNull("No permission prompt for the browser's own default", viewModel.uiState.value.pendingPrompt)
            assertTrue(store.defaultBlockerProvisioned)
            assertTrue("A fresh install has nothing to move", store.blockerMovedToUblock)

            // The next launch does not install it again
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            assertEquals(1, extensionManager.trustedInstalls.size)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun defaultAdBlocker_fallsBackToTheStableLinkWhenTheCatalogHasNoMatch() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            amoApiClient.searchResult = Result.success(emptyList())
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, FakeDefaultExtensionStore())

            assertEquals(DefaultAdBlocker.FALLBACK_URL, extensionManager.trustedInstalls.single().first)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun defaultAdBlocker_usesTheCatalogPackageWhenAvailable() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val catalogUrl = "https://addons.mozilla.org/android/downloads/file/1/ublock-new.xpi"
            amoApiClient.searchResult = Result.success(
                listOf(
                    ublockAddon.copy(id = ArchivedAdBlocker.ID, name = ArchivedAdBlocker.NAME),
                    ublockAddon.copy(id = DefaultAdBlocker.ID, name = DefaultAdBlocker.NAME, xpiUrl = catalogUrl)
                )
            )
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, FakeDefaultExtensionStore())

            assertEquals(catalogUrl, extensionManager.trustedInstalls.single().first)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun defaultAdBlocker_failedFirstInstallIsSilentAndRetriedNextLaunch() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore()
            extensionManager.nextTrustedInstallResult = Result.failure(java.io.IOException("offline"))
            val offline = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)

            assertFalse(offline.uiState.value.isAdBlockerOn)
            assertFalse(offline.uiState.value.isInstallingAdBlocker)
            assertNull("Nobody asked for it yet, so no error is shown", offline.uiState.value.adBlockerError)
            assertNull(offline.uiState.value.errorMessage)
            assertFalse(store.defaultBlockerProvisioned)

            extensionManager.nextTrustedInstallResult = null
            val online = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            assertTrue(online.uiState.value.isAdBlockerOn)
            assertTrue(store.defaultBlockerProvisioned)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun defaultAdBlocker_removedByTheUserIsNotReinstalledButTheSwitchBringsItBack() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore()
            val first = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            first.uninstallExtension(DefaultAdBlocker.ID)
            assertNull(first.uiState.value.adBlocker)

            val relaunched = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            assertNull("Removing it must stick", relaunched.uiState.value.adBlocker)
            assertEquals(1, extensionManager.trustedInstalls.size)

            relaunched.setAdBlockerEnabled(true)
            assertTrue(relaunched.uiState.value.isAdBlockerOn)
            assertEquals(2, extensionManager.trustedInstalls.size)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun adBlockerSwitch_turnsTheExtensionOffAndOnAndReportsAFailedDownload() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, FakeDefaultExtensionStore())

            viewModel.setAdBlockerEnabled(false)
            assertFalse(viewModel.uiState.value.isAdBlockerOn)
            assertNotNull("Switched off, not removed", viewModel.uiState.value.adBlocker)

            viewModel.setAdBlockerEnabled(true)
            assertTrue(viewModel.uiState.value.isAdBlockerOn)

            // Removed, then asked for again while offline: this time the user is told
            viewModel.uninstallExtension(DefaultAdBlocker.ID)
            extensionManager.nextTrustedInstallResult = Result.failure(java.io.IOException("offline"))
            viewModel.setAdBlockerEnabled(true)
            assertFalse(viewModel.uiState.value.isAdBlockerOn)
            assertNotNull(viewModel.uiState.value.adBlockerError)
        } finally {
            vmScope.cancel()
        }
    }

    private fun extension(id: String, enabled: Boolean = true) = InstalledExtension(
        id = id, name = id, version = "1.0", description = "", enabled = enabled, hasBrowserAction = true
    )

    /** A copy set up when AdGuard was the default: provisioned, not yet moved. */
    private fun oldCopyStore() = FakeDefaultExtensionStore(defaultBlockerProvisioned = true, blockerMovedToUblock = false)

    private fun launchOldCopy(
        vmScope: CoroutineScope,
        store: FakeDefaultExtensionStore,
        vararg installed: InstalledExtension
    ): ExtensionsViewModel {
        extensionManager.installedExtensions.value = installed.toList()
        return ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
    }

    private fun enabledOf(id: String) = extensionManager.installedExtensions.value.single { it.id == id }.enabled

    @Test
    fun moveToUblock_withAdGuardOn_installsUblockThenSwitchesAdGuardOffWithoutRemovingIt() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            amoApiClient.searchResult = Result.success(emptyList())
            val viewModel = launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID))

            val (url, id) = extensionManager.trustedInstalls.single()
            assertEquals(DefaultAdBlocker.ID, id)
            assertEquals(DefaultAdBlocker.FALLBACK_URL, url)
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertFalse("Archived, not running", enabledOf(ArchivedAdBlocker.ID))
            assertNotNull("Still installed", extensionManager.installedExtensions.value.firstOrNull { it.id == ArchivedAdBlocker.ID })
            assertTrue(store.blockerMovedToUblock)
            assertTrue(viewModel.uiState.value.isAdBlockerOn)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_withAdGuardSwitchedOff_installsNothingAndMarksItDone() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID, enabled = false))

            assertTrue(extensionManager.trustedInstalls.isEmpty())
            assertFalse(enabledOf(ArchivedAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_withAdGuardRemoved_installsNothingAndMarksItDone() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            val viewModel = launchOldCopy(vmScope, store)

            assertTrue(extensionManager.trustedInstalls.isEmpty())
            assertNull(viewModel.uiState.value.adBlocker)
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_withUblockAlreadyInstalled_switchesAdGuardOffAndInstallsNothing() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID), extension(DefaultAdBlocker.ID))

            assertTrue(extensionManager.trustedInstalls.isEmpty())
            assertFalse(enabledOf(ArchivedAdBlocker.ID))
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_withUblockInstalledButOff_leavesBothAsTheyAre() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID), extension(DefaultAdBlocker.ID, enabled = false))

            assertTrue(extensionManager.trustedInstalls.isEmpty())
            assertTrue("The user chose no blocker; AdGuard is not touched", enabledOf(ArchivedAdBlocker.ID))
            assertFalse(enabledOf(DefaultAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_failedDownloadKeepsAdGuardOnAndRetriesNextLaunch() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            extensionManager.nextTrustedInstallResult = Result.failure(java.io.IOException("offline"))
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID))

            assertEquals(1, extensionManager.trustedInstalls.size)
            assertTrue("AdGuard stays on until uBlock Origin is in place", enabledOf(ArchivedAdBlocker.ID))
            assertFalse(store.blockerMovedToUblock)

            extensionManager.nextTrustedInstallResult = null
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            assertEquals(2, extensionManager.trustedInstalls.size)
            assertFalse(enabledOf(ArchivedAdBlocker.ID))
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_runsOnlyOnce() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID))
            assertEquals(1, extensionManager.trustedInstalls.size)

            // The user switches AdGuard back on and removes uBlock Origin: the next launch leaves both alone
            extensionManager.installedExtensions.value =
                listOf(extension(ArchivedAdBlocker.ID, enabled = true))
            val relaunched = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)

            assertEquals(1, extensionManager.trustedInstalls.size)
            assertTrue(enabledOf(ArchivedAdBlocker.ID))
            assertNull(relaunched.uiState.value.adBlocker)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_usesTheRefreshedList_notTheStateFlowCopyThatArrivesLater() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            amoApiClient.searchResult = Result.success(emptyList())
            // The flow still shows nothing when refreshInstalled returns, as on a device
            extensionManager.refreshResult = Result.success(listOf(extension(ArchivedAdBlocker.ID)))
            extensionManager.installedExtensions.value = emptyList()
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)

            assertEquals(DefaultAdBlocker.ID, extensionManager.trustedInstalls.single().second)
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_afterAFailedRefresh_installsNothingAndTriesAgainNextLaunch() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            extensionManager.refreshResult = Result.failure(java.io.IOException("cannot list"))
            extensionManager.installedExtensions.value = listOf(extension(ArchivedAdBlocker.ID))
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)

            assertTrue(extensionManager.trustedInstalls.isEmpty())
            assertFalse(store.blockerMovedToUblock)
            assertTrue(enabledOf(ArchivedAdBlocker.ID))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun settingsSwitch_withAdGuardOnAndTheMoveUndone_installsUblockAndSwitchesAdGuardOff() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore(defaultBlockerProvisioned = true, blockerMovedToUblock = true)
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            // The flag is cleared again after the launch, with AdGuard on and uBlock Origin absent
            extensionManager.installedExtensions.value = listOf(extension(ArchivedAdBlocker.ID))
            store.blockerMovedToUblock = false

            viewModel.setAdBlockerEnabled(true)

            assertEquals(DefaultAdBlocker.ID, extensionManager.trustedInstalls.single().second)
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertFalse(enabledOf(ArchivedAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installingUblock_whenSwitchingAdGuardOffFails_leavesTheMoveUnfinished() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            extensionManager.setEnabledFailureFor += ArchivedAdBlocker.ID
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID))

            assertEquals(1, extensionManager.trustedInstalls.size)
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertTrue(enabledOf(ArchivedAdBlocker.ID))
            assertFalse("Not done until AdGuard is off", store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installingUblock_afterTheMoveIsDone_leavesAdGuardAloneEvenIfTheUserSwitchedItOn() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore(defaultBlockerProvisioned = true, blockerMovedToUblock = true)
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            extensionManager.installedExtensions.value = listOf(extension(ArchivedAdBlocker.ID))

            viewModel.setAdBlockerEnabled(true)

            assertEquals(1, extensionManager.trustedInstalls.size)
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertTrue("The user's choice", enabledOf(ArchivedAdBlocker.ID))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun moveToUblock_withBothInstalledAndOn_setsTheFlagOnlyOnceAdGuardIsOff() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = oldCopyStore()
            extensionManager.setEnabledFailureFor += ArchivedAdBlocker.ID
            launchOldCopy(vmScope, store, extension(ArchivedAdBlocker.ID), extension(DefaultAdBlocker.ID))
            assertTrue(enabledOf(ArchivedAdBlocker.ID))
            assertFalse(store.blockerMovedToUblock)

            extensionManager.setEnabledFailureFor.clear()
            ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            assertFalse(enabledOf(ArchivedAdBlocker.ID))
            assertTrue(store.blockerMovedToUblock)
            assertTrue(extensionManager.trustedInstalls.isEmpty())
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun installingUblock_whenTheRefreshAfterwardsFails_leavesTheFlagUnsetAndAdGuardAlone() = runTest {
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = FakeDefaultExtensionStore(defaultBlockerProvisioned = true, blockerMovedToUblock = true)
            val viewModel = ExtensionsViewModel(extensionManager, amoApiClient, vmScope, store)
            extensionManager.installedExtensions.value = listOf(extension(ArchivedAdBlocker.ID))
            store.blockerMovedToUblock = false
            extensionManager.refreshResult = Result.failure(java.io.IOException("cannot list"))

            viewModel.setAdBlockerEnabled(true)

            assertEquals(1, extensionManager.trustedInstalls.size)
            assertTrue(enabledOf(DefaultAdBlocker.ID))
            assertTrue("A failed list does not pass for AdGuard being off", enabledOf(ArchivedAdBlocker.ID))
            assertFalse(store.blockerMovedToUblock)
        } finally {
            vmScope.cancel()
        }
    }
}
