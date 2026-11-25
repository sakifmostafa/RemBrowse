package com.rembrowser.rembrowse.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.PxRect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.PageContentSize
import org.robolectric.annotation.Config

/**
 * A whole page's capture up to the engine ([wholePageShot]): what is asked of it, and when
 * nothing is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WholePageShotTest {

    private val budget = 64_000_000L
    private val asked = mutableListOf<Pair<Int, Int>>()
    private val sources = mutableListOf<PxRect>()
    private val taken = WholePageShot.Failed

    private suspend fun shot(maxPixels: Long = budget, askSize: suspend () -> PageContentSize?): WholePageShot =
        wholePageShot(maxPixels, askSize) { source, width, height ->
            sources += source
            asked += width to height
            taken
        }

    @Test
    fun aPageWithinTheBudget_isAskedForAtItsFullSize() = runTest {
        assertSame(taken, shot { PageContentSize(1280, 10_000, 2f) })
        assertEquals(listOf(2560 to 20_000), asked)
    }

    @Test
    fun aPageOverTheBudget_isAskedForSmaller_neverAtItsFullSize() = runTest {
        shot { PageContentSize(427, 30_000, 3f) }
        val (width, height) = asked.single()
        assertTrue("$width by $height", width.toLong() * height <= budget)
        assertTrue("$width by $height", width < 1281 && height < 90_000)
    }

    @Test
    fun aPageTooLong_isRefused_withNothingDrawn() = runTest {
        assertSame(WholePageShot.TooLong, shot { PageContentSize(427, 200_000, 3f) })
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aPageOnlyTheFreeMemoryRulesOut_isRefusedAsThat_withNothingDrawn() = runTest {
        val result = shot(maxPixels = 4_000_000L) { PageContentSize(1280, 5000, 2f) }
        assertSame(WholePageShot.LowMemory, result)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aPageTheEngineCantSize_isAFailedScreenshot_withNothingDrawn() = runTest {
        assertSame(WholePageShot.Failed, shot { null })
        assertSame(WholePageShot.Failed, shot { PageContentSize(0, 0, 2f) })
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aSizeThatNeverComes_isGivenUpOnAfterFiveSeconds_withNothingDrawn() = runTest {
        val result = shot { awaitCancellation() }

        assertSame(WholePageShot.Failed, result)
        assertEquals(PAGE_SIZE_TIMEOUT_MILLIS, currentTime)
        assertEquals(5_000L, PAGE_SIZE_TIMEOUT_MILLIS)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aSizeThatComesLate_butInTime_isStillUsed() = runTest {
        shot {
            delay(PAGE_SIZE_TIMEOUT_MILLIS - 1)
            PageContentSize(1000, 1000, 1f)
        }
        assertEquals(listOf(1000 to 1000), asked)
    }

    @Test
    fun aPageWhoseViewLostFocus_isInTheEnginesFocusWhileItIsTaken_andOutOfItAfter() = runTest {
        val focus = mutableListOf<Any>()
        val result = withEngineFocus(viewFocused = { false }, setEngineFocus = { focus += it }) {
            focus += "taken"
            7
        }
        assertEquals(7, result)
        assertEquals(listOf(true, "taken", false), focus)
    }

    @Test
    fun aPageWhoseViewHasFocus_isLeftAlone() = runTest {
        val focus = mutableListOf<Boolean>()
        assertEquals(7, withEngineFocus(viewFocused = { true }, setEngineFocus = { focus += it }) { 7 })
        assertTrue(focus.isEmpty())
    }

    @Test
    fun aViewThatGainsFocusMeanwhile_keepsTheEnginesFocus() = runTest {
        val focus = mutableListOf<Boolean>()
        var viewFocused = false
        withEngineFocus(viewFocused = { viewFocused }, setEngineFocus = { focus += it }) { viewFocused = true }
        assertEquals(listOf(true), focus)
    }

    @Test
    fun theEnginesFocusIsGivenBack_whenTheCaptureFailsOrIsCancelled() = runTest {
        val focus = mutableListOf<Boolean>()
        val thrown = runCatching {
            withEngineFocus<Unit>(viewFocused = { false }, setEngineFocus = { focus += it }) {
                throw IllegalStateException("no page")
            }
        }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(true, false), focus)

        focus.clear()
        val capture = launch {
            withEngineFocus(viewFocused = { false }, setEngineFocus = { focus += it }) { awaitCancellation() }
        }
        runCurrent()
        assertEquals(listOf(true), focus)
        capture.cancelAndJoin()
        assertEquals(listOf(true, false), focus)
    }

    /** What the engine makes of a side of the region it is asked for: CSS pixels, rounded down. */
    private fun engineCss(source: Int, ratio: Float): Int = (source / ratio).toInt()

    @Test
    fun theEngineIsToldToDrawThePageAsItWasMeasured_soOneThatGrowsMeanwhileIsNotSqueezedIn() = runTest {
        shot { PageContentSize(1280, 10_000, 2f) }
        assertEquals("From the top left corner, all of it", PxRect(0, 0, 2560, 20_000), sources.single())

        // Over the budget the picture is smaller, the part of the page drawn is not
        sources.clear()
        shot { PageContentSize(427, 30_000, 3f) }
        assertEquals(PxRect(0, 0, 1281, 90_000), sources.single())
    }

    @Test
    fun theRegionAskedFor_comesBackAsExactlyThePagesCssSize_atAnyDensity() = runTest {
        for (ratio in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
            for ((width, height) in listOf(412 to 800, 412 to 12_000, 427 to 5000, 1280 to 700, 980 to 3000)) {
                sources.clear()
                shot { PageContentSize(width, height, ratio) }
                val source = sources.single()
                assertEquals("$width at $ratio", width, engineCss(source.width, ratio))
                assertEquals("$height at $ratio", height, engineCss(source.height, ratio))
            }
        }
    }
}
