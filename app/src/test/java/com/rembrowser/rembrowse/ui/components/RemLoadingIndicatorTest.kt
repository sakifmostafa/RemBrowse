package com.rembrowser.rembrowse.ui.components

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RemLoadingIndicator with Android's animations off (Remove animations, or the animator duration
 * scale at 0): a still hourglass instead of Material's indicator, which goes on changing shape
 * and turning every 650ms even then. Both sizes it is used at: 24dp on a rail tab, 48dp (its
 * own) for the add-on catalogue.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h200dp-mdpi")
class RemLoadingIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    @After
    fun tearDown() {
        setAnimatorScale(1f)
    }

    private fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RemBrowseTheme {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Row {
                        RemLoadingIndicator(modifier = Modifier.size(24.dp))
                        RemLoadingIndicator()
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
    }

    /** The screen over three seconds, every 350ms: Material's indicator changes every 650ms. */
    private fun frames(): List<IntArray> = List(9) { index ->
        if (index > 0) composeRule.mainClock.advanceTimeBy(350)
        composeRule.waitForIdle()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        IntArray(screen.width * screen.height).also { screen.getPixels(it, 0, screen.width, 0, 0, screen.width, screen.height) }
            .also { screen.recycle() }
    }

    private val inProgress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    @Test
    fun withAnimationsOff_itHoldsStill() {
        setAnimatorScale(0f)
        show()

        val frames = frames()
        frames.drop(1).forEachIndexed { index, frame ->
            assertTrue("The screen changed ${(index + 1) * 350}ms in", frame.contentEquals(frames.first()))
        }
        // Something is drawn: the hourglasses, not an empty screen
        assertTrue(frames.first().distinct().size > 1)
        composeRule.onAllNodes(inProgress).fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }

    @Test
    fun withAnimationsOn_itIsMaterialsMovingIndicator() {
        setAnimatorScale(1f)
        show()

        composeRule.onNodeWithTag("loading_indicator_still").assertDoesNotExist()
        val frames = frames()
        assertTrue("Material's indicator moves", frames.drop(1).any { !it.contentEquals(frames.first()) })
        composeRule.onAllNodes(inProgress).fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }
}
