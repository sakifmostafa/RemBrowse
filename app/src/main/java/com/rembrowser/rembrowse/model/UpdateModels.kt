package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.data.AvailableUpdate
import java.io.File

/** Where the app's own update stands, for Settings → About and the update notice. */
sealed interface UpdateStatus {
    /** Nothing checked yet in this run. */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    /** The newest published version is the one running. */
    data object UpToDate : UpdateStatus

    data class Available(val update: AvailableUpdate) : UpdateStatus

    data class Downloading(val update: AvailableUpdate, val receivedBytes: Long) : UpdateStatus {
        val fraction: Float
            get() = if (update.size > 0) (receivedBytes.toFloat() / update.size).coerceIn(0f, 1f) else 0f
    }

    /** Downloaded and checked; the system installer takes it from here. */
    data class ReadyToInstall(val update: AvailableUpdate, val file: File) : UpdateStatus

    /** The last step went wrong. [update] is what was being fetched, if anything, for a retry. */
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateStatus
}

data class UpdatesUiState(
    val status: UpdateStatus = UpdateStatus.Idle,
    /** Look for a new version once a day without being asked. */
    val checkAutomatically: Boolean = true,
    val lastCheckedMillis: Long? = null,
    /** A version found by the automatic check, offered over the page until acted on. */
    val notice: AvailableUpdate? = null,
    /** A line for the About page: the installer needs a permission, or refused the file. */
    val message: String? = null
)
