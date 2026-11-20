package com.rembrowser.rembrowse.data

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Private copies of files picked for a page's upload button. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UploadCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root by lazy { File(temp.root, "uploads") }

    // Runs work and deliveries in the calling thread, so results are there when a call returns
    private val cache by lazy { UploadCache(context, root, worker = { it.run() }, deliver = { it.run() }) }

    private fun picked(name: String, text: String): Uri =
        Uri.fromFile(File(temp.newFolder(), name).apply { writeText(text) })

    @Test
    fun copiesEachFileUnderItsOwnName_inPickedOrder() {
        val photo = picked("holiday photo.jpg", "jpeg bytes")
        val report = picked("report.pdf", "pdf bytes")

        var copies: List<File> = emptyList()
        cache.copyInBackground(listOf(photo, report)) { copies = it }

        assertEquals(listOf("holiday photo.jpg", "report.pdf"), copies.map { it.name })
        assertEquals(listOf("jpeg bytes", "pdf bytes"), copies.map { it.readText() })
        assertTrue(copies.all { it.canonicalPath.startsWith(root.canonicalPath) })
    }

    @Test
    fun twoFilesWithTheSameName_bothKeepIt() {
        val first = picked("scan.png", "one")
        val second = picked("scan.png", "two")

        val copies = cache.copy(listOf(first, second))

        assertEquals(listOf("scan.png", "scan.png"), copies.map { it.name })
        assertNotEquals(copies[0].parentFile, copies[1].parentFile)
        assertEquals(listOf("one", "two"), copies.map { it.readText() })
    }

    @Test
    fun aFileThatCannotBeRead_isLeftOut() {
        val missing = Uri.fromFile(File(temp.root, "gone.txt"))
        val present = picked("notes.txt", "hello")

        val copies = cache.copy(listOf(missing, present))

        assertEquals(listOf("notes.txt"), copies.map { it.name })
        // No empty folder is left behind for the failed one
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun clearPrevious_removesEarlierCopies() {
        cache.copy(listOf(picked("old.txt", "old")))
        assertTrue(root.exists())

        cache.clearPrevious()

        assertFalse(root.exists())
    }

    @Test
    fun pickerMimeTypes_offersWhatThePageAccepts_orAnyFile() {
        assertEquals(listOf("image/*", "application/pdf"), UploadCache.pickerMimeTypes(listOf("image/*", " Application/PDF ", "image/*")))
        assertEquals(listOf("*/*"), UploadCache.pickerMimeTypes(null))
        assertEquals(listOf("*/*"), UploadCache.pickerMimeTypes(emptyList()))
        // Bare extensions or broken values cannot filter the picker
        assertEquals(listOf("*/*"), UploadCache.pickerMimeTypes(listOf(".pdf", "image/", "")))
    }
}
