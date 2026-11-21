package com.rembrowser.rembrowse.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest

private const val TAG = "ImageClipboard"

/** Puts a picture from a page on the clipboard (the page menu's Copy image). */
fun interface ImageClipboard {
    /** Copies the picture at [url] (http(s) or a `data:` address), shown on the page at [pageUrl]. */
    fun copyImage(url: String, pageUrl: String?)
}

/** A picture ready for the clipboard: its bytes and its type. */
class ClipboardImage(val bytes: ByteArray, val mimeType: String) {
    val extension: String get() = ClipboardImages.extensionFor(mimeType)
}

/** The parts of copying a picture that need no device: reading `data:` addresses and image types. */
object ClipboardImages {

    /** The largest picture copied; a bigger one is almost certainly not meant for the clipboard. */
    const val MAX_BYTES = 25 * 1024 * 1024

    /** The picture in a `data:image/…` address, or null if it isn't one or can't be read. */
    fun fromDataUrl(url: String): ClipboardImage? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        val parts = header.split(';')
        val mimeType = parts.first().trim().lowercase().ifEmpty { "text/plain" }
        if (!mimeType.startsWith("image/")) return null
        val data = url.substring(comma + 1)
        val bytes = try {
            if (parts.drop(1).any { it.trim().equals("base64", ignoreCase = true) }) {
                Base64.decode(data, Base64.DEFAULT)
            } else {
                URLDecoder.decode(data, Charsets.UTF_8.name()).toByteArray(Charsets.UTF_8)
            }
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return ClipboardImage(bytes, sniff(bytes) ?: mimeType)
    }

    /**
     * What [bytes] are, from their first bytes: PNG, JPEG, GIF, WebP, BMP or AVIF; SVG by its
     * opening tag. Null when unknown. Servers often send a wrong or missing Content-Type.
     */
    fun sniff(bytes: ByteArray): String? {
        fun startsWith(vararg values: Int, at: Int = 0) =
            bytes.size >= at + values.size && values.indices.all { bytes[at + it].toInt() and 0xFF == values[it] }
        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            startsWith(0x52, 0x49, 0x46, 0x46) && startsWith(0x57, 0x45, 0x42, 0x50, at = 8) -> "image/webp"
            startsWith(0x42, 0x4D) -> "image/bmp"
            startsWith(0x66, 0x74, 0x79, 0x70, at = 4) &&
                (startsWith(0x61, 0x76, 0x69, 0x66, at = 8) || startsWith(0x61, 0x76, 0x69, 0x73, at = 8)) -> "image/avif"
            String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).let { head ->
                head.trimStart().startsWith("<svg") || (head.trimStart().startsWith("<?xml") && head.contains("<svg"))
            } -> "image/svg+xml"
            else -> null
        }
    }

    /**
     * Whether a picture of [mimeType] is pasted as it is. PNG, JPEG and GIF (animation kept) paste
     * into nearly anything; WebP, AVIF and BMP are turned into PNG first, as desktop browsers do.
     */
    fun keepsItsFormat(mimeType: String): Boolean =
        mimeType == "image/png" || mimeType == "image/jpeg" || mimeType == "image/gif" || mimeType == "image/svg+xml"

    fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/svg+xml" -> "svg"
        else -> "img"
    }

    /** [input] read whole, or null past [MAX_BYTES]. */
    fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/**
 * [ImageClipboard] for the device. The picture is fetched through the engine's own network stack
 * (GeckoView's GeckoWebExecutor), so it comes with the site's cookies as on the page, saved in the
 * app's cache and put on the clipboard as a `content:` address that pasting apps can read.
 */
class GeckoImageClipboard(context: Context, runtime: GeckoRuntime) : ImageClipboard {
    private val appContext = context.applicationContext
    private val executor = GeckoWebExecutor(runtime)
    private val main = Handler(Looper.getMainLooper())

    override fun copyImage(url: String, pageUrl: String?) {
        ClipboardImages.fromDataUrl(url)?.let { image ->
            Thread({ finish(image) }, "copy-image").start()
            return
        }
        if (url.startsWith("data:", ignoreCase = true)) {
            failed(null)
            return
        }
        val request = WebRequest.Builder(url)
            .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .apply { pageUrl?.let { referrer(it) } }
            .build()
        executor.fetch(request).accept({ response ->
            if (response == null) {
                failed(null)
                return@accept
            }
            // The body is a stream that must not be read on the main thread
            Thread({
                try {
                    val body = response.body
                    val bytes = body?.use(ClipboardImages::readLimited)
                    val contentType = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.substringBefore(';')?.trim()?.lowercase()
                    when {
                        response.statusCode !in 200..299 || bytes == null || bytes.isEmpty() -> failed(null)
                        else -> {
                            val type = ClipboardImages.sniff(bytes) ?: contentType?.takeIf { it.startsWith("image/") }
                            if (type == null) failed(null) else finish(ClipboardImage(bytes, type))
                        }
                    }
                } catch (e: Exception) {
                    failed(e)
                }
            }, "copy-image").start()
        }, { error -> failed(error) })
    }

    /** Off the main thread: makes the file and puts it on the clipboard. */
    private fun finish(image: ClipboardImage) {
        try {
            val ready = if (ClipboardImages.keepsItsFormat(image.mimeType)) image else toPng(image) ?: image
            val dir = File(appContext.cacheDir, "clipboard").apply { mkdirs() }
            // Only the latest copy is kept: older ones are no longer on the clipboard
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "image-${System.currentTimeMillis()}.${ready.extension}")
            file.writeBytes(ready.bytes)
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.downloads", file)
            main.post {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return@post
                clipboard.setPrimaryClip(ClipData.newUri(appContext.contentResolver, "Image", uri))
                // Android 13 and later show their own notice for anything copied
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(appContext, "Image copied", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            failed(e)
        }
    }

    /** WebP, AVIF or BMP as PNG, or null if Android can't read it (then it is copied as it is). */
    private fun toPng(image: ClipboardImage): ClipboardImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // A huge picture would not fit in memory as pixels: keep its own format
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            ClipboardImage(out.toByteArray(), "image/png")
        } finally {
            bitmap.recycle()
        }
    }

    private fun failed(error: Throwable?) {
        if (error != null) Log.w(TAG, "Couldn't copy an image", error)
        main.post { Toast.makeText(appContext, "Couldn't copy the image", Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val MAX_PIXELS = 40_000_000L
    }
}
