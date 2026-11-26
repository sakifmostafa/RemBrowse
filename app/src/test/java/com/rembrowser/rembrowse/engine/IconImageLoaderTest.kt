package com.rembrowser.rembrowse.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import com.rembrowser.rembrowse.RemBrowseApplication
import com.rembrowser.rembrowse.data.CoilSiteIconCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image loader the app gives Coil (see RemBrowseApplication): web icons come through the
 * engine and nothing else, the runtime is not asked for until one is fetched, and a site without
 * an icon is asked once, its "not found" kept in the disk cache for the next start, until the
 * icons are cleared with the browsing history.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconImageLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeEngine(private val answer: (WebRequest) -> WebResponse) : EngineFetch {
        val sent = CopyOnWriteArrayList<String>()

        override suspend fun fetch(request: WebRequest, flags: Int): WebResponse {
            sent += request.uri
            return answer(request)
        }
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8A1538.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun answer(url: String, code: Int, body: ByteArray, type: String): WebResponse =
        WebResponse.Builder(url)
            .statusCode(code)
            .header("Content-Type", type)
            .header("Content-Length", body.size.toString())
            .body(ByteArrayInputStream(body))
            .build()

    /**
     * Loads [data] with [loader] off the main thread, at its own size or [px] pixels across,
     * running the main thread's queue meanwhile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun load(loader: ImageLoader, data: Any, px: Int? = null): ImageResult {
        val result = CompletableDeferred<ImageResult>()
        CoroutineScope(Dispatchers.IO).launch {
            val request = ImageRequest.Builder(context).data(data).apply { if (px != null) size(px) }.build()
            result.complete(loader.execute(request))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!result.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("The load finished", result.isCompleted)
        return result.getCompleted()
    }

    @Test
    fun buildingTheLoaderAndLoadingALocalFileNeverAsksForTheRuntime() {
        val loader = iconImageLoader(context, GeckoEngineFetch { throw AssertionError("The runtime was asked for") })
        val file = File(context.cacheDir, "icon-${UUID.randomUUID()}.png").apply { writeBytes(png()) }
        val result = load(loader, file)
        assertTrue("$result", result is SuccessResult)
    }

    @Test
    fun theAppsLoaderNeverStartsTheEngineForAnIcon() {
        // No window has started the engine here, as in Gecko's own processes
        val app = ApplicationProvider.getApplicationContext<RemBrowseApplication>()
        val result = load(app.newImageLoader(app), "https://site-${UUID.randomUUID()}.test/favicon.ico")
        assertEquals("The engine isn't running", (result as ErrorResult).throwable.message)
    }

    @Test
    fun aSiteIconIsFetchedThroughTheEngine() {
        val url = "https://site-${UUID.randomUUID()}.test/favicon.ico"
        val icon = png()
        val engine = FakeEngine { answer(it.uri, 200, icon, "image/png") }
        val result = load(iconImageLoader(context, engine), url)
        assertTrue("$result", result is SuccessResult)
        assertEquals(listOf(url), engine.sent.toList())
    }

    /** Loads [icon], served as a Windows icon, at its own size and at the tab rail's (55 pixels). */
    private fun loadIco(icon: ByteArray): List<ImageResult> {
        val engine = FakeEngine { answer(it.uri, 200, icon, "image/vnd.microsoft.icon") }
        val url = "https://ico-${UUID.randomUUID()}.test/favicon.ico"
        val loader = iconImageLoader(context, engine)
        return listOf(load(loader, url), load(loader, url, px = 55))
    }

    @Test
    fun anIcoOf16ColourImages_likeWikipedias_isDecodedAtItsLargest() {
        val icon = TestIcons.wikipediaLikeIco()
        assertEquals("The same layout as Wikipedia's 2,734 bytes", 2734, icon.size)
        val (whole, sized) = loadIco(icon)
        assertTrue("$whole", whole is SuccessResult)
        assertEquals(48, (whole as SuccessResult).image.width)
        assertTrue("$sized", sized is SuccessResult)
    }

    @Test
    fun anIcoOfNineImagesEndingInAPng_likePumpFuns_isDecodedAtItsLargest() {
        val (whole, sized) = loadIco(TestIcons.pumpFunLikeIco())
        assertTrue("$whole", whole is SuccessResult)
        assertEquals(256, (whole as SuccessResult).image.width)
        assertTrue("$sized", sized is SuccessResult)
    }

    @Test
    fun aSiteWithoutAnIconIsAskedOnceAndRemembered() {
        val url = "https://no-icon-${UUID.randomUUID()}.test/favicon.ico"
        val engine = FakeEngine { answer(it.uri, 404, "Not Found".toByteArray(), "text/html") }
        assertTrue(load(iconImageLoader(context, engine), url) is ErrorResult)
        // A new loader, as after RemBrowse starts again: the disk cache answers
        assertTrue(load(iconImageLoader(context, engine), url) is ErrorResult)
        assertEquals(listOf(url), engine.sent.toList())
    }

    @Test
    fun clearingTheSiteIconsForgetsThemOnDiskAndInMemory() {
        val url = "https://visited-${UUID.randomUUID()}.test/favicon.ico"
        val icon = png()
        val engine = FakeEngine { answer(it.uri, 200, icon, "image/png") }
        val loader = iconImageLoader(context, engine)
        assertTrue(load(loader, url) is SuccessResult)
        assertTrue("Kept in memory", loader.memoryCache!!.keys.isNotEmpty())
        assertTrue("Kept on disk", loader.diskCache!!.size > 0)

        var worked: Runnable? = null
        CoilSiteIconCache({ loader }, worker = { worked = it }).clear()
        assertTrue("Nothing is deleted on the caller's thread", loader.diskCache!!.size > 0)
        worked!!.run()

        assertTrue(loader.memoryCache!!.keys.isEmpty())
        assertEquals(0L, loader.diskCache!!.size)
        // Neither this loader nor one made after a restart has it: the site is asked again
        assertTrue(load(loader, url) is SuccessResult)
        assertEquals(listOf(url, url), engine.sent.toList())
        CoilSiteIconCache({ loader }, worker = { it.run() }).clear()
        assertTrue(load(iconImageLoader(context, engine), url) is SuccessResult)
        assertEquals(listOf(url, url, url), engine.sent.toList())
    }
}
