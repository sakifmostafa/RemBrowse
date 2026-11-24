package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The theme's System / Light / Dark as a connected button group: side by side and connected while
 * they fit, and separate round buttons on two lines when they don't (200% on a phone), never a
 * connected group broken over two lines with square corners at the break.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h400dp-mdpi", fontScale = 2.0f)
class ConnectedChoiceGroupTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf("System", "Light", "Dark")
    private var background = Color.Unspecified

    private fun show(width: Dp, initial: String = "System") {
        composeRule.setContent {
            var selected by mutableStateOf(initial)
            RemBrowseTheme {
                background = MaterialTheme.colorScheme.background
                Box(modifier = Modifier.fillMaxSize().background(background).padding(16.dp)) {
                    ConnectedChoiceGroup(
                        options = options,
                        selected = selected,
                        onSelect = { selected = it },
                        label = { it },
                        testTag = { "theme_${it.lowercase()}" },
                        modifier = Modifier.width(width)
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(option: String): Rect {
        val node = composeRule.onNodeWithTag("theme_${option.lowercase()}").fetchSemanticsNode()
        val position = node.positionOnScreen
        return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    private fun px(dp: Float) = dp * composeRule.density.density

    /**
     * Whether [option]'s ends are round: the screen's pixel 1dp in from its side, 10dp down from
     * its top, is the background there for a 40dp-tall round end, the button's fill for a square
     * one (corners of 8dp or less).
     */
    private fun roundEnds(option: String): Pair<Boolean, Boolean> {
        composeRule.waitForIdle()
        val box = bounds(option)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val y = (box.top + px(10f)).roundToInt()
        fun isBackground(x: Float) = Color(screen.getPixel(x.roundToInt(), y)).let {
            abs(it.red - background.red) < 0.03f && abs(it.green - background.green) < 0.03f && abs(it.blue - background.blue) < 0.03f
        }
        return isBackground(box.left + px(1f)) to isBackground(box.right - px(1f) - 1)
    }

    @Test
    fun withRoom_theyAreConnectedOnOneLine() {
        show(width = 560.dp)

        val (system, light, dark) = options.map(::bounds)
        assertEquals(system.top, light.top)
        assertEquals(light.top, dark.top)
        // Material's connected group: 2dp between the buttons
        assertEquals(px(2f), light.left - system.right, 1f)
        assertEquals(px(2f), dark.left - light.right, 1f)
        // Round at the group's two ends only
        assertEquals("Light, in the middle, is square at both ends", false to false, roundEnds("Light"))
        assertEquals("Dark, at the end, is round on its outer side", false to true, roundEnds("Dark"))
    }

    @Test
    fun withoutRoom_theyAreSeparateRoundButtons_onTwoLines() {
        show(width = 300.dp)

        val (system, light, dark) = options.map(::bounds)
        assertTrue("They no longer fit on one line", dark.top > system.top)
        if (light.top == system.top) {
            assertEquals("Separate buttons, 8dp apart", px(8f), light.left - system.right, 1f)
        }
        // No square corners at the break: the ones not chosen are round at both ends
        assertEquals("Light", true to true, roundEnds("Light"))
        assertEquals("Dark", true to true, roundEnds("Dark"))
    }

    @Test
    fun choosingAnother_keepsTheForm_andItIsStillAGroupOfRadioButtons() {
        show(width = 300.dp)
        val before = options.map(::bounds)

        composeRule.onNodeWithTag("theme_dark").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("theme_dark").assertIsOn()
        assertNotEquals("Still on two lines", before[0].top, bounds("Dark").top)
        assertEquals(
            3,
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .fetchSemanticsNodes().size
        )
    }
}
