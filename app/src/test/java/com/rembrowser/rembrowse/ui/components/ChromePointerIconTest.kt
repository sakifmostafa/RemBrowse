package com.rembrowser.rembrowse.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cursor claim that chrome drawn over a terminal or page takes while the mouse is on it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-mdpi")
class ChromePointerIconTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val claim = ChromePointerIconClaim()
    private var present by mutableStateOf(true)

    private fun launch() {
        composeRule.setContent {
            CompositionProvider()
        }
    }

    @androidx.compose.runtime.Composable
    private fun CompositionProvider() {
        CompositionLocalProvider(LocalChromePointerIconClaim provides claim) {
            val own = LocalChromePointerIconClaim.current
            Box(Modifier.size(300.dp)) {
                if (present) {
                    val claimant = rememberPointerIconClaimant(own)
                    Box(
                        Modifier
                            .size(100.dp)
                            .testTag("card")
                            .claimsPointerIcon(own, claimant)
                    )
                }
            }
        }
    }

    @Test
    fun hoveringTheElement_claimsTheCursor() {
        launch()
        assertFalse(claim.isClaimed)
        composeRule.onNodeWithTag("card").performMouseInput { enter(Offset(10f, 10f)) }
        composeRule.waitForIdle()
        assertTrue(claim.isClaimed)
    }

    @Test
    fun movingWithinTheElement_keepsTheClaim() {
        launch()
        composeRule.onNodeWithTag("card").performMouseInput { enter(Offset(10f, 10f)); moveTo(Offset(50f, 50f)) }
        composeRule.waitForIdle()
        assertTrue(claim.isClaimed)
    }

    @Test
    fun movingOffTheElement_releasesTheCursor() {
        launch()
        composeRule.onNodeWithTag("card").performMouseInput { enter(Offset(10f, 10f)) }
        composeRule.waitForIdle()
        assertTrue(claim.isClaimed)
        composeRule.onNodeWithTag("card").performMouseInput { moveTo(Offset(200f, 200f)) }
        composeRule.waitForIdle()
        assertFalse(claim.isClaimed)
    }

    @Test
    fun removingTheElementWhileHovered_releasesTheCursor() {
        launch()
        composeRule.onNodeWithTag("card").performMouseInput { enter(Offset(10f, 10f)) }
        composeRule.waitForIdle()
        assertTrue(claim.isClaimed)
        composeRule.runOnUiThread { present = false }
        composeRule.waitForIdle()
        assertFalse(claim.isClaimed)
    }

    @Test
    fun pressingOnTheElement_draggingOffAndReleasing_doesNotLeaveTheClaimStuck() {
        launch()
        composeRule.onNodeWithTag("card").performMouseInput { enter(Offset(10f, 10f)) }
        composeRule.waitForIdle()
        assertTrue(claim.isClaimed)
        composeRule.onNodeWithTag("card").performMouseInput { press() }
        composeRule.waitForIdle()
        // Dragged off: Exit arrives, but the pressed element still gets the moves that follow
        for (offset in listOf(200f, 220f, 240f)) {
            composeRule.onNodeWithTag("card").performMouseInput { moveTo(Offset(offset, offset)) }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("card").performMouseInput { release() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("card").performMouseInput { moveTo(Offset(250f, 250f)) }
        composeRule.waitForIdle()
        assertFalse(claim.isClaimed)
    }

    @Test
    fun aFingerTapOnTheElement_doesNotClaimTheCursor() {
        launch()
        composeRule.onNodeWithTag("card").performTouchInput {
            down(Offset(10f, 10f))
            moveTo(Offset(14f, 12f))
            up()
        }
        composeRule.waitForIdle()
        assertFalse(claim.isClaimed)
    }
}
