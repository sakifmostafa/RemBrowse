package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.InMemoryUpdatePreferences
import com.rembrowser.rembrowse.data.UpdateApk
import com.rembrowser.rembrowse.data.UpdateHttpException
import com.rembrowser.rembrowse.data.UpdateIntegrityException
import com.rembrowser.rembrowse.data.UpdateManifest
import com.rembrowser.rembrowse.fakes.FakeUpdateInstaller
import com.rembrowser.rembrowse.fakes.FakeUpdateSource
import com.rembrowser.rembrowse.model.UpdateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val preferences = InMemoryUpdatePreferences()
    private val source = FakeUpdateSource()
    private val installer = FakeUpdateInstaller()
    private var now = DAY * 100

    private fun updatesTest(block: suspend TestScope.(UpdatesViewModel) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val viewModel = UpdatesViewModel(
                source = source,
                installer = installer,
                preferences = preferences,
                updatesDir = folder.root.resolve("updates"),
                currentVersionCode = 14,
                supportedAbis = listOf("x86_64", "arm64-v8a"),
                baseUrl = "https://example.test/build/",
                scope = scope,
                ioDispatcher = dispatcher,
                clock = { now }
            )
            block(viewModel)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun start_checksWhenDueAndOffersANewVersion() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.onAppStarted()
        advanceUntilIdle()

        val state = vm.uiState.value
        val available = state.status as UpdateStatus.Available
        assertEquals("x86_64", available.update.abi)
        assertEquals("https://example.test/build/RemBrowse-x86_64.apk", available.update.url)
        assertEquals(available.update, state.notice)
        assertEquals(now, preferences.lastCheckedMillis)
        assertEquals(now, state.lastCheckedMillis)
    }

    @Test
    fun start_doesNotCheckWithinADayOrWhenTurnedOff() = updatesTest { vm ->
        preferences.lastCheckedMillis = now - DAY + 60_000
        vm.onAppStarted()
        advanceUntilIdle()
        assertEquals(0, source.fetches)
        assertEquals(UpdateStatus.Idle, vm.uiState.value.status)

        preferences.lastCheckedMillis = now - DAY
        preferences.checkAutomatically = false
        vm.onAppStarted()
        advanceUntilIdle()
        assertEquals(0, source.fetches)
    }

    @Test
    fun start_removesDownloadsLeftByEarlierVersions() = updatesTest { vm ->
        preferences.checkAutomatically = false
        val old = folder.root.resolve("updates").apply { mkdirs() }.resolve("RemBrowse-14-x86_64.apk")
        old.writeText("old")
        vm.onAppStarted()
        advanceUntilIdle()
        assertFalse(old.exists())
    }

    @Test
    fun automaticCheck_doesNotOfferAVersionWhoseNoticeWasClosed() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.onAppStarted()
        advanceUntilIdle()
        vm.dismissNotice()
        assertNull(vm.uiState.value.notice)
        assertEquals(15, preferences.dismissedVersionCode)

        // The next day the same version is still on offer in About, but not over the page
        now += DAY
        vm.setCheckAutomatically(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is UpdateStatus.Available)
        assertNull(vm.uiState.value.notice)

        // A newer one is offered again
        now += DAY
        source.manifest = Result.success(manifest(versionCode = 16))
        vm.setCheckAutomatically(true)
        advanceUntilIdle()
        assertEquals(16, vm.uiState.value.notice?.versionCode)
    }

    @Test
    fun checkNow_saysWhenUpToDateAndNeverShowsTheNotice() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 14))
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateStatus.UpToDate, vm.uiState.value.status)

        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is UpdateStatus.Available)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun failedChecks_areReportedOnlyWhenAskedFor() = updatesTest { vm ->
        source.manifest = Result.failure(UpdateHttpException(404))
        vm.onAppStarted()
        advanceUntilIdle()
        assertEquals(UpdateStatus.Idle, vm.uiState.value.status)
        assertNull(preferences.lastCheckedMillis)

        vm.checkNow()
        advanceUntilIdle()
        val failed = vm.uiState.value.status as UpdateStatus.Failed
        assertEquals("Update information isn't available right now. Try again later.", failed.message)
        assertNull(failed.update)
    }

    @Test
    fun download_checksTheFileAndOpensTheInstaller() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        vm.downloadAndInstall()
        advanceUntilIdle()

        val ready = vm.uiState.value.status as UpdateStatus.ReadyToInstall
        assertEquals(folder.root.resolve("updates/RemBrowse-15-x86_64.apk"), ready.file)
        assertEquals(listOf(ready.file), installer.installed)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun download_showsProgressAndCanBeCancelled() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        source.beforeFinishing = gate
        vm.downloadAndInstall()
        advanceUntilIdle()

        val downloading = vm.uiState.value.status as UpdateStatus.Downloading
        assertEquals(SIZE / 2, downloading.receivedBytes)
        assertEquals(0.5f, downloading.fraction, 0.001f)

        vm.cancelDownload()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is UpdateStatus.Available)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun aDamagedDownload_canBeTriedAgain() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        source.downloadError = UpdateIntegrityException("The download is damaged")
        vm.downloadAndInstall()
        advanceUntilIdle()

        val failed = vm.uiState.value.status as UpdateStatus.Failed
        assertEquals("The download is damaged, so it wasn't installed. Try again.", failed.message)
        assertNotNull(failed.update)

        source.downloadError = null
        vm.downloadAndInstall()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is UpdateStatus.ReadyToInstall)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun install_waitsForThePermissionAndFinishesWhenTheUserComesBack() = updatesTest { vm ->
        installer.allowed = false
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        vm.downloadAndInstall()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.message)
        assertTrue(installer.installed.isEmpty())

        // Back without allowing it: nothing reopens the settings screen
        vm.onResumed()
        advanceUntilIdle()
        assertTrue(installer.installed.isEmpty())
        assertEquals(1, installer.attempts)

        // Allowed, then Install again
        installer.allowed = true
        vm.install()
        advanceUntilIdle()
        assertEquals(1, installer.installed.size)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun install_continuesOnReturnOnceAllowed() = updatesTest { vm ->
        installer.allowed = false
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        vm.downloadAndInstall()
        advanceUntilIdle()

        installer.allowed = true
        vm.onResumed()
        advanceUntilIdle()
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun aRejectedUpdate_isDeletedAndExplained() = updatesTest { vm ->
        installer.rejection = "This update is signed with a different key"
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.checkNow()
        advanceUntilIdle()
        vm.downloadAndInstall()
        advanceUntilIdle()

        val failed = vm.uiState.value.status as UpdateStatus.Failed
        assertEquals("This update is signed with a different key", failed.message)
        assertNull(failed.update)
        assertFalse(folder.root.resolve("updates/RemBrowse-15-x86_64.apk").exists())
    }

    @Test
    fun acceptingTheNotice_startsTheDownload() = updatesTest { vm ->
        source.manifest = Result.success(manifest(versionCode = 15))
        vm.onAppStarted()
        advanceUntilIdle()
        vm.acceptNotice()
        advanceUntilIdle()
        assertNull(vm.uiState.value.notice)
        assertEquals(1, installer.installed.size)
        // Taking up the offer is not closing it
        assertEquals(0, preferences.dismissedVersionCode)
    }

    private fun manifest(versionCode: Int) = UpdateManifest(
        versionName = "0.${versionCode - 2}",
        versionCode = versionCode,
        apks = mapOf(
            "arm64-v8a" to UpdateApk("RemBrowse-arm64-v8a.apk", SIZE, "b".repeat(64)),
            "x86_64" to UpdateApk("RemBrowse-x86_64.apk", SIZE, "a".repeat(64))
        )
    )

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val SIZE = 1_000_000L
    }
}
