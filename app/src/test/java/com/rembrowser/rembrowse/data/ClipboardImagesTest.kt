package com.rembrowser.rembrowse.data

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What can be known about a copied picture without a device: its type and its `data:` address. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClipboardImagesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test
    fun sniff_namesEachFormatByItsFirstBytes() {
        assertEquals("image/png", ClipboardImages.sniff(png))
        assertEquals("image/jpeg", ClipboardImages.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals("image/gif", ClipboardImages.sniff("GIF89a....".toByteArray()))
        assertEquals("image/gif", ClipboardImages.sniff("GIF87a....".toByteArray()))
        assertEquals("image/webp", ClipboardImages.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/bmp", ClipboardImages.sniff("BM......".toByteArray()))
        assertEquals("image/avif", ClipboardImages.sniff(bytes(0, 0, 0, 0x1C) + "ftypavif".toByteArray()))
        assertEquals("image/avif", ClipboardImages.sniff(bytes(0, 0, 0, 0x1C) + "ftypavis".toByteArray()))
        assertEquals("image/svg+xml", ClipboardImages.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertEquals("image/svg+xml", ClipboardImages.sniff("  \n<?xml version=\"1.0\"?><svg/>".toByteArray()))
    }

    @Test
    fun sniff_unknownBytesAreNull() {
        assertNull(ClipboardImages.sniff(ByteArray(0)))
        assertNull(ClipboardImages.sniff("<html><body>not found</body></html>".toByteArray()))
        assertNull(ClipboardImages.sniff("<?xml version=\"1.0\"?><feed/>".toByteArray()))
        assertNull("A RIFF that is not WebP", ClipboardImages.sniff("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull("Too short to tell", ClipboardImages.sniff(bytes(0x89, 0x50)))
        assertNull(ClipboardImages.sniff(bytes(0, 0, 0, 0x1C) + "ftypmp42".toByteArray()))
    }

    @Test
    fun formatsAndExtensions() {
        listOf("image/png", "image/jpeg", "image/gif", "image/svg+xml").forEach { assertTrue(it, ClipboardImages.keepsItsFormat(it)) }
        listOf("image/webp", "image/avif", "image/bmp", "image/tiff").forEach { assertFalse(it, ClipboardImages.keepsItsFormat(it)) }
        assertEquals("png", ClipboardImages.extensionFor("image/png"))
        assertEquals("jpg", ClipboardImages.extensionFor("image/jpeg"))
        assertEquals("gif", ClipboardImages.extensionFor("image/gif"))
        assertEquals("webp", ClipboardImages.extensionFor("image/webp"))
        assertEquals("bmp", ClipboardImages.extensionFor("image/bmp"))
        assertEquals("avif", ClipboardImages.extensionFor("image/avif"))
        assertEquals("svg", ClipboardImages.extensionFor("image/svg+xml"))
        assertEquals("img", ClipboardImages.extensionFor("image/tiff"))
        assertEquals("png", ClipboardImage(png, "image/png").extension)
    }

    @Test
    fun fromDataUrl_readsBase64() {
        val url = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val image = ClipboardImages.fromDataUrl(url)!!
        assertArrayEquals(png, image.bytes)
        assertEquals("image/png", image.mimeType)
        assertNotNull(ClipboardImages.fromDataUrl(url.replace("data:", "DATA:").replace("base64", "BASE64")))
    }

    @Test
    fun fromDataUrl_readsPercentEncodedText_andTrustsTheBytesOverTheDeclaredType() {
        val svg = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E"
        val image = ClipboardImages.fromDataUrl(svg)!!
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\"/>", String(image.bytes))
        assertEquals("image/svg+xml", image.mimeType)

        // Declared as GIF, but the bytes are a PNG
        val wrong = "data:image/gif;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        assertEquals("image/png", ClipboardImages.fromDataUrl(wrong)!!.mimeType)

        // Unrecognised bytes keep the declared type
        val unknown = "data:image/x-thing;base64," + Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP)
        assertEquals("image/x-thing", ClipboardImages.fromDataUrl(unknown)!!.mimeType)
    }

    @Test
    fun fromDataUrl_refusesWhatIsNotAReadablePicture() {
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
        assertNull("Not an image type", ClipboardImages.fromDataUrl("data:text/plain;base64,$b64"))
        assertNull("No type at all is text/plain", ClipboardImages.fromDataUrl("data:;base64,$b64"))
        assertNull("No type, no header", ClipboardImages.fromDataUrl("data:,hello"))
        assertNull("Empty data", ClipboardImages.fromDataUrl("data:image/png;base64,"))
        assertNull("Empty text", ClipboardImages.fromDataUrl("data:image/svg+xml,"))
        assertNull("No comma", ClipboardImages.fromDataUrl("data:image/png;base64"))
        assertNull("Not a data address", ClipboardImages.fromDataUrl("https://example.com/a.png"))
        assertNull("Broken base64", ClipboardImages.fromDataUrl("data:image/png;base64,@@@@"))
        assertNull("Broken percent escape", ClipboardImages.fromDataUrl("data:image/svg+xml,%ZZ"))
    }

    @Test
    fun readLimited_readsASmallStreamWhole() {
        assertArrayEquals(png, ClipboardImages.readLimited(png.inputStream()))
        assertArrayEquals(ByteArray(0), ClipboardImages.readLimited(ByteArray(0).inputStream()))
    }

    /** Endless zeros that remember how much was asked of them, so no 25 MB array is built. */
    private class EndlessStream(private val limit: Long = Long.MAX_VALUE) : InputStream() {
        var served = 0L
        override fun read(): Int = if (served >= limit) -1 else { served++; 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) return -1
            val n = minOf(len.toLong(), limit - served).toInt()
            served += n
            return n
        }
    }

    @Test
    fun readLimited_givesUpPastTheLimitWithoutReadingForever() {
        val stream = EndlessStream()
        assertNull(ClipboardImages.readLimited(stream))
        assertTrue(stream.served > ClipboardImages.MAX_BYTES)
        assertTrue("Stops right after the limit", stream.served <= ClipboardImages.MAX_BYTES + 64 * 1024)
    }

    @Test
    fun readLimited_acceptsExactlyTheLimit() {
        val result = ClipboardImages.readLimited(EndlessStream(ClipboardImages.MAX_BYTES.toLong()))
        assertEquals(ClipboardImages.MAX_BYTES, result!!.size)
        assertNull(ClipboardImages.readLimited(EndlessStream(ClipboardImages.MAX_BYTES + 1L)))
    }
}
