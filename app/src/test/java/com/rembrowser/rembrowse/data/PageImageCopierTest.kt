package com.rembrowser.rembrowse.data

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Copying a picture a page hands over (its own copy button): read off the main thread, one at a
 * time, and always answered, even when the app runs out of memory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PageImageCopierTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val files = ClipboardImageFiles(context)
    private val main = Handler(Looper.getMainLooper())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    /** Workers handed to [startWorker] and not run yet. */
    private val waiting = mutableListOf<Runnable>()
    private val answers = mutableListOf<Boolean>()

    private fun copier(
        decode: (String) -> ByteArray? = { png },
        readable: (ByteArray) -> Boolean = { true },
        startWorker: (Runnable) -> Unit = { waiting += it }
    ) = PageImageCopier(files, main, decode, readable, startWorker)

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun forgetFileProviderRoots() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { field ->
            (field.get(null) as MutableMap<*, *>).clear()
        }
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPng_goesOnTheClipboard_asAContentAddress() {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val picture = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val base64 = java.util.Base64.getEncoder().encodeToString(picture)

        // The real decoding and check, on a thread of its own
        PageImageCopier(files, main).copyPageImage(base64) { answers += it }
        val deadline = System.currentTimeMillis() + 10_000
        while (answers.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            idleMain()
        }

        assertEquals(listOf(true), answers)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("content", clip.getItemAt(0).uri.scheme)
        assertTrue(clip.description.hasMimeType("image/png"))
        assertEquals("Image", clip.description.label)
    }

    @Test
    fun decoding_happensOffTheMainThread_andTheAnswerOnIt() {
        var decodedOn: Thread? = null
        var answeredOnMain = false
        val copier = copier(
            decode = { decodedOn = Thread.currentThread(); png },
            startWorker = { worker -> Thread(worker).apply { start(); join() } }
        )

        copier.copyPageImage("iVBORw0KGgo=") { answeredOnMain = Looper.myLooper() == Looper.getMainLooper(); answers += it }
        idleMain()

        assertNotNull(decodedOn)
        assertNotEquals(Looper.getMainLooper().thread, decodedOn)
        assertEquals(listOf(true), answers)
        assertTrue(answeredOnMain)
    }

    @Test
    fun aSecondPicture_whileOneIsBeingCopied_isRefused_untilTheFirstIsOnTheClipboard() {
        val copier = copier()
        copier.copyPageImage("first") { answers += it }
        copier.copyPageImage("second") { answers += it }
        idleMain()

        // The second never started, and the first is still running
        assertEquals(1, waiting.size)
        assertEquals(listOf(false), answers)

        waiting.removeAt(0).run()
        // Still refused while the first waits for the main thread to put it on the clipboard, so a
        // later copy can't delete its file before then
        copier.copyPageImage("third") { answers += it }
        idleMain()
        assertEquals(listOf(false, true, false), answers)
        assertTrue(waiting.isEmpty())

        copier.copyPageImage("fourth") { answers += it }
        assertEquals("Free again once the first was answered", 1, waiting.size)
    }

    @Test
    fun runningOutOfMemory_isAnsweredWithFalse_andTheNextCopyCanRun() {
        val copier = copier(decode = { throw OutOfMemoryError("Failed to allocate") })
        copier.copyPageImage("big") { answers += it }
        waiting.removeAt(0).run()
        idleMain()
        assertEquals(listOf(false), answers)

        copier.copyPageImage("next") { answers += it }
        assertEquals(1, waiting.size)
    }

    @Test
    fun anyOtherFailure_isAnsweredWithFalse() {
        val throwing = copier(readable = { throw IllegalStateException("decoder") })
        throwing.copyPageImage("a") { answers += it }
        waiting.removeAt(0).run()

        val unreadable = copier(readable = { false })
        unreadable.copyPageImage("b") { answers += it }
        waiting.removeAt(0).run()

        val notAPng = copier(decode = { null })
        notAPng.copyPageImage("c") { answers += it }
        waiting.removeAt(0).run()

        idleMain()
        assertEquals(listOf(false, false, false), answers)
        assertTrue("Nothing saved", File(context.cacheDir, ClipboardImageFiles.DIR).listFiles().isNullOrEmpty())
        assertNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
    }

    @Test
    fun aWorkerThatCantStart_isAnsweredWithFalse_andTheNextCopyCanRun() {
        var starts = 0
        val copier = copier(startWorker = { worker ->
            if (starts++ == 0) throw OutOfMemoryError("pthread_create failed") else waiting += worker
        })
        copier.copyPageImage("a") { answers += it }
        idleMain()
        assertEquals(listOf(false), answers)

        copier.copyPageImage("b") { answers += it }
        assertEquals(1, waiting.size)
        assertFalse(answers.size > 1)
    }
}
