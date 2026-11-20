package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.AvailableUpdate
import com.rembrowser.rembrowse.data.InstallOutcome
import com.rembrowser.rembrowse.data.UpdateHttpException
import com.rembrowser.rembrowse.data.UpdateInstaller
import com.rembrowser.rembrowse.data.UpdateIntegrityException
import com.rembrowser.rembrowse.data.UpdateManifests
import com.rembrowser.rembrowse.data.UpdatePreferences
import com.rembrowser.rembrowse.data.UpdateSource
import com.rembrowser.rembrowse.data.UnreadableUpdateException
import com.rembrowser.rembrowse.model.UpdateStatus
import com.rembrowser.rembrowse.model.UpdatesUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds, downloads and installs new versions of RemBrowse itself.
 *
 * Versions are published to the repository with a small `update.json` beside the APKs. Once a
 * day (if allowed) and whenever asked, it reads that file; a newer version is downloaded only
 * when the user chooses to, checked against the size and SHA-256 published with it, and handed
 * to the system installer, which asks the user to confirm.
 */
class UpdatesViewModel(
    private val source: UpdateSource,
    private val installer: UpdateInstaller,
    private val preferences: UpdatePreferences,
    /** Holds the downloaded APK. Emptied on start, so an installed update leaves nothing behind. */
    private val updatesDir: File,
    private val currentVersionCode: Int,
    /** The device's processor types, most preferred first. */
    private val supportedAbis: List<String>,
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _uiState = MutableStateFlow(
        UpdatesUiState(
            checkAutomatically = preferences.checkAutomatically,
            lastCheckedMillis = preferences.lastCheckedMillis
        )
    )
    val uiState: StateFlow<UpdatesUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    /** The installer sent the user to allow installing apps; finish when they come back. */
    private var installWhenAllowed = false

    /** Clears old downloads, then checks for a new version if a day has passed since the last check. */
    fun onAppStarted() {
        if (job?.isActive == true) return
        job = scope.launch {
            withContext(ioDispatcher) { clearDownloads() }
            if (isCheckDue()) runCheck(automatic = true)
        }
    }

    /** Called whenever the window comes back, as it does after the screen that allows installs. */
    fun onResumed() {
        if (!installWhenAllowed) return
        installWhenAllowed = false
        if (installer.canInstallPackages()) {
            install()
        }
    }

    fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch { runCheck(automatic = false) }
    }

    fun setCheckAutomatically(enabled: Boolean) {
        preferences.checkAutomatically = enabled
        _uiState.update { it.copy(checkAutomatically = enabled) }
        if (enabled && isCheckDue() && job?.isActive != true) {
            job = scope.launch { runCheck(automatic = true) }
        }
    }

    /** Downloads the version on offer and, once it checks out, opens the installer. */
    fun downloadAndInstall() {
        val update = when (val status = _uiState.value.status) {
            is UpdateStatus.Available -> status.update
            is UpdateStatus.Failed -> status.update ?: return
            else -> return
        }
        if (job?.isActive == true) return
        // A cancelled download may still be cleaning up its partial file
        val previous = job
        _uiState.update { it.copy(status = UpdateStatus.Downloading(update, 0L), notice = null, message = null) }
        job = scope.launch {
            previous?.join()
            val target = File(updatesDir, "RemBrowse-${update.versionCode}-${update.abi}.apk")
            withContext(ioDispatcher) { clearDownloads() }
            val result = source.download(update, target) { received -> onProgress(update, received) }
            result.fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(status = UpdateStatus.ReadyToInstall(update, file)) }
                    runInstall()
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(status = UpdateStatus.Failed(downloadFailure(error), update)) }
                }
            )
        }
    }

    fun cancelDownload() {
        val status = _uiState.value.status as? UpdateStatus.Downloading ?: return
        job?.cancel()
        _uiState.update { it.copy(status = UpdateStatus.Available(status.update)) }
    }

    /** Opens the installer again for an update already downloaded. */
    fun install() {
        if (_uiState.value.status !is UpdateStatus.ReadyToInstall || job?.isActive == true) return
        job = scope.launch { runInstall() }
    }

    /** Takes up the offer from the notice: the download starts straight away. */
    fun acceptNotice() {
        _uiState.update { it.copy(notice = null) }
        downloadAndInstall()
    }

    /** Closes the notice. The same version is not offered again unasked; About still shows it. */
    fun dismissNotice() {
        val notice = _uiState.value.notice ?: return
        preferences.dismissedVersionCode = notice.versionCode
        _uiState.update { it.copy(notice = null) }
    }

    private suspend fun runCheck(automatic: Boolean) {
        val before = _uiState.value.status
        _uiState.update { it.copy(status = UpdateStatus.Checking, message = null) }
        val result = source.fetchManifest()
        result.fold(
            onSuccess = { manifest ->
                val now = clock()
                preferences.lastCheckedMillis = now
                val update = UpdateManifests.updateFor(manifest, currentVersionCode, supportedAbis, baseUrl)
                _uiState.update { state ->
                    state.copy(
                        status = if (update == null) UpdateStatus.UpToDate else UpdateStatus.Available(update),
                        lastCheckedMillis = now,
                        notice = when {
                            update == null -> null
                            automatic && update.versionCode != preferences.dismissedVersionCode -> update
                            else -> state.notice
                        }
                    )
                }
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                // An automatic check that fails says nothing; the next launch tries again
                _uiState.update {
                    it.copy(status = if (automatic) before else UpdateStatus.Failed(checkFailure(error)))
                }
            }
        )
    }

    private suspend fun runInstall() {
        val ready = _uiState.value.status as? UpdateStatus.ReadyToInstall ?: return
        when (val outcome = withContext(ioDispatcher) { installer.install(ready.file, ready.update) }) {
            InstallOutcome.Started -> _uiState.update { it.copy(message = null) }
            InstallOutcome.PermissionNeeded -> {
                installWhenAllowed = true
                _uiState.update {
                    it.copy(message = "Allow RemBrowse to install apps, then come back here to finish updating.")
                }
            }
            is InstallOutcome.Rejected -> {
                withContext(ioDispatcher) { ready.file.delete() }
                _uiState.update { it.copy(status = UpdateStatus.Failed(outcome.message), message = null) }
            }
        }
    }

    /** Progress in steps of a percent at most, so a large download doesn't flood the screen with updates. */
    private fun onProgress(update: AvailableUpdate, received: Long) {
        _uiState.update { state ->
            val downloading = state.status as? UpdateStatus.Downloading
            if (downloading == null || downloading.update != update) return@update state
            val step = (update.size / 100).coerceAtLeast(1L)
            if (received != update.size && received - downloading.receivedBytes < step) return@update state
            state.copy(status = UpdateStatus.Downloading(update, received))
        }
    }

    private fun isCheckDue(): Boolean {
        if (!preferences.checkAutomatically) return false
        val last = preferences.lastCheckedMillis ?: return true
        val now = clock()
        // A clock set back would otherwise hold off checks until it caught up
        return now < last || now - last >= CHECK_INTERVAL_MILLIS
    }

    private fun clearDownloads() {
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    private fun checkFailure(error: Throwable): String = when {
        error is UpdateHttpException && error.code == 404 ->
            "Update information isn't available right now. Try again later."
        error is UpdateHttpException -> "The update server had a problem (error ${error.code}). Try again later."
        error is UnreadableUpdateException -> "The update information couldn't be read."
        else -> "Couldn't reach the update server. Check your connection and try again."
    }

    private fun downloadFailure(error: Throwable): String = when (error) {
        is UpdateIntegrityException -> "${error.message}, so it wasn't installed. Try again."
        is UpdateHttpException -> "Couldn't download the update (error ${error.code}). Try again later."
        else -> "The download stopped. Check your connection and try again."
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
