package com.rembrowser.rembrowse.fakes

import com.rembrowser.rembrowse.data.AvailableUpdate
import com.rembrowser.rembrowse.data.InstallOutcome
import com.rembrowser.rembrowse.data.UpdateInstaller
import com.rembrowser.rembrowse.data.UpdateManifest
import com.rembrowser.rembrowse.data.UpdateSource
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Serves [manifest]; a download reports half, waits for [beforeFinishing] if set, then writes the file. */
class FakeUpdateSource : UpdateSource {
    var manifest: Result<UpdateManifest> = Result.failure(IllegalStateException("no manifest"))
    var downloadError: Throwable? = null
    var beforeFinishing: CompletableDeferred<Unit>? = null
    var fetches = 0

    override suspend fun fetchManifest(): Result<UpdateManifest> {
        fetches++
        return manifest
    }

    override suspend fun download(update: AvailableUpdate, target: File, onProgress: (Long) -> Unit): Result<File> {
        downloadError?.let { return Result.failure(it) }
        onProgress(update.size / 2)
        beforeFinishing?.await()
        onProgress(update.size)
        target.parentFile?.mkdirs()
        target.writeText("apk")
        return Result.success(target)
    }
}

/** Installs whatever it is given, unless told the permission is missing or to refuse. */
class FakeUpdateInstaller : UpdateInstaller {
    var allowed = true
    var rejection: String? = null
    var attempts = 0
    val installed = mutableListOf<File>()

    override fun canInstallPackages(): Boolean = allowed

    override fun install(apk: File, update: AvailableUpdate): InstallOutcome {
        attempts++
        rejection?.let { return InstallOutcome.Rejected(it) }
        if (!allowed) return InstallOutcome.PermissionNeeded
        installed += apk
        return InstallOutcome.Started
    }
}
