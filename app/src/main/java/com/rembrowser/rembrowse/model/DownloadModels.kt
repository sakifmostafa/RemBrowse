package com.rembrowser.rembrowse.model

import com.rembrowser.rembrowse.util.UrlResolver
import java.io.InputStream

enum class DownloadStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** One file in the downloads list. */
data class DownloadItem(
    val id: String,
    val fileName: String,
    val sourceUrl: String,
    val mimeType: String,
    /** The size the server announced, or [UNKNOWN_SIZE] when it did not say. */
    val totalBytes: Long = UNKNOWN_SIZE,
    val receivedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.IN_PROGRESS,
    /** Where the finished file can be opened from. Null until the download has completed. */
    val contentUri: String? = null,
    val startedAtMillis: Long = 0
) {
    val isInProgress: Boolean
        get() = status == DownloadStatus.IN_PROGRESS

    /** 0..1 while a download of known size is running; null when there is nothing to draw. */
    val progress: Float?
        get() = if (isInProgress && totalBytes > 0) {
            (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            null
        }

    /** The site the file came from, for display. Blank for data: and blob: addresses. */
    val sourceHost: String
        get() = if (UrlResolver.isWebUrl(sourceUrl)) UrlResolver.extractDisplayHost(sourceUrl) else ""

    companion object {
        const val UNKNOWN_SIZE = -1L
    }
}

/**
 * A file the engine is offering to save. The engine holds [body] open, without reading from
 * the network, until the request is accepted (the body is then read to the end) or declined.
 * Either way the stream must be closed by whoever takes the request.
 */
class DownloadRequest(
    /** The tab the file was requested from, if it is still known. */
    val tabId: String?,
    val url: String,
    val fileName: String,
    val mimeType: String,
    /** Size in bytes, or [DownloadItem.UNKNOWN_SIZE]. */
    val contentLength: Long,
    /** The user already chose to save this (for example with a PDF viewer's save button). */
    val skipConfirmation: Boolean,
    val body: InputStream
) {
    val sourceHost: String
        get() = if (UrlResolver.isWebUrl(url)) UrlResolver.extractDisplayHost(url) else ""
}

/** The most recent thing worth telling the user about, shown briefly over the page. */
data class DownloadNotice(
    val itemId: String,
    val fileName: String,
    val status: DownloadStatus
)

data class DownloadsUiState(
    /** Newest first. */
    val items: List<DownloadItem> = emptyList(),
    /** Files waiting for the user to say yes or no; the first one is on screen. */
    val pendingRequests: List<DownloadRequest> = emptyList(),
    val notice: DownloadNotice? = null,
    /** A problem that is not tied to one download, such as a file that can no longer be opened. */
    val message: String? = null
) {
    val pendingRequest: DownloadRequest?
        get() = pendingRequests.firstOrNull()

    val activeCount: Int
        get() = items.count { it.isInProgress }

    val hasFinishedItems: Boolean
        get() = items.any { !it.isInProgress }
}
