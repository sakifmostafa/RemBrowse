package com.rembrowser.rembrowse.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebResponse
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A site's icon on screen ([SiteIcon]) over the app's image loader and a stand-in for the
 * engine: the icons the site names for itself first, then its `/favicon.ico`, then its letter.
 *
 * Every load runs on one thread of the test's own ([loads]; Coil won't fetch on the main thread)
 * and the stand-in answers at once, so [settle] can run the screen to rest without waiting on the
 * clock. A load's end changes SiteIcon's state outside composition; Compose's test idling sends
 * that change on (Snapshot.sendApplyNotifications). Compose's own process-wide sender can't be
 * relied on for it: in a full test run an earlier test can leave it stuck, and the change then
 * never reaches the screen.
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

    /** The thread every load runs on, and the dispatcher over it. */
    private val loads = Executors.newSingleThreadExecutor { Thread(it, "site-icon-test-loads") }
    private val loadDispatcher = loads.asCoroutineDispatcher()

    /** What the stand-in engine was asked for, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    @OptIn(DelicateCoilApi::class)
    @After
    fun tearDown() {
        SingletonImageLoader.reset()
        loads.shutdownNow()
    }

    /** Serves [found] addresses a PNG and every other address a 404 page. */
    @OptIn(DelicateCoilApi::class)
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
        SingletonImageLoader.setUnsafe(
            iconImageLoader(context, engine, ioDispatcher = loadDispatcher).newBuilder()
                .fetcherCoroutineContext(loadDispatcher)
                .decoderCoroutineContext(loadDispatcher)
                .build()
        )
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

    /**
     * Runs the screen to rest, in rounds. Each round runs a frame (where Coil starts a load, on
     * the composition's test dispatcher), lets [loads] finish what it was handed, and lets
     * Compose's idling apply the state changes, recompose and run the main thread's queue. A load
     * that fails starts the next address in the next round.
     */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            composeRule.mainClock.advanceTimeBy(FRAME_MS)
            // Queued behind the loads already handed over. The limit only guards against a hang.
            loads.submit {}.get(10, TimeUnit.SECONDS)
            composeRule.waitForIdle()
        }
    }

    @Test
    fun aDeclaredIcon_isShownInPlaceOfTheLetter_withoutAskingForFaviconIco() {
        val declared = "https://$host/assets/favicon.png"
        serve(declared)
        show(mapOf("https://$host" to listOf(DeclaredSiteIcon(declared))))

        settle()
        assertFalse(letterShown())
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

        settle()
        assertFalse(letterShown())
        // Two of the site's own icons, the best first, then /favicon.ico
        assertEquals(listOf(first, second, favicon), asked.toList())
    }

    @Test
    fun whenNothingLoads_theLetterStays() {
        val declared = "https://$host/gone.png"
        serve()
        show(mapOf("https://$host" to listOf(DeclaredSiteIcon(declared))))

        settle()
        assertEquals(listOf(declared, favicon), asked.toList())
        assertTrue(letterShown())
    }

    @Test
    fun aSiteThatNamesNoIcon_getsItsFaviconIco_asBefore() {
        serve(favicon)
        show(emptyMap())

        settle()
        assertFalse(letterShown())
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
        settle()
        assertFalse("The first site's icon", letterShown())

        url = "$other/home"
        settle()
        assertTrue("The second site's icon", composeRule.onAllNodesWithText("O").fetchSemanticsNodes().isEmpty())
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
        settle()
        assertFalse(letterShown())

        // The page is visited and names its icon: the pinned site or tab picks it up
        sites = mapOf("https://$host" to listOf(DeclaredSiteIcon(declared)))
        settle()
        assertEquals(listOf(favicon, declared), asked.toList())
        assertTrue("The icon on screen stays while the new one loads", !letterShown())
    }

    private companion object {
        const val SETTLE_ROUNDS = 10
        const val FRAME_MS = 16L
    }
}
