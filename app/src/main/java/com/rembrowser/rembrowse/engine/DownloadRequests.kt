package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.util.DownloadFileNames
import java.io.InputStream

/**
 * Describes a response the engine could not display as a file to save.
 *
 * The engine does not promise how header names are capitalised, so they are matched in any
 * letter case. Nothing from the response is trusted: the name is reduced to a plain file name
 * and a size that is missing or nonsense becomes "unknown".
 */
internal fun downloadRequestFrom(
    tabId: String?,
    url: String,
    headers: Map<String, String>,
    skipConfirmation: Boolean,
    body: InputStream,
    extensionForMime: (String) -> String? = { null }
): DownloadRequest {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

    // "text/plain; charset=utf-8" -> "text/plain"
    val mimeType = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return DownloadRequest(
        tabId = tabId,
        url = url,
        fileName = DownloadFileNames.resolve(url, header("Content-Disposition"), mimeType, extensionForMime),
        mimeType = mimeType,
        contentLength = header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: DownloadItem.UNKNOWN_SIZE,
        skipConfirmation = skipConfirmation,
        body = body
    )
}
