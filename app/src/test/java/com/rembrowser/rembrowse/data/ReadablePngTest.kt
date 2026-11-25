package com.rembrowser.rembrowse.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The check a page's own copied picture passes before it goes on the clipboard: a PNG that
 * Android's decoder reads, with Android's real decoder (Robolectric's native graphics).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadablePngTest {

    private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(204, 51, 51))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray().also { bitmap.recycle() }
    }

    @Test
    fun aPng_isReadable() {
        assertTrue(isReadablePng(picture(120, 80)))
    }

    @Test
    fun aLargePng_isReadAtAReducedSize() {
        // 6 million pixels: checked at a quarter of them, so it needs no 24 MB of memory
        assertTrue(isReadablePng(picture(3000, 2000)))
    }

    @Test
    fun aPictureThatIsNotAPng_isRefused() {
        assertFalse("JPEG", isReadablePng(picture(40, 30, Bitmap.CompressFormat.JPEG)))
        assertFalse("WebP", isReadablePng(picture(40, 30, Bitmap.CompressFormat.WEBP_LOSSLESS)))
    }

    @Test
    fun bytesThatOnlyStartLikeAPng_areRefused() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(isReadablePng(signature))
        assertFalse(isReadablePng(signature + ByteArray(64) { it.toByte() }))
        // Its header with no picture after it
        assertFalse(isReadablePng(picture(120, 80).copyOf(33)))
    }

    @Test
    fun nothing_isRefused() {
        assertFalse(isReadablePng(ByteArray(0)))
        assertFalse(isReadablePng("not a picture".toByteArray()))
    }
}
