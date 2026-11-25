package com.rembrowser.rembrowse.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
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
    private val taken = WholePageShot.Failed

    private suspend fun shot(maxPixels: Long = budget, askSize: suspend () -> PageContentSize?): WholePageShot =
        wholePageShot(maxPixels, askSize) { width, height ->
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
}
