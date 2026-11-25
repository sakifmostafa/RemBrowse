package com.rembrowser.rembrowse.ui.components

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
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

    private const val PANE = "pane:"

    /** The pane showing [tabId]: its page, terminal or Settings. */
    fun pane(tabId: String): String = "$PANE$tabId"

    /** The tab a pane's region [key] shows, or null for a region that is no pane. */
    fun tabIdOf(key: String): String? = key.takeIf { it.startsWith(PANE) }?.removePrefix(PANE)
}

/** What became of a screenshot, or of saving one. */
sealed interface ScreenshotResult {
    /**
     * It is on the clipboard: [width] by [height] pixels, with a small picture of it for the
     * notice. A [wholePage] (scrolled-out parts included) can be saved in Downloads from the notice
     * too, and its picture is the top of the page.
     */
    class Copied(
        val thumbnail: ImageBitmap?,
        val width: Int,
        val height: Int,
        val wholePage: Boolean = false
    ) : ScreenshotResult

    /** Nothing was copied. */
    data object Failed : ScreenshotResult

    /** A whole page too long to take, even smaller ([CaptureGeometry.wholePagePlan]): nothing was copied. */
    data object TooLong : ScreenshotResult

    /** The whole page from the notice was saved in Downloads as [fileName]. */
    class Saved(val fileName: String) : ScreenshotResult

    /** The whole page from the notice couldn't be saved in Downloads. */
    data object NotSaved : ScreenshotResult
}

/** The notice for [result]; [serial] tells one notice from the next, so each stays its full time. */
class ScreenshotNotice(val result: ScreenshotResult, val serial: Long)

/**
 * The screenshot shortcut's state, kept with the browser chrome so the activity's keyboard
 * shortcut can start it: idle, choosing what to take (the overlay is up), or taking it (the
 * overlay is gone and the window is being copied, or the engine is drawing a whole page). Main
 * thread only.
 */
@Stable
class ScreenCaptureState {
    enum class Phase { IDLE, SELECTING, CAPTURING }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** The window pixels being taken, while [phase] is CAPTURING an area of the window. */
    var area by mutableStateOf<PxRect?>(null)
        private set

    /** The tab whose whole page is being taken, while [phase] is CAPTURING a whole page. */
    var wholePageTabId by mutableStateOf<String?>(null)
        private set

    /** Whether the whole page in the notice is being saved in Downloads (see [isSaving]). */
    var saving by mutableStateOf(false)
        private set

    // The notice whose whole page is being saved
    private var savingSerial = -1L

    /** The confirmation (or failure) of the last screenshot, until it leaves. */
    var notice by mutableStateOf<ScreenshotNotice?>(null)
        private set

    private var serial = 0L

    /** Counts requests to take the focused pane from outside the overlay (Enter, read by the activity). */
    var focusedCaptureRequests by mutableIntStateOf(0)
        private set

    /** Counts requests to take a whole page from outside the overlay (P, read by the activity). */
    var wholePageRequests by mutableIntStateOf(0)
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

    /** Asks the overlay to take a whole page, as P does: the pane under the mouse's, or the focused pane's. */
    fun requestWholePageCapture() {
        if (phase == Phase.SELECTING) wholePageRequests++
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
        wholePageTabId = null
        phase = Phase.CAPTURING
    }

    /**
     * Takes the whole page shown in [tabId], scrolled-out parts included: from the overlay, which
     * goes, or straight from the command palette. The engine draws the page by itself, so the
     * notice can say meanwhile that it is being taken. False while another screenshot is being
     * taken.
     */
    fun captureWholePage(tabId: String): Boolean {
        if (phase == Phase.CAPTURING) return false
        notice = null
        generation++
        area = null
        wholePageTabId = tabId
        phase = Phase.CAPTURING
        return true
    }

    /** Gives up on the whole page being taken (the notice's Cancel): nothing is copied, and no notice follows. */
    fun cancelWholePage() {
        if (phase != Phase.CAPTURING || wholePageTabId == null) return
        wholePageTabId = null
        phase = Phase.IDLE
    }

    /**
     * Screenshot [generation] is done: [result] is shown in a notice, and another can be taken.
     * False, with nothing changed, for any other screenshot than the one under way.
     */
    fun finish(result: ScreenshotResult, generation: Long): Boolean {
        if (phase != Phase.CAPTURING || generation != this.generation) return false
        area = null
        wholePageTabId = null
        phase = Phase.IDLE
        notice = ScreenshotNotice(result, ++serial)
        return true
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * Starts saving the whole page in the notice in Downloads (its Save button). False, with
     * nothing changed, when the notice holds no whole page or one is already being saved.
     */
    fun startSaving(): Boolean {
        val current = notice ?: return false
        if (saving || (current.result as? ScreenshotResult.Copied)?.wholePage != true) return false
        saving = true
        savingSerial = current.serial
        return true
    }

    /** Whether [notice]'s whole page is the one being saved. */
    fun isSaving(notice: ScreenshotNotice): Boolean = saving && notice.serial == savingSerial

    /**
     * The save is done: [result] says so in a notice of its own, in place of the one it came from
     * (or of none, if that one has left). A notice of a later screenshot stays, and nothing is
     * shown while another screenshot is being chosen or taken.
     */
    fun finishSaving(result: ScreenshotResult) {
        if (!saving) return
        saving = false
        val current = notice
        if (phase == Phase.IDLE && (current == null || current.serial == savingSerial)) {
            notice = ScreenshotNotice(result, ++serial)
        }
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
 * goes, whether it was cancelled or took something. The expanded tab rail over the page notes
 * and gives it back the same way (BrowserChromeState.focusBeforeTabRail).
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

/** Where the overlay is in the window, its hint, and the region under the mouse; read by its gestures and keys, so not state. */
private class OverlayFrame {
    var originX = 0
    var originY = 0
    var width = 0
    var height = 0
    var hint: PxRect? = null
    var hoveredKey: String? = null

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
 * - The whole page, scrolled-out parts included: the hint's Whole page button takes the focused
 *   pane's, offered when that pane shows a web page; P takes the one under the mouse, else the
 *   focused one (see [CaptureGeometry.wholePageTarget]).
 *
 * Call it over everything else in the window. It takes the keyboard focus while it is up (the
 * caller gives it back afterwards, see [FocusBeforeCapture]), and is announced to screen readers
 * as a pane with actions for each choice.
 *
 * @param focusedRegionKey what Enter takes: the tab switcher when it is open, else the active pane.
 * @param wholePageKeys the panes (their region keys) that show a web page, whose whole page can be taken.
 */
@Composable
fun ScreenCaptureOverlay(
    state: ScreenCaptureState,
    focusedRegionKey: String?,
    modifier: Modifier = Modifier,
    wholePageKeys: Set<String> = emptySet()
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

    val latestPageKeys by rememberUpdatedState(wholePageKeys)
    val wholePageOffered = focusedRegionKey != null && focusedRegionKey in wholePageKeys
    val takeWholePageOf: (String?) -> Unit = { key ->
        key?.let(CaptureRegions::tabIdOf)?.let { state.captureWholePage(it) }
    }
    // The button and screen readers: the focused pane's page
    val takeFocusedWholePage = { takeWholePageOf(latestFocusedKey?.takeIf { it in latestPageKeys }) }
    // P: the page under the mouse, else the focused one; nothing over a terminal or the start page
    val takeWholePageByKey = {
        takeWholePageOf(CaptureGeometry.wholePageTarget(frame.hoveredKey, latestFocusedKey, latestPageKeys))
    }

    // The pages and terminals underneath keep their own cursors to themselves meanwhile
    val cursorClaim = LocalChromePointerIconClaim.current
    val claimant = remember { Any() }
    DisposableEffect(cursorClaim) {
        cursorClaim.set(claimant, true)
        onDispose { cursorClaim.set(claimant, false) }
    }

    // Enter and P, when the activity read them before they reached the overlay
    val requestsAtStart = remember { state.focusedCaptureRequests }
    LaunchedEffect(state.focusedCaptureRequests) {
        if (state.focusedCaptureRequests != requestsAtStart) takeFocused()
    }
    val wholePageRequestsAtStart = remember { state.wholePageRequests }
    LaunchedEffect(state.wholePageRequests) {
        if (state.wholePageRequests != wholePageRequestsAtStart) takeWholePageByKey()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A menu or the command palette that started this hands window focus back first
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val appearSpec = RemMotion.defaultEffects<Float>()
    LaunchedEffect(Unit) { appear.animateTo(1f, appearSpec) }

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
                            frame.hoveredKey = null
                            continue
                        }
                        if (!change.changedToDown()) {
                            // A mouse moving with nothing pressed: light up what a click would take
                            if (!change.pressed) {
                                val region = CaptureGeometry.regionAt(x, y, state.regions())
                                frame.hoveredKey = region?.key
                                hovered = region?.bounds?.intersect(frame.window)
                            }
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
                                frame.hoveredKey = null
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
                when (KeyboardShortcuts.screenCaptureKey(key.keyCode, key.isSystem, noModifiers = key.hasNoModifiers())) {
                    ScreenCaptureKey.CANCEL -> {
                        if (event.type == KeyEventType.KeyDown) cancel()
                        true
                    }
                    ScreenCaptureKey.CAPTURE_FOCUSED -> {
                        if (event.type == KeyEventType.KeyDown) takeFocused()
                        true
                    }
                    ScreenCaptureKey.CAPTURE_WHOLE_PAGE -> {
                        if (event.type == KeyEventType.KeyDown) takeWholePageByKey()
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
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Capture the focused pane") {
                        takeFocused()
                        true
                    },
                    CustomAccessibilityAction("Capture the whole window") {
                        takeWindow()
                        true
                    },
                    if (wholePageOffered) {
                        CustomAccessibilityAction("Capture the whole page") {
                            takeFocusedWholePage()
                            true
                        }
                    } else {
                        null
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
            onWholePage = if (wholePageOffered) takeFocusedWholePage else null,
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

/**
 * "Drag to capture an area · …", with the Whole page button when [onWholePage] is given, and a
 * close button: a tonal pill like the find bar. Where the button doesn't fit beside the words
 * (a phone-sized window), it goes under them.
 */
@Composable
private fun CaptureHint(
    text: String,
    onCancel: () -> Unit,
    onWholePage: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier) {
        val buttonBelow = onWholePage != null && maxWidth < HintButtonBesideFrom
        Surface(
            modifier = Modifier
                .widthIn(max = HintMaxWidth)
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
            // Two rows round off less, so the corners stay clear of the words
            shape = if (buttonBelow) MaterialTheme.shapes.extraLarge else CircleShape,
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                Row(
                    modifier = Modifier.heightIn(min = 44.dp),
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
                    if (onWholePage != null && !buttonBelow) {
                        WholePageButton(onClick = onWholePage)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
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
                if (onWholePage != null && buttonBelow) {
                    // Its words in line with the hint's (the button's own padding is 12dp)
                    WholePageButton(
                        onClick = onWholePage,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * "Whole page": takes the focused pane's whole page, scrolled-out parts included. A labelled
 * Material text button, 48dp high, in the accent colour; its tooltip names the key (P) that does
 * the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WholePageButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // TooltipBox wraps its anchor in a box of its own, which would swallow the caller's padding
    Box(modifier = modifier) {
        TooltipBox(
            // The hint is at the top of the window: below it there is room
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("Capture the whole page (${KeyboardShortcuts.WHOLE_PAGE_KEY})") } },
            state = rememberTooltipState(),
            focusable = false
        ) {
            TextButton(
                onClick = onClick,
                modifier = Modifier
                    .height(WholePageButtonHeight)
                    .testTag("screen_capture_whole_page"),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(text = "Whole page", maxLines = 1)
            }
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
 * The notice's words for [result]: what happened, and a line under it (or none). [saving]: the
 * whole page in it is being saved in Downloads.
 */
internal fun screenshotNoticeText(result: ScreenshotResult, saving: Boolean = false): Pair<String, String?> = when (result) {
    is ScreenshotResult.Copied -> Pair(
        if (result.wholePage) "Whole page copied" else "Screenshot copied",
        if (saving) "Saving to Downloads…" else "${result.width} × ${result.height} px"
    )
    ScreenshotResult.Failed -> "Couldn't take the screenshot" to "Try again with Ctrl+Alt+S"
    ScreenshotResult.TooLong -> "This page is too long to capture whole" to "Capture part of it with Ctrl+Alt+S"
    is ScreenshotResult.Saved -> "Saved to Downloads" to result.fileName
    ScreenshotResult.NotSaved -> "Couldn't save the screenshot" to null
}

/**
 * "Screenshot copied", with a small picture of it, over the bottom of the window like the download
 * notice; or a plain message when it failed, with the error icon (named "Error"). It leaves by
 * itself after a few seconds.
 *
 * A whole page ([ScreenshotResult.Copied.wholePage]) adds **Save**, which puts it in Downloads
 * ([onSave]), since a long page is too large to paste into most places; its notice stays a little
 * longer, and doesn't leave while it is being saved ([saving]). While a whole page is being taken
 * ([takingWholePage], with no notice yet), it says so, with a Cancel button ([onCancelWholePage]).
 */
@Composable
fun ScreenshotNoticeBar(
    notice: ScreenshotNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    takingWholePage: Boolean = false,
    onCancelWholePage: () -> Unit = {},
    saving: Boolean = false,
    onSave: () -> Unit = {}
) {
    if (notice == null) {
        if (takingWholePage) WholePageProgress(onCancel = onCancelWholePage, modifier = modifier)
        return
    }
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val result = notice.result
    val copied = result as? ScreenshotResult.Copied
    val saveable = copied?.wholePage == true
    LaunchedEffect(notice.serial, saving) {
        if (saving) return@LaunchedEffect
        delay(if (saveable) SAVEABLE_NOTICE_MILLIS else SCREENSHOT_NOTICE_MILLIS)
        latestOnDismiss()
    }
    val (title, detail) = screenshotNoticeText(result, saving)

    NoticeSnackbar(
        testTag = "screenshot_notice",
        dismissLabel = "Dismiss",
        onDismiss = onDismiss,
        modifier = modifier,
        dismissTestTag = "screenshot_notice_dismiss",
        actionLabel = if (saveable && !saving) "Save" else null,
        actionTestTag = "screenshot_notice_save",
        onAction = onSave
    ) {
        when {
            copied != null -> ScreenshotThumbnail(copied)
            // The words say it was saved
            result is ScreenshotResult.Saved -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            else -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * "Capturing the whole page…" while the engine draws it and it is copied, which can take a few
 * seconds for a long page; its close button gives up on it. Material's loading indicator, still
 * with Remove animations on.
 */
@Composable
private fun WholePageProgress(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    NoticeSnackbar(
        testTag = "screenshot_progress",
        dismissLabel = "Cancel",
        onDismiss = onCancel,
        modifier = modifier,
        dismissTestTag = "screenshot_progress_cancel"
    ) {
        RemLoadingIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.inversePrimary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Capturing the whole page…",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The screenshot, small, at a fixed height and a width that follows its shape within limits. A
 * whole page shows its top, as wide as the picture made of it.
 */
@Composable
private fun ScreenshotThumbnail(copied: ScreenshotResult.Copied) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    val picture = copied.thumbnail
    val aspect = when {
        copied.wholePage && picture != null && picture.height > 0 -> picture.width.toFloat() / picture.height
        copied.height > 0 -> copied.width.toFloat() / copied.height
        else -> 1f
    }
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
        picture?.let {
            // The text beside it says what it is
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = if (copied.wholePage) ContentScale.Crop else ContentScale.Fit,
                alignment = Alignment.TopCenter,
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

/** How much wider than tall the picture of a whole page's top is (4:3), leaving room for the words beside it. */
const val WHOLE_PAGE_THUMBNAIL_ASPECT = 4f / 3f
private const val SCREENSHOT_NOTICE_MILLIS = 4_000L

/** A notice with Save stays longer, to give time to reach the button. */
private const val SAVEABLE_NOTICE_MILLIS = 8_000L

/** The hint grows to fit the Whole page button beside its words. */
private val HintMaxWidth = 720.dp

/** Narrower than this, the Whole page button goes under the hint's words. */
private val HintButtonBesideFrom = 600.dp

/** A full touch target, as the hint's close button has. */
private val WholePageButtonHeight = 48.dp
