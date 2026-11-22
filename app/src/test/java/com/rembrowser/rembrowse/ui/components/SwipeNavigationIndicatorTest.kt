package com.rembrowser.rembrowse.ui.components

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.engine.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SwipeNavigationIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = SwipeIndicatorState()

    private fun arrow() = composeRule.onNodeWithTag("swipe_arrow", useUnmergedTree = true)

    @Test
    fun state_startsIdle() {
        assertNull(state.direction)
        assertFalse(state.active)
        assertFalse(state.armed)
        assertFalse(state.navigated)
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun update_recordsTheSwipe_andEndKeepsOrClearsArmed() {
        state.update(SwipeDirection.FORWARD, 0.5f, armed = false)
        assertEquals(SwipeDirection.FORWARD, state.direction)
        assertEquals(0.5f, state.progress, 0f)
        assertTrue(state.active)
        assertFalse(state.navigated)

        state.update(SwipeDirection.FORWARD, 1.2f, armed = true)
        assertTrue(state.armed)
        assertEquals(1.2f, state.progress, 0f)

        state.end(navigated = true)
        assertFalse(state.active)
        assertTrue(state.navigated)
        assertTrue("A swipe that navigated stays armed while it fades", state.armed)

        state.update(SwipeDirection.BACK, 1f, armed = true)
        assertFalse("A new swipe starts not navigated", state.navigated)
        state.end(navigated = false)
        assertFalse(state.active)
        assertFalse(state.navigated)
        assertFalse("Called off: no longer armed", state.armed)
    }

    @Test
    fun theComposable_drawsNothingBeforeASwipe_showsTheArrowDuringOne_andIsGoneAfterIt() {
        composeRule.setContent { SwipeNavigationIndicator(state) }
        arrow().assertDoesNotExist()

        composeRule.runOnIdle { state.update(SwipeDirection.BACK, 0.5f, armed = false) }
        composeRule.waitForIdle()
        arrow().assertExists()

        composeRule.runOnIdle { state.end(navigated = false) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        arrow().assertDoesNotExist()
    }

    @Test
    fun theArrow_showsForAForwardSwipeAtNoProgress_andAfterANavigatingSwipeLeaves() {
        composeRule.setContent { SwipeNavigationIndicator(state) }
        composeRule.runOnIdle { state.update(SwipeDirection.FORWARD, 0f, armed = false) }
        composeRule.waitForIdle()
        arrow().assertExists()

        composeRule.runOnIdle { state.update(SwipeDirection.FORWARD, 1f, armed = true) }
        composeRule.runOnIdle { state.end(navigated = true) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        arrow().assertDoesNotExist()
    }
}
