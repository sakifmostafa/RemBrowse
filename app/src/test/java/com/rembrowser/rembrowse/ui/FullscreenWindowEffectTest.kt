package com.rembrowser.rembrowse.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.ui.components.withFullStop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The window gets its system bars and its size back every time a page leaves fullscreen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FullscreenWindowEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyEnterIsMatchedByAnExit_whenThePageLeavesFullscreen() {
        // A `by` state, as in MainActivity: the old effect is disposed after it already reads false
        var fullscreen by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var enters = 0
        var exits = 0
        composeRule.setContent {
            if (shown) {
                FullscreenWindowEffect(fullscreen = fullscreen, onEnter = { enters++ }, onExit = { exits++ })
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, enters)
        assertEquals(0, exits)

        fullscreen = true
        composeRule.waitForIdle()
        assertEquals(1, enters)
        assertEquals(0, exits)

        fullscreen = false
        composeRule.waitForIdle()
        assertEquals("Leaving fullscreen restores the window", 1, exits)

        fullscreen = true
        composeRule.waitForIdle()
        shown = false
        composeRule.waitForIdle()
        assertEquals(2, enters)
        assertEquals("Leaving the screen while fullscreen restores it too", 2, exits)
    }

    @Test
    fun withFullStop_neverDoublesTheStop() {
        assertEquals("Last checked today at 9:23 a.m.", withFullStop("Last checked today at 9:23 a.m."))
        assertEquals("Last checked today at 09:23.", withFullStop("Last checked today at 09:23"))
    }
}
