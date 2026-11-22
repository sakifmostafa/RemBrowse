package com.rembrowser.rembrowse.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.engine.SwipeDirection
import com.rembrowser.rembrowse.engine.SwipeFeedback

/**
 * The state of the arrow a two-finger swipe shows over a page; the swipe reports to it
 * ([SwipeFeedback]) and [SwipeNavigationIndicator] draws it. One per page pane.
 */
@Stable
class SwipeIndicatorState : SwipeFeedback {
    var direction by mutableStateOf<SwipeDirection?>(null)
        private set

    /** 0 where the swipe started, 1 or more where letting go navigates. */
    var progress by mutableFloatStateOf(0f)
        private set

    var armed by mutableStateOf(false)
        private set

    /** The fingers are down. False once they lift, while the arrow leaves. */
    var active by mutableStateOf(false)
        private set

    /** The last swipe went back or forward. */
    var navigated by mutableStateOf(false)
        private set

    override fun update(direction: SwipeDirection, progress: Float, armed: Boolean) {
        this.direction = direction
        this.progress = progress
        this.armed = armed
        navigated = false
        active = true
    }

    override fun end(navigated: Boolean) {
        this.navigated = navigated
        if (!navigated) armed = false
        active = false
    }
}

private val ArrowCircle = 44.dp
private val ArrowInset = 16.dp

/**
 * A round arrow that slides in from the pane's edge as the fingers move: from the left for Back,
 * from the right for Forward. It fills with the accent colour once letting go navigates. When
 * the fingers lift it slides back out, or, if the swipe navigated, fades where it is. With
 * animations turned off in Android's settings it doesn't slide: it fades in and out in place.
 * Only a picture: it takes no input, and screen readers skip it (Back and Forward are on the
 * toolbar and on Alt+Left and Alt+Right). Laid out left to right in every language, because
 * the swipe's direction is physical: fingers moving right go back, from the left edge.
 */
@Composable
fun SwipeNavigationIndicator(state: SwipeIndicatorState, modifier: Modifier = Modifier) {
    val direction = state.direction ?: return
    val context = LocalContext.current
    val stillMotion = remember(context) { animationsOff(context) }
    val shown by animateFloatAsState(
        targetValue = if (state.active) state.progress.coerceIn(0f, 1f) else 0f,
        // Follows the fingers while they are down; leaves on its own, quickly, once they lift
        animationSpec = if (state.active) snap() else tween(if (state.navigated) 120 else 180, easing = FastOutLinearInEasing),
        label = "swipe arrow"
    )
    if (!state.active && shown == 0f) return

    val colors = MaterialTheme.colorScheme
    val back = direction == SwipeDirection.BACK
    val armed = state.armed
    val pop by animateFloatAsState(
        targetValue = if (armed && !stillMotion) 1.1f else 1f,
        animationSpec = tween(100),
        label = "swipe arrow armed"
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .clearAndSetSemantics { },
            contentAlignment = if (back) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .testTag("swipe_arrow")
                    .graphicsLayer {
                        val hidden = -ArrowCircle.toPx()
                        val rest = ArrowInset.toPx()
                        // Where it sits: off the edge at 0, at rest at 1. A swipe that navigated
                        // stays put while it fades.
                        val place = when {
                            stillMotion -> 1f
                            !state.active && state.navigated -> 1f
                            else -> shown
                        }
                        val x = hidden + (rest - hidden) * place
                        translationX = if (back) x else -x
                        alpha = when {
                            stillMotion -> if (state.active) 0.25f + 0.75f * shown else shown
                            state.active -> 1f
                            else -> shown
                        }
                        scaleX = pop
                        scaleY = pop
                    }
                    .size(ArrowCircle)
                    .shadow(6.dp, CircleShape)
                    .background(if (armed) colors.primary else colors.surfaceContainerHigh, CircleShape)
                    .border(1.dp, if (armed) Color.Transparent else colors.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (back) Icons.AutoMirrored.Outlined.ArrowBack else Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = if (armed) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** Android's animations are turned off (Settings → Accessibility → Remove animations). */
private fun animationsOff(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
