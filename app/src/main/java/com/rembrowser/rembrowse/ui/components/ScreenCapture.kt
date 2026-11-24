package com.rembrowser.rembrowse.ui.components

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.engine.PointerDevices
import com.rembrowser.rembrowse.model.CaptureGeometry
import com.rembrowser.rembrowse.model.CaptureRegion
import com.rembrowser.rembrowse.model.PxRect
import com.rembrowser.rembrowse.ui.theme.RemMotion
import com.rembrowser.rembrowse.util.KeyboardShortcuts
import com.rembrowser.rembrowse.util.ScreenCaptureKey
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Keys of the regions a click on the screenshot overlay takes whole. */
object CaptureRegions {
    /** The tab switcher, drawn over the panes. */
    const val TAB_SWITCHER = "tab_switcher"

    /** The pane showing [tabId]: its page, terminal or Settings. */
    fun pane(tabId: String): String = "pane:$tabId"
}

/** What became of a screenshot. */
sealed interface ScreenshotResult {
    /** It is on the clipboard: [width] by [height] pixels, with a small picture of it for the notice. */
    class Copied(val thumbnail: ImageBitmap?, val width: Int, val height: Int) : ScreenshotResult

    /** Nothing was copied. */
    data object Failed : ScreenshotResult
}

/** The notice for [result]; [serial] tells one notice from the next, so each stays its full time. */
class ScreenshotNotice(val result: ScreenshotResult, val serial: Long)

/**
 * The screenshot shortcut's state, kept with the browser chrome so the activity's keyboard
 * shortcut can start it: idle, choosing what to take (the overlay is up), or taking it (the
 * overlay is gone and the window is being copied). Main thread only.
 */
@Stable
class ScreenCaptureState {
    enum class Phase { IDLE, SELECTING, CAPTURING }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** The window pixels being taken, while [phase] is CAPTURING. */
    var area by mutableStateOf<PxRect?>(null)
        private set

    /** The confirmation (or failure) of the last screenshot, until it leaves. */
    var notice by mutableStateOf<ScreenshotNotice?>(null)
        private set

    private var serial = 0L

    /** Counts requests to take the focused pane from outside the overlay (Enter, read by the activity). */
    var focusedCaptureRequests by mutableIntStateOf(0)
        private set

    // Written as panes are laid out and read only when the overlay is clicked, so a plain map:
    // moving a pane must not recompose anything
    private val regions = LinkedHashMap<String, CaptureRegion>()

    val isSelecting: Boolean get() = phase == Phase.SELECTING

    /** Puts the overlay up, unless a screenshot is already under way. The last notice goes, so it isn't taken. */
    fun start(): Boolean {
        if (phase != Phase.IDLE) return false
        notice = null
        phase = Phase.SELECTING
        return true
    }

    /** Takes the overlay down without taking anything. */
    fun cancel() {
        if (phase == Phase.SELECTING) phase = Phase.IDLE
    }

    /** Asks the overlay to take the focused pane, as Enter does. */
    fun requestFocusedCapture() {
        if (phase == Phase.SELECTING) focusedCaptureRequests++
    }

    /**
     * Counts the screenshots taken. A result is accepted only for the one under way, so one that
     * was given up on (it timed out) can't come back later as if it were the next.
     */
    var generation = 0L
        private set

    /** Takes [area] (window pixels): the overlay goes, and the screen waits for the window to be copied. */
    fun capture(area: PxRect) {
        if (phase != Phase.SELECTING || area.isEmpty) return
        generation++
        this.area = area
        phase = Phase.CAPTURING
    }

    /**
     * Screenshot [generation] is done: [result] is shown in a notice, and another can be taken.
     * False, with nothing changed, for any other screenshot than the one under way.
     */
    fun finish(result: ScreenshotResult, generation: Long): Boolean {
        if (phase != Phase.CAPTURING || generation != this.generation) return false
        area = null
        phase = Phase.IDLE
        notice = ScreenshotNotice(result, ++serial)
        return true
    }

    fun dismissNotice() {
        notice = null
    }

    fun setRegion(key: String, bounds: PxRect, layer: Int = 0) {
        regions[key] = CaptureRegion(key, bounds, layer)
    }

    fun removeRegion(key: String) {
        regions.remove(key)
    }

    fun regions(): List<CaptureRegion> = regions.values.toList()
}

/** Tells [state] where this element is in the window, as the region [key]. Pair it with [CaptureRegionLifetime]. */
fun Modifier.reportCaptureRegion(state: ScreenCaptureState, key: String, layer: Int = 0): Modifier =
    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        state.setRegion(
            key,
            PxRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt()),
            layer
        )
    }

/** Forgets the region [key] when this leaves the screen. */
@Composable
fun CaptureRegionLifetime(state: ScreenCaptureState, key: String) {
    DisposableEffect(state, key) { onDispose { state.removeRegion(key) } }
}

/** Marks the whole of this box (a pane) as the region [key]. */
@Composable
fun BoxScope.CaptureRegionMarker(state: ScreenCaptureState, key: String, layer: Int = 0) {
    CaptureRegionLifetime(state, key)
    Box(modifier = Modifier.matchParentSize().reportCaptureRegion(state, key, layer))
}

/**
 * What had the keyboard when the screenshot overlay took it, to be given it back when the overlay
 * goes, whether it was cancelled or took something.
 */
sealed interface FocusBeforeCapture {
    /** The address bar, with what was typed in it: losing the focus put the page's address back. */
    class AddressBar(val typed: String) : FocusBeforeCapture

    /** The tab switcher's search field. */
    data object TabSearch : FocusBeforeCapture

    /** An Android view: a page's web view, or a terminal. */
    class View(val view: WeakReference<android.view.View>) : FocusBeforeCapture

    /** Nothing in particular: the page in the focused pane gets it. */
    data object Page : FocusBeforeCapture

    /** The tab switcher was open without its search field focused: nothing is focused again. */
    data object Nothing : FocusBeforeCapture
}

/** The overlay's instructions: "Drag to capture an area · Click a page to capture all of it · Esc to cancel". */
internal fun screenCaptureHint(clicks: Boolean, hasKeyboard: Boolean): String = buildList {
    add("Drag to capture an area")
    add(if (clicks) "Click a page to capture all of it" else "Tap a page to capture all of it")
    if (hasKeyboard) add("Esc to cancel")
}.joinToString(" · ")

/** Where the overlay is in the window, and its hint; read by its gestures, so not state. */
private class OverlayFrame {
    var originX = 0
    var originY = 0
    var width = 0
    var height = 0
    var hint: PxRect? = null

    val window: PxRect get() = PxRect(originX, originY, originX + width, originY + height)
}

/**
 * The screenshot overlay, in the spirit of Windows' Snipping Tool: the whole window dims, the
 * pointer becomes a crosshair, and a hint at the top says what to do.
 *
 * - Dragging (mouse, touchpad or finger) draws a selection: a clear window through the dimming
 *   with an accent border and its size in pixels. Letting go takes it.
 * - A click without dragging takes the pane under the pointer (its page, the tab switcher, or
 *   Settings), or the whole window over the toolbar and rail. With a mouse, the pane a click would
 *   take is lit up as the pointer moves over it.
 * - Esc, a right click or the hint's close button cancels; Enter takes the focused pane.
 *
 * Call it over everything else in the window. It takes the keyboard focus while it is up (the
 * caller gives it back afterwards, see [FocusBeforeCapture]), and is announced to screen readers
 * as a pane with actions for each choice.
 *
 * @param focusedRegionKey what Enter takes: the tab switcher when it is open, else the active pane.
 */
@Composable
fun ScreenCaptureOverlay(
    state: ScreenCaptureState,
    focusedRegionKey: String?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val hasKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
    val clicks = remember(context) { hasKeyboard || PointerDevices.hasMouseOrTouchpad(context) }
    val hint = screenCaptureHint(clicks = clicks, hasKeyboard = hasKeyboard)

    val frame = remember { OverlayFrame() }
    // Both in window pixels; read while drawing, so a drag only redraws
    var selection by remember { mutableStateOf<PxRect?>(null) }
    var hovered by remember { mutableStateOf<PxRect?>(null) }

    val latestFocusedKey by rememberUpdatedState(focusedRegionKey)
    val take: (PxRect) -> Unit = { area -> state.capture(area) }
    val cancel: () -> Unit = { state.cancel() }
    val takeFocused = { take(CaptureGeometry.focusedTarget(latestFocusedKey, state.regions(), frame.window)) }
    val takeWindow = { take(frame.window) }

    // The pages and terminals underneath keep their own cursors to themselves meanwhile
    val cursorClaim = LocalChromePointerIconClaim.current
    val claimant = remember { Any() }
    DisposableEffect(cursorClaim) {
        cursorClaim.set(claimant, true)
        onDispose { cursorClaim.set(claimant, false) }
    }

    // Enter, when the activity read it before it reached the overlay
    val requestsAtStart = remember { state.focusedCaptureRequests }
    LaunchedEffect(state.focusedCaptureRequests) {
        if (state.focusedCaptureRequests != requestsAtStart) takeFocused()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A menu or the command palette that started this hands window focus back first
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, RemMotion.defaultEffects()) }

    val scrim = colors.scrim.copy(alpha = CaptureScrimAlpha)
    val accent = colors.primary
    val halo = colors.onPrimary

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                frame.originX = origin.x.roundToInt()
                frame.originY = origin.y.roundToInt()
                frame.width = coordinates.size.width
                frame.height = coordinates.size.height
            }
            .graphicsLayer { alpha = appear.value }
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(state) {
                val touchSlop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val x = change.position.x + frame.originX
                        val y = change.position.y + frame.originY
                        if (event.type == PointerEventType.Exit) {
                            hovered = null
                            continue
                        }
                        if (!change.changedToDown()) {
                            // A mouse moving with nothing pressed: light up what a click would take
                            if (!change.pressed) hovered = CaptureGeometry.regionAt(x, y, state.regions())?.bounds?.intersect(frame.window)
                            continue
                        }
                        // The hint's close button took it
                        if (change.isConsumed) continue
                        val isMouse = change.type == PointerType.Mouse
                        // A right (or middle) click cancels; Android reports a touchpad's right click as a finger with that button down
                        if (event.buttons.hasOtherThanPrimary()) {
                            change.consume()
                            cancel()
                            continue
                        }
                        if (isMouse && !event.buttons.isPrimaryPressed) continue
                        change.consume()

                        val threshold = CaptureGeometry.dragThresholdPx(isMouse, touchSlop, density.density)
                        var dragging = false
                        var released = false
                        var cancelled = false
                        while (true) {
                            val next = awaitPointerEvent()
                            val moved = next.changes.firstOrNull { it.id == change.id } ?: break
                            if (next.buttons.hasOtherThanPrimary()) {
                                cancelled = true
                                moved.consume()
                                break
                            }
                            val nx = moved.position.x + frame.originX
                            val ny = moved.position.y + frame.originY
                            if (!dragging && CaptureGeometry.isDrag(x, y, nx, ny, threshold)) {
                                dragging = true
                                hovered = null
                            }
                            if (dragging) selection = CaptureGeometry.selection(x, y, nx, ny, frame.window)
                            moved.consume()
                            if (!moved.pressed) {
                                released = true
                                break
                            }
                        }
                        val chosen = selection
                        selection = null
                        when {
                            cancelled -> cancel()
                            !released -> Unit
                            !dragging -> take(CaptureGeometry.clickTarget(x, y, state.regions(), frame.window))
                            // Too small to mean anything: stay, for another try
                            chosen != null && CaptureGeometry.isBigEnough(chosen, density.density) -> take(chosen)
                        }
                    }
                }
            }
            // Before the focus target, so its keys pass through here
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val device = key.device
                val physical = device != null && !device.isVirtual && key.isFromSource(InputDevice.SOURCE_KEYBOARD)
                when (KeyboardShortcuts.screenCaptureKey(key.keyCode, key.isSystem, physical)) {
                    ScreenCaptureKey.CANCEL -> {
                        if (event.type == KeyEventType.KeyDown) cancel()
                        true
                    }
                    ScreenCaptureKey.CAPTURE_FOCUSED -> {
                        if (event.type == KeyEventType.KeyDown) takeFocused()
                        true
                    }
                    // Nothing reaches the page or terminal underneath while choosing
                    ScreenCaptureKey.SWALLOW -> true
                    ScreenCaptureKey.PASS -> false
                }
            }
            .focusRequester(focus)
            .focusable()
            .semantics {
                paneTitle = "Take screenshot"
                // The hint itself is read from the pill; dragging has these actions instead
                contentDescription = "Choose what to capture"
                customActions = listOf(
                    CustomAccessibilityAction("Capture the focused pane") {
                        takeFocused()
                        true
                    },
                    CustomAccessibilityAction("Capture the whole window") {
                        takeWindow()
                        true
                    }
                )
                dismiss(label = "Cancel screenshot") {
                    cancel()
                    true
                }
            }
            .testTag("screen_capture_overlay")
            .drawBehind {
                val cut = selection ?: hovered
                if (cut == null) {
                    drawRect(scrim)
                    return@drawBehind
                }
                val clear = Rect(
                    (cut.left - frame.originX).toFloat(),
                    (cut.top - frame.originY).toFloat(),
                    (cut.right - frame.originX).toFloat(),
                    (cut.bottom - frame.originY).toFloat()
                )
                val dimmed = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRect(clear)
                }
                drawPath(dimmed, scrim)
                // A drawn selection is outlined outside, so its border never covers what is taken;
                // a pane is outlined inside, so the border stays in view at the window's edges
                drawCaptureOutline(clear, accent, halo, outside = selection != null)
            }
    ) {
        selection?.let { chosen ->
            val gap = with(density) { LabelGap.roundToPx() }
            Layout(
                content = { SelectionSizeLabel(chosen) },
                modifier = Modifier.matchParentSize()
            ) { measurables, constraints ->
                val label = measurables.first().measure(Constraints())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val position = CaptureGeometry.labelPosition(
                        selection = chosen.offset(-frame.originX, -frame.originY),
                        labelWidth = label.width,
                        labelHeight = label.height,
                        bounds = PxRect(0, 0, constraints.maxWidth, constraints.maxHeight),
                        gap = gap
                    )
                    label.place(position.x, position.y)
                }
            }
        }

        // Out of the way while a selection reaches under it; there is no layout to shift
        val hintCovered = selection?.let { chosen -> frame.hint?.let { chosen.intersect(it) != null } } == true
        val hintAlpha by animateFloatAsState(
            targetValue = if (hintCovered) 0f else 1f,
            animationSpec = if (reduceMotion) snap() else RemMotion.fastEffects(),
            label = "captureHintAlpha"
        )
        CaptureHint(
            text = hint,
            onCancel = cancel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    frame.hint = PxRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                }
                .graphicsLayer { alpha = hintAlpha }
        )
    }
}

/**
 * An accent outline with a hairline beside it in the accent's own contrast colour, so it reads
 * on any page: the accent against one kind of page, the hairline against the other.
 */
private fun DrawScope.drawCaptureOutline(rect: Rect, accent: Color, halo: Color, outside: Boolean) {
    val accentWidth = OutlineWidth.toPx()
    val haloWidth = HaloWidth.toPx()
    val direction = if (outside) 1f else -1f
    val accentInset = direction * accentWidth / 2f
    val haloInset = direction * (accentWidth + haloWidth / 2f)
    fun Rect.grown(by: Float) = Rect(left - by, top - by, right + by, bottom + by)
    val accentRect = rect.grown(accentInset)
    val haloRect = rect.grown(haloInset)
    drawRect(halo, topLeft = haloRect.topLeft, size = haloRect.size, style = Stroke(haloWidth))
    drawRect(accent, topLeft = accentRect.topLeft, size = accentRect.size, style = Stroke(accentWidth))
}

/** "Drag to capture an area · …", with a close button: a tonal pill like the find bar. */
@Composable
private fun CaptureHint(text: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .widthIn(max = 640.dp)
            .pointerHoverIcon(PointerIcon.Default)
            // A press on the hint is not the start of a selection
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
                    }
                }
            }
            .testTag("screen_capture_hint"),
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.HighlightAlt,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            VerticalDivider(modifier = Modifier.height(20.dp), color = colors.outlineVariant)
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Cancel screenshot (Esc)",
                onClick = onCancel,
                size = 32.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag("screen_capture_cancel")
            )
        }
    }
}

/** "640 × 480" beside the selection, in the accent's container colours; figures don't jiggle as they change. */
@Composable
private fun SelectionSizeLabel(selection: PxRect) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = CaptureGeometry.sizeLabel(selection),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = colors.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("screen_capture_size")
            // "640 by 480 pixels" rather than "640 times 480"
            .clearAndSetSemantics { contentDescription = "Selection, ${CaptureGeometry.sizeDescription(selection)}" }
    )
}

/**
 * "Screenshot copied", with a small picture of it, over the bottom of the window like the download
 * notice; or a plain message when it failed, with the error icon (named "Error"). It leaves by
 * itself after a few seconds.
 */
@Composable
fun ScreenshotNoticeBar(
    notice: ScreenshotNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (notice == null) return
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(notice.serial) {
        delay(SCREENSHOT_NOTICE_MILLIS)
        latestOnDismiss()
    }
    val copied = notice.result as? ScreenshotResult.Copied

    NoticeSnackbar(
        testTag = "screenshot_notice",
        dismissLabel = "Dismiss",
        onDismiss = onDismiss,
        modifier = modifier,
        dismissTestTag = "screenshot_notice_dismiss"
    ) {
        if (copied != null) {
            ScreenshotThumbnail(copied)
        } else {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = if (copied != null) "Screenshot copied" else "Couldn't take the screenshot",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (copied != null) "${copied.width} × ${copied.height} px" else "Try again with Ctrl+Alt+S",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The screenshot, small, at a fixed height and a width that follows its shape within limits. */
@Composable
private fun ScreenshotThumbnail(copied: ScreenshotResult.Copied) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    val aspect = if (copied.height > 0) copied.width.toFloat() / copied.height else 1f
    val width = (ThumbnailHeight * aspect).coerceIn(ThumbnailMinWidth, ThumbnailMaxWidth)
    Box(
        modifier = Modifier
            .size(width = width, height = ThumbnailHeight)
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant, shape)
            .testTag("screenshot_thumbnail"),
        contentAlignment = Alignment.Center
    ) {
        copied.thumbnail?.let { picture ->
            // The text beside it says what it is
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** How dark the window goes under the overlay; the hint and the size label are opaque, so they read on any page. */
internal const val CaptureScrimAlpha = 0.45f
private val OutlineWidth = 2.dp
private val HaloWidth = 1.dp
private val LabelGap = 8.dp
private val ThumbnailHeight = 40.dp
private val ThumbnailMinWidth = 28.dp
private val ThumbnailMaxWidth = 72.dp
private const val SCREENSHOT_NOTICE_MILLIS = 4_000L
