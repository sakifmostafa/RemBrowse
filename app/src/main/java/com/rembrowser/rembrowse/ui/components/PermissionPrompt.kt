package com.rembrowser.rembrowse.ui.components

import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.PermissionRequest
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.ui.theme.RemMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How long the card's answers ignore input after a request appears in it, so a click or tap
 * aimed at the page just before the card came up cannot answer it (click-jacking).
 */
internal const val PermissionActionDelayMillis = 500L

/** The card's widest; on a narrower pane it takes the pane's width less the insets. */
private val PromptMaxWidth = 400.dp

/** The space between the card and the find bar above it. */
private val PromptGap = 8.dp

/** How the answers look when a wait starts: dimmed, as disabled controls are. */
private const val ActionsWaitingAlpha = 0.38f

/**
 * As far as the answers fade during the wait. Short of full, so they only look ready once they
 * are, whatever the system's animation speed; then they finish fading in.
 */
private const val ActionsAlmostReadyAlpha = 0.7f

/** How far the answers may move on screen (layout rounding, say) without starting the wait again. */
private val ActionsMoveSlack = 4.dp

/**
 * The permission question of the page shown in this pane, if its tab has one waiting: a card
 * that hangs from the pane's top-start corner, where a desktop browser hangs its bubble under
 * the site's padlock. It sits beside the find bar when the pane is wide enough for both, and
 * slides below it otherwise. Call it from the pane's own box, like the find bar.
 *
 * Keyboard focus is never moved into the card, so a key meant for the page cannot answer it.
 *
 * @param request the oldest request of this pane's tab, or null.
 * @param findBarOpen whether this pane's find bar is showing.
 * @param onAnswer answers a request (BrowserViewModel.answerPermission).
 * @param onReturnFocus called when an answer was given with keyboard focus in the card, which
 *   then leaves; focus should go back to the page.
 */
@Composable
fun BoxScope.PermissionPromptOverlay(
    request: PermissionRequest?,
    findBarOpen: Boolean,
    onAnswer: (requestId: String, answer: PermissionAnswer) -> Unit,
    onReturnFocus: () -> Unit = {}
) {
    // What was last shown here, so the card can still be drawn while it animates away
    var shown by remember { mutableStateOf<PermissionRequest?>(null) }
    if (request != null) shown = request
    val reduceMotion = rememberReduceMotion()

    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        // Beside the find bar only if both fit at full width; the card never changes width
        val besideFindBar = !findBarOpen ||
            maxWidth >= FindBarInset * 2 + PromptMaxWidth + PromptGap + FindBarMaxWidth
        val top by animateDpAsState(
            targetValue = if (besideFindBar) FindBarInset else FindBarInset + FindBarHeight + PromptGap,
            animationSpec = if (reduceMotion) snap() else RemMotion.defaultSpatial(),
            label = "permissionPromptTop"
        )
        AnimatedVisibility(
            visible = request != null,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                slideInVertically(RemMotion.defaultSpatial()) { -it / 4 } + fadeIn(RemMotion.defaultEffects())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                slideOutVertically(RemMotion.fastSpatial()) { -it / 4 } + fadeOut(RemMotion.fastEffects())
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, top.roundToPx()) }
                .padding(horizontal = FindBarInset)
        ) {
            shown?.let { current ->
                PermissionPromptCard(
                    request = current,
                    onAnswer = onAnswer,
                    onReturnFocus = onReturnFocus,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.width(min(PromptMaxWidth, maxWidth - FindBarInset * 2).coerceAtLeast(0.dp))
                )
            }
        }
    }
}

/**
 * The card itself: what the site wants, Block, Allow this time (camera, microphone and location
 * only) and Allow, and a close button that refuses this request without remembering anything.
 * Esc closes it too while keyboard focus is in it.
 *
 * A request that follows another in the same card crossfades in, and every new request holds
 * its answers back for [PermissionActionDelayMillis].
 */
@Composable
fun PermissionPromptCard(
    request: PermissionRequest,
    onAnswer: (requestId: String, answer: PermissionAnswer) -> Unit,
    modifier: Modifier = Modifier,
    onReturnFocus: () -> Unit = {},
    reduceMotion: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    var focusInside by remember { mutableStateOf(false) }
    val answer = { id: String, given: PermissionAnswer ->
        onAnswer(id, given)
        if (focusInside) onReturnFocus()
    }

    Surface(
        modifier = modifier
            .onFocusChanged { focusInside = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape) {
                    // Both halves of the press are taken, so the release reaches nothing else
                    if (event.type == KeyEventType.KeyDown) answer(request.id, PermissionAnswer.DISMISS)
                    true
                } else {
                    false
                }
            }
            .testTag("permission_prompt"),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 6.dp
    ) {
        val fadeInSpec = RemMotion.defaultEffects<Float>()
        val fadeOutSpec = RemMotion.fastEffects<Float>()
        AnimatedContent(
            targetState = request,
            contentKey = { it.id },
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    // The old request leaves quicker than the new one arrives
                    fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec)
                }.using(SizeTransform(clip = false))
            },
            label = "permissionPromptContent"
        ) { current ->
            PromptContent(
                request = current,
                onAnswer = { given -> answer(current.id, given) },
                reduceMotion = reduceMotion,
                // A request that follows another fades in; its wait starts once it is all there
                appearing = transition.currentState != EnterExitState.Visible
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptContent(
    request: PermissionRequest,
    onAnswer: (PermissionAnswer) -> Unit,
    reduceMotion: Boolean,
    appearing: Boolean
) {
    val colors = MaterialTheme.colorScheme

    // Each request's answers ignore taps until they have been on screen, in one place, for
    // PermissionActionDelayMillis (see ActionGuard)
    val scope = rememberCoroutineScope()
    val slackPx = with(LocalDensity.current) { ActionsMoveSlack.toPx() }
    val readySpec = RemMotion.fastEffects<Float>()
    val guard = remember(request.id) { ActionGuard(scope, reduceMotion, slackPx, readySpec) }
    LaunchedEffect(guard, appearing) { if (!appearing) guard.onAppeared() }
    // Every frame the window draws, the answers are looked for where they are now: a move of
    // something around them (the card sliding below the find bar) is not reported otherwise
    val view = LocalView.current
    DisposableEffect(guard, view) {
        val observer = view.viewTreeObserver
        val onPreDraw = ViewTreeObserver.OnPreDrawListener {
            guard.checkPosition()
            true
        }
        observer.addOnPreDrawListener(onPreDraw)
        onDispose {
            (observer.takeIf { it.isAlive } ?: view.viewTreeObserver).removeOnPreDrawListener(onPreDraw)
        }
    }
    val answerWhenArmed = { given: PermissionAnswer -> if (guard.takesInput()) onAnswer(given) }

    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(40.dp)
                    .background(colors.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Decorative: the words beside it say what is asked
                Icon(
                    imageVector = permissionIcon(request.kinds),
                    contentDescription = null,
                    tint = colors.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("permission_prompt_message")
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.onSurface)) {
                            append(request.displayHost)
                        }
                        append(" wants to")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = permissionAskText(request.kinds, request.embeddedHost),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Not now (Esc)",
                onClick = { onAnswer(PermissionAnswer.DISMISS) },
                size = 32.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag("permission_dismiss")
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .onGloballyPositioned { guard.onPlaced(it) }
                .drawWithContent {
                    drawContent()
                    guard.onDrawn()
                }
                .graphicsLayer { alpha = guard.displayAlpha },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(
                onClick = { answerWhenArmed(PermissionAnswer.BLOCK) },
                modifier = Modifier.testTag("permission_block")
            ) { Text("Block") }
            if (request.offersAllowOnce) {
                TextButton(
                    onClick = { answerWhenArmed(PermissionAnswer.ALLOW_ONCE) },
                    modifier = Modifier.testTag("permission_allow_once")
                ) { Text("Allow this time") }
            }
            PrimaryDialogButton(
                label = "Allow",
                onClick = { answerWhenArmed(PermissionAnswer.ALLOW) },
                modifier = Modifier.testTag("permission_allow")
            )
        }
    }
}

/**
 * Keeps a request's answers from taking a tap or click aimed at something else (click-jacking):
 * they ignore input until they have been drawn, fully faded in, and left in the same place on
 * screen for [PermissionActionDelayMillis]. Drawn, not composed: a card can be composed some
 * frames before anyone sees it. Any move of more than [ActionsMoveSlack] (the find bar opening
 * above the card, the pane resized, a fullscreen page ending, the answers wrapping differently)
 * starts the wait again, so answers that jump under the pointer cannot be hit at once. Where
 * they are is looked at after layout, on every frame the window draws, and at the tap itself.
 *
 * Meanwhile the answers are dimmed, and fade only part of the way in, so they look ready only
 * once they are. The wait is real time, whatever the system's animation speed.
 */
@Stable
private class ActionGuard(
    private val scope: CoroutineScope,
    private val reduceMotion: Boolean,
    private val slackPx: Float,
    /** How the answers fade the rest of the way in once they take input. */
    private val readySpec: FiniteAnimationSpec<Float>
) {
    /** Whether the wait is over. [takesInput] also makes sure the answers have not moved since. */
    var armed by mutableStateOf(false)
        private set

    private val alpha = Animatable(ActionsWaitingAlpha)

    /**
     * The answers' opacity, read in their graphics layer. Never full while they ignore input,
     * also in the frame a move restarts the wait, before the fade is set back.
     */
    val displayAlpha: Float
        get() = if (armed) alpha.value else minOf(alpha.value, ActionsAlmostReadyAlpha)

    private var appeared = false
    private var drawn = false

    /** The answers' place in the layout, and where they were in the window when the wait began. */
    private var coordinates: LayoutCoordinates? = null
    private var waitStartedAt: Rect? = null
    private var wait: Job? = null

    /** Where the answers are in the window now, or null before they are laid out. */
    private val bounds: Rect?
        get() = coordinates?.takeIf { it.isAttached }?.boundsInWindow()

    /** The content's own fade-in (a request following another) is over. */
    fun onAppeared() {
        appeared = true
        if (drawn && waitStartedAt == null) restart()
    }

    fun onDrawn() {
        drawn = true
        if (appeared && waitStartedAt == null) restart() else checkPosition()
    }

    fun onPlaced(layoutCoordinates: LayoutCoordinates) {
        coordinates = layoutCoordinates
        checkPosition()
    }

    /** Starts the wait again if the answers moved since it began. */
    fun checkPosition() {
        val from = waitStartedAt ?: return
        val now = bounds ?: return
        if (from.movedBeyond(now, slackPx)) restart()
    }

    /** Whether a tap now may answer: the wait is over and the answers are still where it began. */
    fun takesInput(): Boolean {
        checkPosition()
        return armed
    }

    private fun restart() {
        waitStartedAt = bounds ?: Rect.Zero
        // At once, not in the coroutine: a tap in this same frame must already be ignored
        armed = false
        wait?.cancel()
        wait = scope.launch {
            alpha.snapTo(ActionsWaitingAlpha)
            if (!reduceMotion) {
                launch {
                    alpha.animateTo(
                        ActionsAlmostReadyAlpha,
                        tween(PermissionActionDelayMillis.toInt(), easing = LinearEasing)
                    )
                }
            }
            delay(PermissionActionDelayMillis)
            armed = true
            if (reduceMotion) alpha.snapTo(1f) else alpha.animateTo(1f, readySpec)
        }
    }
}

/** Whether any edge of this rectangle and [other] are more than [slack] apart. */
private fun Rect.movedBeyond(other: Rect, slack: Float): Boolean =
    abs(left - other.left) > slack || abs(top - other.top) > slack ||
        abs(right - other.right) > slack || abs(bottom - other.bottom) > slack

/** The outlined icon for what a request asks for; camera wins when both camera and microphone are asked. */
internal fun permissionIcon(kinds: Set<SitePermissionKind>): ImageVector = when {
    SitePermissionKind.CAMERA in kinds -> Icons.Outlined.Videocam
    SitePermissionKind.MICROPHONE in kinds -> Icons.Outlined.Mic
    else -> kinds.firstOrNull()?.icon ?: Icons.Outlined.Videocam
}

/** The outlined icon for one kind, as Settings lists it. */
internal val SitePermissionKind.icon: ImageVector
    get() = when (this) {
        SitePermissionKind.CAMERA -> Icons.Outlined.Videocam
        SitePermissionKind.MICROPHONE -> Icons.Outlined.Mic
        SitePermissionKind.LOCATION -> Icons.Outlined.LocationOn
        SitePermissionKind.NOTIFICATIONS -> Icons.Outlined.Notifications
        SitePermissionKind.PROTECTED_CONTENT -> Icons.Outlined.SmartDisplay
        SitePermissionKind.STORAGE_ACCESS -> Icons.Outlined.Cookie
    }

/**
 * What the site wants, as the second line of the card: "Use your camera and microphone",
 * "Know your location", or for cookies "Let **embedded.example** use its cookies on this site".
 */
internal fun permissionAskText(kinds: Set<SitePermissionKind>, embeddedHost: String?): AnnotatedString {
    val boldHost = SpanStyle(fontWeight = FontWeight.SemiBold)
    val phrases = buildList {
        val camera = SitePermissionKind.CAMERA in kinds
        val microphone = SitePermissionKind.MICROPHONE in kinds
        when {
            camera && microphone -> add(AnnotatedString("use your camera and microphone"))
            camera -> add(AnnotatedString("use your camera"))
            microphone -> add(AnnotatedString("use your microphone"))
        }
        if (SitePermissionKind.LOCATION in kinds) add(AnnotatedString("know your location"))
        if (SitePermissionKind.NOTIFICATIONS in kinds) add(AnnotatedString("show notifications"))
        if (SitePermissionKind.PROTECTED_CONTENT in kinds) add(AnnotatedString("play protected content (DRM)"))
        if (SitePermissionKind.STORAGE_ACCESS in kinds) {
            add(
                buildAnnotatedString {
                    append("let ")
                    if (embeddedHost != null) {
                        withStyle(boldHost) { append(embeddedHost) }
                    } else {
                        append("an embedded site")
                    }
                    append(" use its cookies on this site")
                }
            )
        }
    }
    return buildAnnotatedString {
        phrases.forEachIndexed { index, phrase ->
            when {
                index == 0 -> Unit
                index == phrases.lastIndex -> append(" and ")
                else -> append(", ")
            }
            if (index == 0 && phrase.isNotEmpty()) {
                append(phrase.text.take(1).uppercase())
                append(phrase.subSequence(1, phrase.length))
            } else {
                append(phrase)
            }
        }
    }
}

/** What a decision in Settings is about: "Camera", "Cookies for embedded.example". */
internal fun sitePermissionLabel(kind: SitePermissionKind, embeddedHost: String?): String = when (kind) {
    SitePermissionKind.CAMERA -> "Camera"
    SitePermissionKind.MICROPHONE -> "Microphone"
    SitePermissionKind.LOCATION -> "Location"
    SitePermissionKind.NOTIFICATIONS -> "Notifications"
    SitePermissionKind.PROTECTED_CONTENT -> "Protected content"
    SitePermissionKind.STORAGE_ACCESS -> if (embeddedHost != null) "Cookies for $embeddedHost" else "Cookies for embedded sites"
}

