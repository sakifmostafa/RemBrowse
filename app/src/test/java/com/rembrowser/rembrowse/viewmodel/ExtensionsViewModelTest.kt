package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.engine.ExtensionInstallCancelledException
import com.rembrowser.rembrowse.fakes.FakeAmoApiClient
import com.rembrowser.rembrowse.fakes.FakeExtensionManager
import com.rembrowser.rembrowse.model.AmoAddon
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
}
