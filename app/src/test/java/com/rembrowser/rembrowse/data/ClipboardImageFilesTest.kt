package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.ClipboardManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The files behind pictures on the clipboard, shared by Copy image and screenshots: one file at a
 * time, handed out through the manifest's FileProvider, as an image clip pasting apps can read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClipboardImageFilesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val files = ClipboardImageFiles(context)
    private val dir = File(context.cacheDir, ClipboardImageFiles.DIR)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    /**
     * FileProvider remembers its roots in a static cache, which outlives a test in Robolectric's
     * sandbox while each test gets a new data directory.
     */
    @Before
    fun forgetFileProviderRoots() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { field ->
            (field.get(null) as MutableMap<*, *>).clear()
        }
        // A device's MimeTypeMap knows PNG, which is where FileProvider gets a file's type
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
    }

    @Test
    fun aScreenshot_isSavedAsAPng_andHandedOutThroughTheFileProvider() {
        val uri = files.save(ClipboardImage(png, "image/png"), "screenshot")!!

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.downloads", uri.authority)
        val saved = dir.listFiles().orEmpty().single()
        assertTrue(saved.name, saved.name.startsWith("screenshot-") && saved.name.endsWith(".png"))
        assertArrayEquals(png, saved.readBytes())
        // What a pasting app (or GeckoView pasting into a page) reads
        assertArrayEquals(png, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test
    fun onlyTheLatestCopy_isKept_soCapturesDontPileUp() {
        files.save(ClipboardImage(png, "image/png"), "image")
        files.save(ClipboardImage(png, "image/png"), "screenshot")
        files.save(ClipboardImage(png + byteArrayOf(4), "image/png"), "screenshot")

        val left = dir.listFiles().orEmpty()
        assertEquals(1, left.size)
        assertArrayEquals(png + byteArrayOf(4), left.single().readBytes())
    }

    @Test
    fun theClip_isTheFilesAddress_typedAsAPicture() {
        val uri = files.save(ClipboardImage(png, "image/png"), "screenshot")!!
        assertTrue(files.putOnClipboard(uri, "Screenshot"))

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(uri, clip.getItemAt(0).uri)
        assertEquals("Screenshot", clip.description.label)
        // GeckoView offers a clip to a page as a picture by this type (Clipboard.hasData("image/png"))
        assertTrue(clip.description.hasMimeType("image/png"))
        assertFalse("Not offered as text", clip.description.hasMimeType("text/plain"))
    }

    @Test
    fun aCopyNoLongerWanted_isNotSaved_andLeavesTheLastOneAlone() {
        val kept = files.save(ClipboardImage(png, "image/png"), "screenshot")
        val keptFile = dir.listFiles().orEmpty().single()

        assertEquals(null, files.save(ClipboardImage(png + byteArrayOf(9), "image/png"), "screenshot", wanted = { false }))

        assertEquals(listOf(keptFile), dir.listFiles().orEmpty().toList())
        assertArrayEquals(png, context.contentResolver.openInputStream(kept!!)!!.use { it.readBytes() })
    }

    private val pendingDir = File(context.cacheDir, ClipboardImageFiles.PENDING_DIR)

    @Test
    fun aPictureWrittenStraightToAFile_takesTheClipboardFilesPlace_onceItIsWhole() {
        files.save(ClipboardImage(byteArrayOf(9), "image/png"), "image")

        val pending = files.writePending("png") { out ->
            out.write(png)
            true
        }!!
        assertArrayEquals(png, pending.readBytes())
        assertEquals("Not on the clipboard's shelf yet", pendingDir, pending.parentFile)
        assertEquals("The last copy is still there", 1, dir.listFiles().orEmpty().size)

        val uri = files.adopt(pending, "screenshot")!!
        val saved = dir.listFiles().orEmpty().single()
        assertTrue(saved.name, saved.name.startsWith("screenshot-") && saved.name.endsWith(".png"))
        assertArrayEquals(png, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertFalse(pending.exists())
        assertTrue(pendingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aPictureThatCouldntBeEncodedWhole_leavesNothingBehind_andTheLastCopyStays() {
        files.save(ClipboardImage(png, "image/png"), "image")

        // What Bitmap.compress answers when it ran out of memory or room part-way
        val pending = files.writePending("png") { out ->
            out.write(byteArrayOf(1, 2, 3))
            false
        }

        assertEquals(null, pending)
        assertTrue(pendingDir.listFiles().orEmpty().isEmpty())
        assertArrayEquals(png, dir.listFiles().orEmpty().single().readBytes())
    }

    @Test
    fun aPictureThatCantBeWritten_isNoFile_andRunningOutOfMemoryIsPassedOn() {
        assertEquals(null, files.writePending("png") { throw java.io.IOException("No space left on device") })
        assertTrue(pendingDir.listFiles().orEmpty().isEmpty())

        var thrown: Throwable? = null
        try {
            files.writePending("png") { out ->
                out.write(png)
                throw OutOfMemoryError("Failed to allocate")
            }
        } catch (e: OutOfMemoryError) {
            thrown = e
        }
        assertTrue(thrown is OutOfMemoryError)
        assertTrue(pendingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aPictureNoLongerWanted_isDeleted_andTheLastCopyStays() {
        files.save(ClipboardImage(png, "image/png"), "image")
        val pending = files.writePending("png") { out ->
            out.write(byteArrayOf(4, 5, 6))
            true
        }!!

        assertEquals(null, files.adopt(pending, "screenshot", wanted = { false }))

        assertFalse(pending.exists())
        assertArrayEquals(png, dir.listFiles().orEmpty().single().readBytes())
        assertEquals("One already gone", null, files.adopt(pending, "screenshot"))
    }

    @Test
    fun aPictureLeftHalfWrittenByACopyThatNeverFinished_isClearedByTheNext() {
        pendingDir.mkdirs()
        File(pendingDir, "pending-1.png").writeBytes(byteArrayOf(1))

        val pending = files.writePending("png") { out ->
            out.write(png)
            true
        }!!

        assertEquals(listOf(pending), pendingDir.listFiles().orEmpty().toList())
    }
}
