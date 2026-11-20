package com.rembrowser.rembrowse.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.WorkerThread
import com.rembrowser.rembrowse.util.DownloadFileNames
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "UploadCache"

/**
 * Private copies of the files a user picks for a page's upload button.
 *
 * The system file picker returns `content://` documents. GeckoView resolves a picked document
 * to a path on shared storage, which a browser is not allowed to open under Android's scoped
 * storage, so the upload would fail. Each picked file is therefore copied, under its own name,
 * into the app's cache, and the engine is given the copy.
 *
 * Copies are kept while the page may still be reading them, and removed the next time the
 * browser starts.
 */
class UploadCache(
    private val context: Context,
    private val root: File = File(context.cacheDir, "uploads"),
    private val worker: Executor = Executors.newSingleThreadExecutor(),
    private val deliver: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post
) {
    /** Removes the copies made during earlier runs. */
    fun clearPrevious() {
        worker.execute { root.deleteRecursively() }
    }

    /**
     * Copies [uris] off the main thread and passes the copies to [onCopied] on the main thread,
     * in the same order. A file that cannot be read is left out.
     */
    fun copyInBackground(uris: List<Uri>, onCopied: (List<File>) -> Unit) {
        worker.execute {
            val files = copy(uris)
            deliver { onCopied(files) }
        }
    }

    @WorkerThread
    fun copy(uris: List<Uri>): List<File> = uris.mapNotNull { uri ->
        runCatching { copyOne(uri) }
            .onFailure { Log.w(TAG, "Couldn't read a picked file", it) }
            .getOrNull()
    }

    private fun copyOne(uri: Uri): File {
        // A folder of its own per file keeps the original name even when two files share it
        val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(folder, DownloadFileNames.sanitize(displayName(uri) ?: uri.lastPathSegment ?: "file"))
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("No content for $uri")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        } catch (t: Throwable) {
            folder.deleteRecursively()
            throw t
        }
        return target
    }

    /** The name the picker showed for [uri], when its provider reports one. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * The MIME types to offer the system picker for what a page accepts. Gecko passes MIME
         * types, such as `application/pdf` or an image family; anything else, or nothing, means
         * any file.
         */
        fun pickerMimeTypes(accepted: List<String>?): List<String> {
            val types = accepted.orEmpty()
                .map { it.trim().lowercase() }
                .filter { '/' in it && !it.startsWith('/') && !it.endsWith("/") }
                .distinct()
            return types.ifEmpty { listOf("*/*") }
        }
    }
}
