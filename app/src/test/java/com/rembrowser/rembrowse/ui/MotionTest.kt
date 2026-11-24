package com.rembrowser.rembrowse.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.FindInPageState
import com.rembrowser.rembrowse.ui.components.FindBarInset
import com.rembrowser.rembrowse.ui.components.FindInPageActions
import com.rembrowser.rembrowse.ui.components.FindInPageOverlay
import com.rembrowser.rembrowse.ui.components.rememberReduceMotion
import com.rembrowser.rembrowse.ui.theme.RemBrowseTheme
import com.rembrowser.rembrowse.ui.theme.RemMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * RemBrowse's motion: Material 3's standard springs, and Android's "Remove animations" setting,
 * read in one place, leaving the motion out (the find bar is the example).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class MotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        // As Android's settings provider does for a change
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    @After
    fun tearDown() {
        setAnimatorScale(1f)
    }

    @Test
    fun remMotion_isMaterialsStandardMotionScheme() {
        // material3 1.4 keeps its motion scheme internal, so it is read by reflection; the
        // standard scheme's JVM name carries the module's
        val scheme = Class.forName("androidx.compose.material3.MotionScheme")
        val companion = scheme.getField("Companion").get(null)
        val standard = companion.javaClass.methods
            .first { it.name == "standard" || it.name.startsWith("standard$") }
            .invoke(companion)
        fun spec(name: String): Any = scheme.getMethod(name).invoke(standard)!!
        val pairs: List<Pair<Any, FiniteAnimationSpec<Float>>> = listOf(
            spec("fastSpatialSpec") to RemMotion.fastSpatial(),
            spec("defaultSpatialSpec") to RemMotion.defaultSpatial(),
            spec("slowSpatialSpec") to RemMotion.slowSpatial(),
            spec("fastEffectsSpec") to RemMotion.fastEffects(),
            spec("defaultEffectsSpec") to RemMotion.defaultEffects(),
            spec("slowEffectsSpec") to RemMotion.slowEffects()
        )
        // SpringSpec compares its damping, stiffness and threshold
        pairs.forEach { (material, ours) -> assertEquals(material, ours) }
    }

    @Test
    fun reduceMotion_followsAndroidsSetting_whileShown() {
        setAnimatorScale(1f)
        var reduced: Boolean? = null
        composeRule.setContent { reduced = rememberReduceMotion() }
        composeRule.runOnIdle { assertEquals(false, reduced) }

        setAnimatorScale(0f)
        composeRule.runOnIdle { assertEquals(true, reduced) }

        // Slower animations are still animations
        setAnimatorScale(0.5f)
        composeRule.runOnIdle { assertEquals(false, reduced) }
    }

    private val noActions = FindInPageActions({}, {}, {}, {}, {})

    /** The find bar's top a frame after it is opened over a page. */
    private fun findBarTopOneFrameAfterOpening(): Float {
        var find by mutableStateOf<FindInPageState?>(null)
        composeRule.setContent {
            RemBrowseTheme {
                Box(Modifier.size(800.dp, 600.dp)) {
                    FindInPageOverlay(tabId = "a", find = find, actions = noActions)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            find = FindInPageState(tabId = "a")
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        return composeRule.onNodeWithTag("find_bar").getBoundsInRoot().top.value
    }

    @Test
    fun findBar_withAnimationsOn_slidesDownIntoPlace() {
        setAnimatorScale(1f)
        val top = findBarTopOneFrameAfterOpening()
        assertTrue("Still on its way down: $top", top < FindBarInset.value)

        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(FindBarInset.value, composeRule.onNodeWithTag("find_bar").getBoundsInRoot().top.value, 0.5f)
    }

    @Test
    fun findBar_withAnimationsOff_isInPlaceAtOnce() {
        setAnimatorScale(0f)
        val top = findBarTopOneFrameAfterOpening()
        assertEquals(FindBarInset.value, top, 0.5f)
        assertNotEquals("Drawn", 0f, composeRule.onNodeWithTag("find_bar").getBoundsInRoot().let { it.bottom - it.top }.value)
    }
}
