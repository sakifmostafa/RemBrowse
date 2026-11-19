package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.DownloadHistoryStore
import com.rembrowser.rembrowse.data.DownloadStorage
import com.rembrowser.rembrowse.data.DownloadTarget
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadNotice
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.DownloadsUiState
import com.rembrowser.rembrowse.util.UrlResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves the files that pages offer and keeps the downloads list.
 *
 * A request is either started straight away or held until the user answers, depending on
 * [askBeforeDownloading]. The file is copied from the engine's response body on
 * [ioDispatcher]; everything observable goes through [uiState].
 */
class DownloadsViewModel(
    private val storage: DownloadStorage,
    private val history: DownloadHistoryStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val askBeforeDownloading: () -> Boolean = { true },
    /** Called on [scope] once a request is over, however it ended (saved, failed, declined). */
    private val onRequestSettled: (DownloadRequest) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private class Running(val request: DownloadRequest) {
        @Volatile
        var cancelled = false
    }

    private val _uiState = MutableStateFlow(DownloadsUiState(items = history.load()))
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val running = ConcurrentHashMap<String, Running>()

    /** Where files are saved, for the settings page. */
    val locationLabel: String
        get() = storage.locationLabel

    /** Whether the system's own downloads screen would show the files saved here. */
    val canShowFolder: Boolean
        get() = storage.isSharedFolder

    fun onDownloadRequested(request: DownloadRequest) {
        if (request.skipConfirmation || !askBeforeDownloading()) {
            start(request)
            return
        }
        // A page can ask for any number of files. Past a handful of unanswered questions the
        // rest are turned down rather than queued into an endless run of dialogs.
        if (_uiState.value.pendingRequests.size >= MAX_PENDING_REQUESTS) {
            decline(request)
            return
        }
        _uiState.update { it.copy(pendingRequests = it.pendingRequests + request) }
    }

    /** Answers the request on screen: save it, or drop it. */
    fun answerPendingRequest(accept: Boolean) {
        val request = _uiState.value.pendingRequest ?: return
        _uiState.update { state -> state.copy(pendingRequests = state.pendingRequests.filterNot { it === request }) }
        if (accept) start(request) else decline(request)
    }

    /** Stops a running download and removes what was written so far. */
    fun cancel(id: String) {
        val download = running[id] ?: return
        download.cancelled = true
        _uiState.update { state ->
            state.copy(
                items = state.items.map { if (it.id == id) it.copy(status = DownloadStatus.CANCELLED) else it },
                notice = state.notice?.takeUnless { it.itemId == id }
            )
        }
        // Wakes a read that is waiting on the network. Not on the caller's thread: closing can
        // itself wait for that read to let go of the stream.
        scope.launch(ioDispatcher) { runCatching { download.request.body.close() } }
    }

    /** Takes a finished download off the list. The file itself is left alone. */
    fun remove(id: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.filterNot { it.id == id && !it.isInProgress },
                notice = state.notice?.takeUnless { it.itemId == id }
            )
        }
        persist()
    }

    /** Clears every finished download from the list. Files are left alone. */
    fun clearFinished() {
        _uiState.update { state -> state.copy(items = state.items.filter { it.isInProgress }, notice = null) }
        persist()
    }

    /**
     * Opens a finished download with [launch], which returns false when no app can show it.
     * The file is checked first, off the main thread: it may have been moved or deleted.
     */
    fun open(id: String, launch: (DownloadItem) -> Boolean) {
        val item = _uiState.value.items.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED } ?: return
        val uri = item.contentUri ?: return
        scope.launch {
            val stillThere = withContext(ioDispatcher) { storage.exists(uri) }
            when {
                !stillThere -> reportMessage("${item.fileName} has been moved or deleted.")
                !launch(item) -> reportMessage("No app on this device can open ${item.fileName}.")
            }
        }
    }

    fun reportMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** Stops everything in flight. Called when the browser window goes away. */
    fun shutdown() {
        running.values.forEach { download ->
            download.cancelled = true
            runCatching { download.request.body.close() }
        }
        _uiState.value.pendingRequests.forEach { runCatching { it.body.close() } }
    }

    private fun decline(request: DownloadRequest) {
        scope.launch(ioDispatcher) {
            runCatching { request.body.close() }
            settle(request)
        }
    }

    private fun start(request: DownloadRequest) {
        val id = idGenerator()
        val item = DownloadItem(
            id = id,
            fileName = request.fileName,
            // A data: address is the file itself and can run to megabytes. Only its scheme is
            // kept: the list is saved to disk and has no use for the rest.
            sourceUrl = if (UrlResolver.isWebUrl(request.url)) request.url else request.url.substringBefore(':') + ":",
            mimeType = request.mimeType,
            totalBytes = request.contentLength.takeIf { it > 0 } ?: DownloadItem.UNKNOWN_SIZE,
            startedAtMillis = clock()
        )
        val download = Running(request)
        running[id] = download
        _uiState.update { state ->
            state.copy(
                items = (listOf(item) + state.items).trimmed(),
                notice = DownloadNotice(id, item.fileName, DownloadStatus.IN_PROGRESS)
            )
        }
        scope.launch(ioDispatcher) {
            copyToStorage(id, download)
            settle(request)
        }
    }

    /** Blocking: reads the response body to the end and publishes how it went. */
    private fun copyToStorage(id: String, download: Running) {
        val request = download.request
        var target: DownloadTarget? = null
        var received = 0L
        var savedAt: String? = null
        try {
            if (!download.cancelled) {
                val created = storage.create(request.fileName, request.mimeType)
                target = created
                updateItem(id) { it.copy(fileName = created.fileName) }
                created.openOutput().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var reported = 0L
                    while (!download.cancelled) {
                        val count = request.body.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        // Progress is published in steps; a state update per chunk would redraw
                        // the list thousands of times for a large file.
                        if (received - reported >= PROGRESS_STEP_BYTES) {
                            reported = received
                            updateItem(id) { it.copy(receivedBytes = received) }
                        }
                    }
                }
                if (!download.cancelled) savedAt = created.commit()
            }
        } catch (_: Exception) {
            // Reported below as failed, or as cancelled if that is why the stream closed
            savedAt = null
        } finally {
            runCatching { request.body.close() }
            running.remove(id)
        }

        val uri = savedAt
        val status = when {
            uri != null -> DownloadStatus.COMPLETED
            download.cancelled -> DownloadStatus.CANCELLED
            else -> DownloadStatus.FAILED
        }
        if (uri == null) target?.let { runCatching { it.discard() } }

        _uiState.update { state ->
            val finished = state.items.map { item ->
                when {
                    item.id != id -> item
                    uri != null -> item.copy(
                        status = status,
                        contentUri = uri,
                        receivedBytes = received,
                        totalBytes = received
                    )
                    else -> item.copy(status = status, receivedBytes = received)
                }
            }
            val fileName = finished.firstOrNull { it.id == id }?.fileName ?: request.fileName
            state.copy(
                items = finished,
                // Cancelling was the user's own doing and needs no announcement
                notice = if (status == DownloadStatus.CANCELLED) {
                    state.notice?.takeUnless { it.itemId == id }
                } else {
                    DownloadNotice(id, fileName, status)
                }
            )
        }
        persist()
    }

    private fun settle(request: DownloadRequest) {
        scope.launch { onRequestSettled(request) }
    }

    private fun updateItem(id: String, change: (DownloadItem) -> DownloadItem) {
        _uiState.update { state -> state.copy(items = state.items.map { if (it.id == id) change(it) else it }) }
    }

    private fun persist() {
        history.save(_uiState.value.items.filterNot { it.isInProgress })
    }

    /** Keeps the list bounded by dropping the oldest finished entries. */
    private fun List<DownloadItem>.trimmed(): List<DownloadItem> {
        if (size <= MAX_ITEMS) return this
        var surplus = size - MAX_ITEMS
        return asReversed().filterNot { item ->
            (surplus > 0 && !item.isInProgress).also { dropped -> if (dropped) surplus-- }
        }.asReversed()
    }

    companion object {
        const val MAX_ITEMS = 50
        const val MAX_PENDING_REQUESTS = 5
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}
