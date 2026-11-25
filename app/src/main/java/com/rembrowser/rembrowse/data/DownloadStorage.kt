package com.rembrowser.rembrowse.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.rembrowser.rembrowse.util.DownloadFileNames
import java.io.IOException
import java.io.OutputStream

/** A file being written. Exactly one of [commit] or [discard] ends it. */
interface DownloadTarget {
    /** The name the file was actually given, which may differ from the one asked for. */
    val fileName: String

    fun openOutput(): OutputStream

    /** Makes the finished file visible and returns the address it can be opened from. */
    fun commit(): String

    /** Removes whatever was written. */
    fun discard()
}

/** Where downloaded files go. Kept behind an interface so the download logic runs on the JVM. */
interface DownloadStorage {
    /** A short description of the place, for the settings page. */
    val locationLabel: String

    /** True when files land in the device's shared Downloads folder, which the system can show. */
    val isSharedFolder: Boolean

    /** Creates a new file. Never overwrites: a taken name gets a numbered variant. */
    fun create(fileName: String, mimeType: String): DownloadTarget

    /** Whether a finished download can still be read (it may have been moved or deleted). */
    fun exists(contentUri: String): Boolean
}

/**
 * Saves into the device's shared Downloads folder through MediaStore, which needs no storage
 * permission.
 */
class AndroidDownloadStorage(context: Context) : DownloadStorage {

    private val appContext = context.applicationContext

    override val isSharedFolder: Boolean = true

    override val locationLabel: String = "The Downloads folder on this device"

    override fun create(fileName: String, mimeType: String): DownloadTarget = createInMediaStore(fileName)

    override fun exists(contentUri: String): Boolean = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun createInMediaStore(requestedName: String): DownloadTarget {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, requestedName)
            // MediaStore appends an extension when the name does not match the type. Giving it
            // the type that belongs to the name's own extension keeps the name as it is.
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(requestedName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until the last byte is written
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create a file in Downloads")
        val savedName = displayNameOf(uri) ?: requestedName

        return object : DownloadTarget {
            override val fileName = savedName

            override fun openOutput(): OutputStream =
                resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file for writing")

            override fun commit(): String {
                val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
                return uri.toString()
            }

            override fun discard() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val GENERIC_TYPE = "application/octet-stream"

        /** The type Android associates with [fileName]'s extension, for saving and for opening. */
        fun mimeTypeFor(fileName: String): String {
            val extension = DownloadFileNames.extensionOf(fileName)
            if (extension.isEmpty()) return GENERIC_TYPE
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: GENERIC_TYPE
        }
    }
}
