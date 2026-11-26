package com.rembrowser.rembrowse.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import com.rembrowser.rembrowse.engine.EngineFetch
import com.rembrowser.rembrowse.engine.TestIcons
import com.rembrowser.rembrowse.engine.iconImageLoader
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.ui.components.LocalDeclaredSiteIcons
import com.rembrowser.rembrowse.ui.components.SiteIcon
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebResponse
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A site's icon on screen ([SiteIcon]) over the app's image loader and a stand-in for the
 * engine: the icons the site names for itself first, then its `/favicon.ico`, then its letter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteIconUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Each site's own host, so no test finds another's icons in the disk cache. */
    private val host = "site-${UUID.randomUUID()}.test"
    private val page = "https://$host/channels/@me"
    private val favicon = "https://$host/favicon.ico"

    /** What the stand-in engine was asked for, in order. */
    private val asked = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()

    @org.junit.Before
    fun clearTrace() { com.rembrowser.rembrowse.ui.components.SiteIconTrace.events.clear() }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    /** Serves [found] addresses a PNG and every other address a 404 page. */
    private fun serve(vararg found: String) {
        val engine = EngineFetch { request, _ ->
            asked += request.uri
            val (code, body, type) = if (request.uri in found) {
                Triple(200, TestIcons.png(64), "image/png")
            } else {
                Triple(404, "<!doctype html><title>Not found</title>".toByteArray(), "text/html")
            }
            WebResponse.Builder(request.uri).statusCode(code).header("Content-Type", type)
                .body(ByteArrayInputStream(body)).build()
        }
        val t0 = System.nanoTime()
        fun ev(what: String) { events += "${(System.nanoTime() - t0) / 1_000_000}ms [${Thread.currentThread().name}] $what" }
        val listener = object : coil3.EventListener() {
            override fun onStart(request: coil3.request.ImageRequest) = ev("start ${request.data}")
            override fun resolveSizeStart(request: coil3.request.ImageRequest, sizeResolver: coil3.size.SizeResolver) = ev("resolveSize ${request.data}")
            override fun resolveSizeEnd(request: coil3.request.ImageRequest, size: coil3.size.Size) = ev("resolvedSize ${request.data} $size")
            override fun fetchStart(request: coil3.request.ImageRequest, fetcher: coil3.fetch.Fetcher, options: coil3.request.Options) = ev("fetchStart ${request.data}")
            override fun fetchEnd(request: coil3.request.ImageRequest, fetcher: coil3.fetch.Fetcher, options: coil3.request.Options, result: coil3.fetch.FetchResult?) = ev("fetchEnd ${request.data}")
            override fun onCancel(request: coil3.request.ImageRequest) = ev("cancel ${request.data}")
            override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) = ev("error ${request.data} ${result.throwable}")
            override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) = ev("success ${request.data} ${result.dataSource}")
        }
        SingletonImageLoader.setUnsafe(iconImageLoader(context, engine).newBuilder().eventListener(listener).build())
    }

    private fun show(declared: Map<String, List<DeclaredSiteIcon>>) {
        composeRule.setContent {
            RemBrowseTheme {
                CompositionLocalProvider(LocalDeclaredSiteIcons provides declared) {
                    SiteIcon(url = page, title = "Site")
                }
            }
        }
    }

    private fun letterShown() = composeRule.onAllNodesWithText("S").fetchSemanticsNodes().isNotEmpty()

    /** Runs the screen until [done], or fails after 10 seconds. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            if (System.currentTimeMillis() >= deadline) {
                val dump = StringBuilder()
                dump.append("TEST $what asked=$asked\n")
                events.forEach { dump.append("EVENT $it\n") }
                com.rembrowser.rembrowse.ui.components.SiteIconTrace.events.forEach { dump.append("UI $it\n") }
                for ((t, st) in Thread.getAllStackTraces()) {
                    dump.append("THREAD ${t.name} ${t.state}\n")
                    st.take(25).forEach { dump.append("    at $it\n") }
                }
                java.io.File("/tmp/claude-1000/-home-mrremking-RemBrowse/af52660c-af46-41f4-b75e-956d279a9dd4/scratchpad/siteicon-dump-${System.nanoTime()}.txt").writeText(dump.toString())
            }
            if (System.currentTimeMillis() >= deadline) {
                val gsm = runCatching {
                    val c = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
                    val inst = c.getDeclaredField("INSTANCE").get(null)
                    c.declaredFields.filter { it.name != "INSTANCE" }.joinToString { f -> f.isAccessible = true; "${f.name}=${f.get(inst)}" }
                }.getOrElse { "reflect failed $it" }
                val pending = runCatching { androidx.compose.runtime.snapshots.Snapshot.current.let { it.javaClass.name + " pending=" + (it as? androidx.compose.runtime.snapshots.MutableSnapshot)?.hasPendingChanges() } }.getOrElse { "$it" }
                // Experiment: send the apply notifications ourselves, then keep running
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                var recovered = false
                val until = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < until) {
                    composeRule.mainClock.advanceTimeBy(16)
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                    if (done()) { recovered = true; break }
                }
                java.io.File("/tmp/claude-1000/-home-mrremking-RemBrowse/af52660c-af46-41f4-b75e-956d279a9dd4/scratchpad/siteicon-exp-${System.nanoTime()}.txt").writeText("what=$what gsm=[$gsm] snapshot=[$pending] recoveredAfterSendApply=$recovered asked=$asked\n")
            }
            assertTrue("Timed out waiting: $what (asked $asked)", System.currentTimeMillis() < deadline)
            if (events.isNotEmpty() && events.last().contains("LOOP").not() && System.currentTimeMillis() % 1000 < 12) events += "LOOP ${System.currentTimeMillis()}"
            composeRule.mainClock.advanceTimeBy(16)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun aDeclaredIcon_isShownInPlaceOfTheLetter_withoutAskingForFaviconIco() {
        val declared = "https://$host/assets/favicon.png"
        serve(declared)
        show(mapOf("https://$host" to listOf(DeclaredSiteIcon(declared))))

        waitFor("the declared icon") { !letterShown() }
        assertEquals(listOf(declared), asked.toList())
    }

    @Test
    fun declaredIconsThatFail_giveWayToFaviconIco() {
        val first = "https://$host/a.png"
        val second = "https://cdn-$host/b.png"
        serve(favicon)
        show(
            mapOf(
                "https://$host" to listOf(
                    DeclaredSiteIcon(first, sizes = listOf(64)),
                    DeclaredSiteIcon(second, sizes = listOf(128)),
                    DeclaredSiteIcon("https://$host/c.png", sizes = listOf(256))
                )
            )
        )

        waitFor("/favicon.ico") { !letterShown() }
        // Two of the site's own icons, the best first, then /favicon.ico
        assertEquals(listOf(first, second, favicon), asked.toList())
    }

    @Test
    fun whenNothingLoads_theLetterStays() {
        val declared = "https://$host/gone.png"
        serve()
        show(mapOf("https://$host" to listOf(DeclaredSiteIcon(declared))))

        waitFor("both addresses tried") { asked.size == 2 }
        composeRule.waitForIdle()
        assertEquals(listOf(declared, favicon), asked.toList())
        assertTrue(letterShown())
    }

    @Test
    fun aSiteThatNamesNoIcon_getsItsFaviconIco_asBefore() {
        serve(favicon)
        show(emptyMap())

        waitFor("/favicon.ico") { !letterShown() }
        assertEquals(listOf(favicon), asked.toList())
    }

    @Test
    fun anotherSiteNamingTheSameIconAddress_showsItToo() {
        // Two sites sharing one icon on a common CDN, as a tab goes from one to the other
        val shared = "https://cdn-$host/brand.png"
        val other = "https://other-$host"
        serve(shared)
        var url by mutableStateOf(page)
        composeRule.setContent {
            RemBrowseTheme {
                CompositionLocalProvider(
                    LocalDeclaredSiteIcons provides mapOf(
                        "https://$host" to listOf(DeclaredSiteIcon(shared)),
                        other to listOf(DeclaredSiteIcon(shared))
                    )
                ) {
                    SiteIcon(url = url, title = "Site")
                }
            }
        }
        waitFor("the first site's icon") { !letterShown() }

        url = "$other/home"
        composeRule.waitForIdle()
        waitFor("the second site's icon") { composeRule.onAllNodesWithText("O").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun iconsTheSiteNamesLater_replaceFaviconIco() {
        val declared = "https://$host/assets/favicon.png"
        serve(favicon, declared)
        var sites by mutableStateOf(emptyMap<String, List<DeclaredSiteIcon>>())
        composeRule.setContent {
            RemBrowseTheme {
                CompositionLocalProvider(LocalDeclaredSiteIcons provides sites) {
                    SiteIcon(url = page, title = "Site")
                }
            }
        }
        waitFor("/favicon.ico") { !letterShown() }

        // The page is visited and names its icon: the pinned site or tab picks it up
        sites = mapOf("https://$host" to listOf(DeclaredSiteIcon(declared)))
        waitFor("the declared icon") { declared in asked }
        composeRule.waitForIdle()
        assertEquals(listOf(favicon, declared), asked.toList())
        assertTrue("The icon on screen stays while the new one loads", !letterShown())
    }
}
