package com.rembrowser.rembrowse.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.rembrowser.rembrowse.BuildConfig
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.SiteIcons
import com.rembrowser.rembrowse.model.SiteOrigins
import com.rembrowser.rembrowse.model.TerminalUrls
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import android.view.ViewTreeObserver
import android.view.View

/**
 * Whether [SiteIcon] may fetch a site's icon. Tests and previews turn this off so they never
 * touch the network; the monogram fallback is shown instead.
 */
val LocalSiteIconsEnabled = staticCompositionLocalOf { true }

/**
 * The icons each site names for itself, by origin (BrowserViewModel.declaredSiteIcons), which
 * [SiteIcon] tries before the site's `/favicon.ico`. Empty where nothing provides it.
 */
val LocalDeclaredSiteIcons = compositionLocalOf<Map<String, List<DeclaredSiteIcon>>> { emptyMap() }

/**
 * Icon-only button with an accessible name, a hover/long-press tooltip and a 48dp touch target
 * around its smaller visual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Color.Unspecified,
    size: Dp = RemDimens.ControlSize,
    iconSize: Dp = RemDimens.IconSize,
    bordered: Boolean = false,
    /** Whether the pointer over it, or a finger held on it, shows its tooltip. */
    tooltipEnabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        tint != Color.Unspecified -> tint
        selected -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    // TooltipBox wraps its anchor in a box of its own, which would swallow a parent-scoped
    // modifier such as align(). An explicit outer box makes the caller's modifier take effect.
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            focusable = false,
            enableUserInput = tooltipEnabled
        ) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(size)
                    // A bordered button rings outside its border (see keyboardFocusRing)
                    .then(if (bordered) Modifier.keyboardFocusRing(MaterialTheme.shapes.medium) else Modifier)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.surfaceContainerHighest else Color.Transparent)
                    .then(
                        if (bordered) {
                            Modifier.border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        }
                    )
                    .clickable(
                        interactionSource = null,
                        indication = if (bordered) ringOutsideRipple() else ripple(focusRingShape = MaterialTheme.shapes.medium),
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick
                    )
                    .semantics { if (selected) this.selected = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * A ring around the element while it has keyboard focus (Tab, arrows), in the accent colour,
 * 2dp wide and 2dp outside its edge unless [inside]. Touch and pointer use show none, as on the
 * web's :focus-visible. It is drawn, not laid out, so it never moves anything; while it shows,
 * the element is drawn above its neighbours, so one with a fill of its own (the selected section
 * just below) never covers the ring where it reaches over the gap between them.
 *
 * Controls normally show Material's inset ring instead (see focusRingConfiguration in Theme.kt);
 * this one is left where that can't be drawn or would mislead: a focusable that is not a
 * clickable (the print preview's page list), a focus also taken on touch (the print screen's
 * Save tile), and controls with a border of their own (the Split button, the rail's tiles,
 * switcher cards, Settings' sections, command palette rows, the terminal's theme cards, the
 * accent swatches). On those, the border would cover half of Material's ring, which runs along
 * the inside of the edge, and where the border is the accent (a split open, the active tab, the
 * section shown, the selected swatch) the ring would look like it. Their clickable takes
 * [ringOutsideRipple].
 *
 * Put it before the element's `clip` (a ring after the clip would be cut off) and before its
 * `clickable` or `focusable`, whose focus it follows; and before a `border`, which it is drawn
 * over. Use [inside] for rows in a scrolling list whose edge sits right at the list's, with no
 * room for an outside ring.
 *
 * Given the keyboard's focus in a scrolling list or grid (the rail's tabs, the switcher's cards,
 * Settings' sections), the element is scrolled into view with the whole ring around it
 * ([FocusRingReach] beyond each edge): the list itself only brings the element's own bounds in,
 * so one Tab brought in from below sat on the list's edge with the bottom of its ring cut off.
 */
@Composable
internal fun Modifier.keyboardFocusRing(shape: Shape, inside: Boolean = false): Modifier {
    var focused by remember { mutableStateOf(false) }
    val inputModes = LocalInputModeManager.current
    val keyboard = inputModes.inputMode == InputMode.Keyboard
    val color = LocalFocusRingColor.current.takeOrElse { MaterialTheme.colorScheme.primary }
    val density = LocalDensity.current
    val ringInView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    // The element's size as last laid out, for the area the ring covers
    val laidOut = remember { IntArray(2) }
    return this
        .then(if (inside) Modifier else Modifier.bringIntoViewRequester(ringInView))
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && !inside && inputModes.inputMode == InputMode.Keyboard) {
                scope.launch {
                    val reach = with(density) { FocusRingReach.toPx() }
                    ringInView.bringIntoView(
                        Rect(-reach, -reach, laidOut[0] + reach, laidOut[1] + reach)
                    )
                }
            }
        }
        // Read while placing, so a change of focus places the element again rather than recomposing it
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            laidOut[0] = placeable.width
            laidOut[1] = placeable.height
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0, zIndex = if (focused && keyboard) 1f else 0f)
            }
        }
        .drawWithContent {
            drawContent()
            if (focused && keyboard) {
                val stroke = FocusRingWidth.toPx()
                // Where the ring's centre line runs, from the element's edge
                val offset = if (inside) -stroke / 2 else FocusRingGap.toPx() + stroke / 2
                val ring = Size(size.width + offset * 2, size.height + offset * 2)
                val outline = shape.createOutline(ring, layoutDirection, this)
                translate(left = -offset, top = -offset) {
                    drawOutline(outline = outline, color = color, style = Stroke(width = stroke))
                }
            }
        }
}

/** [keyboardFocusRing]'s line, and the gap between it and the element's edge. */
private val FocusRingWidth = 2.dp
private val FocusRingGap = 2.dp

/** How far an outside [keyboardFocusRing] reaches beyond the element's edge. */
internal val FocusRingReach = FocusRingGap + FocusRingWidth

/**
 * The indication of a control that draws its focus ring with [keyboardFocusRing], outside its
 * border: Material's ripple for hover and press, without Material's own inset ring.
 */
internal fun ringOutsideRipple(): IndicationNodeFactory = ripple(enableFocusIndication = false)

/**
 * Whether Android's animations are off (Settings → Accessibility → Remove animations, or the
 * animator duration scale at 0 in the developer options): the one place RemBrowse asks. Motion
 * is then left out and things appear and leave at once. It follows the setting while shown, so
 * turning animations off takes effect without reopening anything.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var off by remember(context) { mutableStateOf(systemAnimationsOff(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = systemAnimationsOff(context)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }.isSuccess
        // Changed between the first read and the observer
        off = systemAnimationsOff(context)
        onDispose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return off
}

private fun systemAnimationsOff(context: Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

/**
 * The colour of [keyboardFocusRing] where the accent would not stand out, such as on a notice's
 * inverse surface. Unspecified means the accent (primary).
 */
internal val LocalFocusRingColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * [style] at the size it has with the system's font size at 100%, for letters that are part of
 * an icon (a site's monogram, the tab count). Like the icon they stand in, they keep their size
 * when the font size grows, instead of spilling out of their tile; the control around them
 * carries the name a screen reader reads.
 */
@Composable
internal fun glyphStyle(style: TextStyle): TextStyle {
    val density = LocalDensity.current
    if (density.fontScale == 1f) return style
    return with(density) {
        style.copy(
            fontSize = if (style.fontSize.isSp) style.fontSize.value.dp.toSp() else style.fontSize,
            lineHeight = if (style.lineHeight.isSp) style.lineHeight.value.dp.toSp() else style.lineHeight
        )
    }
}

/**
 * A site's icon: one the site names for itself ([LocalDeclaredSiteIcons]), or else its
 * `/favicon.ico`, when one can be loaded (see SiteIcons.addressesFor); otherwise the first
 * letter of its host. Each address that fails gives way to the next. While another address
 * loads, the icon last shown for the site stays (from Coil's memory cache), so a site changing
 * its icon doesn't flash its letter. Always decorative; the surrounding control carries the
 * accessible name.
 */
@Composable
fun SiteIcon(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = RemDimens.IconSize,
    isNewTab: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (url.startsWith(BrowserTab.SETTINGS_URL, ignoreCase = true) || TerminalUrls.isTerminalUrl(url)) {
        Icon(
            imageVector = if (TerminalUrls.isTerminalUrl(url)) Icons.Outlined.Terminal else Icons.Outlined.Settings,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
        return
    }
    if (isNewTab) {
        // The start page is RemBrowse's own page, so it carries the RemBrowse mark
        Image(
            painter = painterResource(R.drawable.rembrowse_mark),
            contentDescription = null,
            modifier = modifier.size(size)
        )
        return
    }
    val declared = LocalDeclaredSiteIcons.current
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    val addresses = if (LocalSiteIconsEnabled.current) {
        remember(url, declared, targetPx) { SiteIcons.addressesFor(url, declared, targetPx) }
    } else {
        emptyList()
    }
    // The address being tried: each that fails gives way to the next
    var tried by remember(addresses) { mutableIntStateOf(0) }
    val address = addresses.getOrNull(tried)
    // Whether an icon of the site is on screen (otherwise its letter is), and its key in Coil's
    // memory cache. Kept while the page moves within the site.
    val origin = remember(url) { SiteOrigins.originOf(url) }
    var shown by remember(origin) { mutableStateOf(false) }
    var shownKey by remember(origin) { mutableStateOf<MemoryCache.Key?>(null) }
    // A new address is loading and Coil no longer has the icon on screen to show meanwhile
    var loadingBlank by remember(origin) { mutableStateOf(false) }
    val context = LocalContext.current
    // Built when the address changes: the icon on screen then stays until the new one is in
    val request = remember(origin, address) {
        address?.let { ImageRequest.Builder(context).data(it).placeholderMemoryCacheKey(shownKey).build() }
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (!shown || loadingBlank) {
            Text(
                text = UrlResolver.monogram(url, title),
                style = glyphStyle(
                    if (size >= 28.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge
                ),
                color = tint,
                maxLines = 1
            )
        }
        // Another site starts afresh, even at an address the last one shared (a common CDN icon)
        if (request != null) key(origin) {
            AsyncImage(
                model = request,
                contentDescription = null,
                onLoading = { state -> loadingBlank = state.painter == null },
                onSuccess = { state ->
                    shown = true
                    shownKey = state.result.memoryCacheKey
                    loadingBlank = false
                },
                onError = { state ->
                    if (BuildConfig.DEBUG) Log.d(SITE_ICON_TAG, "No icon at $address: ${state.result.throwable}")
                    if (tried < addresses.lastIndex) {
                        tried++
                    } else {
                        shown = false
                        shownKey = null
                        loadingBlank = false
                    }
                },
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

private const val SITE_ICON_TAG = "SiteIcon"

/**
 * Miniature drawing of a pane layout, used wherever a layout is offered.
 * [firstPaneFraction] moves the divider, so a 70/30 split is drawn as one.
 */
@Composable
fun LayoutGlyph(
    mode: LayoutMode,
    color: Color,
    modifier: Modifier = Modifier,
    firstPaneFraction: Float = 0.5f
) {
    Canvas(modifier = modifier.size(RemDimens.IconSize)) {
        val stroke = 1.5.dp.toPx()
        val insetX = 1.5.dp.toPx()
        val insetY = 3.5.dp.toPx()
        val left = insetX
        val top = insetY
        val right = size.width - insetX
        val bottom = size.height - insetY
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(width = stroke)
        )
        val x = left + (right - left) * firstPaneFraction
        if (mode.columns == 2) {
            drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
        }
        if (mode.rows == 2) {
            val y = top + (bottom - top) * firstPaneFraction
            // One and two: only the right-hand column is split in two
            val rowStart = if (mode == LayoutMode.SPLIT_1_2) x else left
            drawLine(color, Offset(rowStart, y), Offset(right, y), strokeWidth = stroke)
        }
    }
}

/** Invokes [onClick] for a mouse/trackpad secondary click, without also triggering a tap. */
fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}

/** [onSecondaryClick] that always calls the latest [onClick]. */
@Composable
fun Modifier.contextMenuTrigger(onClick: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onClick)
    return onSecondaryClick { latest() }
}

/**
 * Invokes [onClick] for a middle (tertiary) button press, without also triggering a tap: a
 * mouse's middle button, and a three-finger tap on a touchpad, which Android's touchpad handling
 * sends as a middle click (BUTTON_TERTIARY). Kept on purpose: it is the platform's own
 * middle-click gesture, and ChromeOS's browser takes it the same way. A finger on the screen
 * never presses it, and the touchpad's one- and two-finger clicks and taps are left as they are.
 */
fun Modifier.onTertiaryClick(onClick: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}

/** [onTertiaryClick] that always calls the latest [onClick]: what a middle click does, as in a desktop browser. */
@Composable
fun Modifier.middleClickTrigger(onClick: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onClick)
    return onTertiaryClick { latest() }
}

/**
 * Whether a press is a finger on the screen: not a mouse, not a stylus, and not the touchpad,
 * whose clicks can arrive as a finger with a button down. [TouchHaptics] answers only these
 * ([touchLongPress] also takes a stylus, and the rail's combinedClickable any pointer).
 */
internal fun isFingerPress(type: PointerType, buttons: PointerButtons): Boolean =
    type == PointerType.Touch && !buttons.areAnyPressed

/**
 * Haptics for touch only. [perform] plays a haptic when the press now being let go on an element
 * carrying [touchPressFor] is a finger's ([isFingerPress]), and nothing for a mouse, the
 * touchpad, the keyboard or a screen reader. Devices without a vibrator ignore it.
 */
@Stable
class TouchHaptics internal constructor(private val haptics: HapticFeedback) {
    internal var fingerPress = false

    fun perform(type: HapticFeedbackType) {
        if (fingerPress) haptics.performHapticFeedback(type)
    }
}

@Composable
fun rememberTouchHaptics(): TouchHaptics {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { TouchHaptics(haptics) }
}

/**
 * Tells [haptics] whether the press on this element is a finger's. Put it before the element's
 * `clickable` or `toggleable`: those act on the release in the Main pass, and this only lets go
 * of the press in the Final pass after it, so a keyboard or screen reader action later never
 * counts as touch.
 */
fun Modifier.touchPressFor(haptics: TouchHaptics): Modifier = pointerInput(haptics) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        haptics.fingerPress = isFingerPress(down.type, currentEvent.buttons)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.id == down.id && it.pressed })
        } finally {
            haptics.fingerPress = false
        }
    }
}

/**
 * [toggleable] for a switch row, with Android's switch haptics (ToggleOn, ToggleOff) when a
 * finger flips it. A mouse, the touchpad, the keyboard and screen readers flip it silently.
 */
@Composable
fun Modifier.hapticToggleable(
    value: Boolean,
    role: Role,
    enabled: Boolean = true,
    /** The shape of the keyboard focus ring Material draws inside the element. */
    focusRingShape: Shape = MaterialTheme.shapes.small,
    onValueChange: (Boolean) -> Unit
): Modifier {
    val haptics = rememberTouchHaptics()
    val latest by rememberUpdatedState(onValueChange)
    return this
        .touchPressFor(haptics)
        .toggleable(
            value = value,
            interactionSource = null,
            indication = ripple(focusRingShape = focusRingShape),
            enabled = enabled,
            role = role
        ) { checked ->
            haptics.perform(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            latest(checked)
        }
}

/**
 * Calls [onLongPress] when a finger (or a stylus) rests on the element for the long-press time
 * without moving, with the long-press haptic, and keeps the lift from also counting as a tap: a
 * touch screen's way to the menu a right-click opens. A mouse and the touchpad are left alone,
 * so holding a click there is still a click. Screen readers offer it as the element's long-click
 * action, named [label]. Put it before the element's `clickable`.
 */
@Composable
fun Modifier.touchLongPress(label: String, onLongPress: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onLongPress)
    val haptics = LocalHapticFeedback.current
    return this
        .semantics {
            onLongClick(label = label) {
                latest()
                true
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val onScreen = down.type == PointerType.Touch || down.type == PointerType.Stylus
                // A touchpad's right click can arrive as a finger with a button down
                if (!onScreen || currentEvent.buttons.areAnyPressed) return@awaitEachGesture
                // Null when the time ran out with the finger still down and still
                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (!change.pressed || change.isConsumed || moved || event.changes.size > 1) break
                    }
                    true
                }
                if (endedEarly != null) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latest()
                // Until the finger lifts, nothing underneath takes it as a tap or a scroll
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}

/**
 * The font scale from which text counts as very large (Settings → Display → Font size at 150% and
 * up): menu rows drop their extra padding, and the More menu its key hints.
 */
internal const val LargeFontScale = 1.5f

/**
 * Where a menu's anchor ends in its window, noted by [menuAnchor], so a [RemDropdownMenu] opened
 * from it can stay below it.
 */
@Stable
class MenuAnchor {
    internal var bottomInWindow by mutableFloatStateOf(Float.NaN)
}

/** Notes where this anchor of a [RemDropdownMenu] ends; see [MenuAnchor]. */
fun Modifier.menuAnchor(anchor: MenuAnchor): Modifier =
    onGloballyPositioned { anchor.bottomInWindow = it.boundsInWindow().bottom }

/**
 * Material's margin between a menu and the window's top and bottom edges (MenuVerticalMargin),
 * which its placement keeps: a menu goes below its anchor only if it ends this far above the foot
 * of the window.
 */
private val MenuWindowMargin = 48.dp

/** Below this, a menu is left to Material's placement rather than squeezed under its anchor. */
private val MinMenuRoomBelow = 144.dp

/**
 * How tall a menu opened [offsetY] below an anchor whose foot is [anchorBottom] down the window may
 * be and still fit under it, as Material's placement measures it: the window's visible frame
 * ([frameHeight]) less the anchor's foot, the offset and [MenuWindowMargin]. Null when that is
 * less than three rows ([MinMenuRoomBelow]), where the menu is left to Material's placement.
 */
internal fun menuRoomBelow(frameHeight: Dp, anchorBottom: Dp, offsetY: Dp): Dp? =
    (frameHeight - anchorBottom - offsetY - MenuWindowMargin).takeIf { it >= MinMenuRoomBelow }

/** [menuRoomBelow] for a menu opened [offsetY] below [anchor]; null while the anchor is not laid out. */
@Composable
private fun menuRoomBelow(anchor: MenuAnchor, offsetY: Dp): Dp? {
    val bottom = anchor.bottomInWindow
    val frameHeight = rememberVisibleFrameHeight()
    if (bottom.isNaN()) return null
    return with(LocalDensity.current) { menuRoomBelow(frameHeight.toDp(), bottom.toDp(), offsetY) }
}

/** The height of the window's visible frame, in px: the window less the system bars and the keyboard. */
private fun visibleFrameHeightOf(view: View): Int =
    android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }.height()

/**
 * The height of the window's visible frame ([read], the frame Material places a menu in), kept
 * current: read again whenever the on-screen keyboard's insets change, and whenever the window
 * is laid out with a frame of another height (a window resized, or a keyboard that sends no
 * insets). Read once, a menu opened with the keyboard up kept the keyboard's room after it went.
 */
@Composable
internal fun rememberVisibleFrameHeight(read: (View) -> Int = ::visibleFrameHeightOf): Int {
    val view = LocalView.current
    val latestRead by rememberUpdatedState(read)
    // Read here, so the keyboard coming or going reads the frame again
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    // Set by the window's layouts; a state of the same value changes nothing
    var laidOut by remember(view) { mutableIntStateOf(read(view)) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { laidOut = latestRead(view) }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return remember(view, keyboard, laidOut) { read(view) }
}

/**
 * A menu in RemBrowse's style: Material's, on the surface colour with a hairline border and a
 * shadow, at least 220dp wide. Given its [anchor] ([menuAnchor]), it opens below the anchor
 * however long it is: with very large text, or on a short window, it scrolls in the room below
 * instead of moving up over its anchor (and the toolbar the anchor sits in), as Material's menus
 * do when they don't fit under it.
 */
@Composable
fun RemDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    /** Not focusable for a menu that acts on a text field, which must keep its focus meanwhile. */
    properties: PopupProperties = PopupProperties(focusable = true),
    anchor: MenuAnchor? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val roomBelow = anchor?.let { menuRoomBelow(it, offset.y) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 220.dp)
            .then(if (roomBelow != null) Modifier.heightIn(max = roomBelow) else Modifier),
        offset = offset,
        properties = properties,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/**
 * A menu row's padding: Material 3 Expressive's, 12dp above and below the text, at the usual font
 * sizes; from [LargeFontScale] none above and below, as Material's classic rows had, the row
 * keeping Material's 48dp minimum height. At 200% the expressive padding makes every row 62dp
 * tall, and a long menu (More) longer than a laptop's window.
 */
@Composable
private fun menuItemPadding(): PaddingValues =
    if (LocalDensity.current.fontScale >= LargeFontScale) {
        MenuDefaults.DropdownMenuItemContentPadding
    } else {
        MenuDefaults.DropdownMenuSelectableItemContentPadding
    }

/**
 * A row in a [RemDropdownMenu]: Material 3 Expressive's menu item, set 4dp in from the menu's
 * sides with rounded corners for its hover, press and focus, less tall with very large text
 * ([menuItemPadding]). The parameters are those of the classic DropdownMenuItem it replaces;
 * [trailingIcon] is usually a [ShortcutHint].
 */
@Composable
fun RemMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    DropdownMenuItem(
        onClick = onClick,
        text = text,
        shape = MenuDefaults.itemShapes().shape,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingContent = trailingIcon,
        enabled = enabled,
        contentPadding = menuItemPadding()
    )
}

/**
 * A menu row that is on or off, such as Desktop site: Material 3 Expressive's checkable menu item.
 * While on it takes the selected fill (tertiaryContainer), rounder corners and a tick at its end,
 * so the state never rests on colour alone; screen readers hear a checkbox. Its padding is
 * [menuItemPadding]'s.
 */
@Composable
fun RemCheckableMenuItem(
    text: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    CheckableDropdownMenuItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        text = text,
        shapes = MenuDefaults.itemShapes(),
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingContent = if (checked) {
            { MenuTick() }
        } else {
            null
        },
        enabled = enabled,
        colors = selectableMenuItemColors(),
        contentPadding = menuItemPadding()
    )
}

/**
 * One of a set of choices in a menu, such as a split's layout: Material 3 Expressive's selectable
 * menu item. The chosen one takes the selected fill, rounder corners and a tick at its end (in
 * place of [trailingIcon], usually a shortcut); screen readers hear a radio button.
 */
@Composable
fun RemSelectableMenuItem(
    text: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true
) {
    SelectableDropdownMenuItem(
        selected = selected,
        onClick = onClick,
        text = text,
        shapes = MenuDefaults.itemShapes(),
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingContent = if (selected) {
            { MenuTick() }
        } else {
            trailingIcon
        },
        enabled = enabled,
        colors = selectableMenuItemColors(),
        contentPadding = menuItemPadding()
    )
}

/** Material's selectable menu item colours, on the menu's own fill rather than one of their own. */
@Composable
private fun selectableMenuItemColors() = MenuDefaults.selectableItemColors(
    containerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent
)

/** The tick at the end of a chosen menu row, in the row's content colour. */
@Composable
private fun MenuTick() {
    Icon(imageVector = Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
}

/**
 * A small set of choices of which one is on, such as the theme (System, Light, Dark): Material 3
 * Expressive's connected button group. Toggle buttons sit side by side, 2dp apart, the outer ones
 * rounded on their outer side; the chosen one is filled with the accent (primary), turns fully
 * round and carries a tick, so it is not told apart by colour alone. Screen readers hear a group
 * of radio buttons. Each button is as wide as its label, so no word is broken. [testTag] names
 * each option's button.
 *
 * Material's button groups stay on one line (they "shouldn't wrap to a second line"), and its
 * remedies, narrower buttons or an overflow menu, can't hold three names at 200% on a phone. So
 * when the group doesn't fit (a narrow window, large text), the choices become separate round
 * toggle buttons, 8dp apart, that go on to a second line: a connected group broken over two lines
 * would show square corners at the end of the first line and the start of the second. Whether it
 * fits is measured with the chosen one's tick on any of them, so choosing another never moves
 * the buttons from one form to the other.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    testTag: (T) -> String,
    modifier: Modifier = Modifier
) {
    @Composable
    fun choice(index: Int, option: T, connected: Boolean) {
        val checked = option == selected
        val icon: (@Composable () -> Unit)? = if (checked) {
            { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) }
        } else {
            null
        }
        val buttonModifier = Modifier
            .semantics { role = Role.RadioButton }
            .testTag(testTag(option))
        val text: @Composable RowScope.() -> Unit = { Text(text = label(option), maxLines = 1) }
        if (connected) {
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                modifier = buttonModifier,
                icon = icon,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                content = text
            )
        } else {
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option) },
                modifier = buttonModifier,
                icon = icon,
                content = text
            )
        }
    }

    SubcomposeLayout(modifier = modifier.selectableGroup()) { constraints ->
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx()
        // Measured, never shown: each button without the tick, and the first one with it
        val probes = subcompose(ChoiceGroupSlot.Probe) {
            options.forEach { option -> ChoiceProbe(label(option), ticked = false) }
            options.firstOrNull()?.let { option -> ChoiceProbe(label(option), ticked = true) }
        }.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val tick = if (probes.isEmpty()) 0 else probes.last() - probes.first()
        val needed = probes.dropLast(1).sum() + tick + gap * (options.size - 1).coerceAtLeast(0)
        val oneLine = !constraints.hasBoundedWidth || needed <= constraints.maxWidth
        val placeable = subcompose(if (oneLine) ChoiceGroupSlot.Connected else ChoiceGroupSlot.Separate) {
            if (oneLine) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { index, option -> choice(index, option, connected = true) }
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SeparateChoiceSpacing),
                    verticalArrangement = Arrangement.spacedBy(SeparateChoiceSpacing),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { index, option -> choice(index, option, connected = false) }
                }
            }
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(
            placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth),
            placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        ) {
            placeable.place(0, 0)
        }
    }
}

/** What [ConnectedChoiceGroup] lays out: its probes, then one of its two forms. */
private enum class ChoiceGroupSlot { Probe, Connected, Separate }

/** The space between [ConnectedChoiceGroup]'s buttons when they no longer fit on one line. */
private val SeparateChoiceSpacing = 8.dp

/**
 * A toggle button as [ConnectedChoiceGroup] draws one, [ticked] or not, for its width alone:
 * disabled and without semantics, so neither the keyboard nor a screen reader ever reaches it.
 */
@Composable
private fun ChoiceProbe(label: String, ticked: Boolean) {
    ToggleButton(
        checked = ticked,
        onCheckedChange = {},
        enabled = false,
        modifier = Modifier.clearAndSetSemantics {},
        icon = if (ticked) {
            { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) }
        } else {
            null
        }
    ) {
        Text(text = label, maxLines = 1)
    }
}

/**
 * Material 3 Expressive's loading indicator, shapes morphing into one another in the accent
 * colour, for a wait of unknown length that is usually short: a tab loading in the rail, the
 * add-on catalogue, the print preview. It fills [modifier]'s size (48dp by default, the shapes a
 * little smaller). Long, measurable waits (a download) use LinearWavyProgressIndicator instead,
 * and the page's own loading line stays a plain 2dp line.
 *
 * With Android's animations off ([rememberReduceMotion]) it holds still: an hourglass in the
 * same colour, about as big as the shapes. Material's indicator would go on changing shape and
 * turning every 650ms even then (its loop waits 650ms between springs, which then only jump).
 * Either way a screen reader hears a progress bar in progress, as Material's says.
 *
 * material3 1.5.0 still marks LoadingIndicator experimental (ExperimentalMaterial3ExpressiveApi),
 * so this is the one place it is called: if it changes, only this wrapper does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemLoadingIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (rememberReduceMotion()) {
        Box(
            modifier = modifier
                .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .size(LoadingIndicatorSize)
                .testTag("loading_indicator_still"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.HourglassTop,
                contentDescription = null,
                tint = color,
                modifier = Modifier.fillMaxSize(LoadingShapeFraction)
            )
        }
    } else {
        LoadingIndicator(modifier = modifier, color = color)
    }
}

/** Material's loading indicator's size, 48dp, and how much of it its shapes fill, 38dp. */
private val LoadingIndicatorSize = 48.dp
private const val LoadingShapeFraction = 38f / 48f

/**
 * An outlined button as RemBrowse draws it: text and icon in the accent colour (primary) and a
 * border in the outline colour, as Material 3 1.3 drew them. Material 3 1.4 changed the default
 * to neutral text (onSurfaceVariant) on a fainter outlineVariant border. Disabled, it looks as
 * Material 3 1.4 draws it.
 */
@Composable
fun RemOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val defaultBorder = ButtonDefaults.outlinedButtonBorder(enabled)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = outlinedButtonContentColor(colors)),
        border = if (enabled) BorderStroke(defaultBorder.width, outlinedButtonBorderColor(colors)) else defaultBorder,
        contentPadding = contentPadding,
        content = content
    )
}

/** [RemOutlinedButton]'s text and icon colour while enabled (checked for contrast in ThemeContrastTest). */
internal fun outlinedButtonContentColor(colors: ColorScheme): Color = colors.primary

/** [RemOutlinedButton]'s border colour while enabled (checked for contrast in ThemeContrastTest). */
internal fun outlinedButtonBorderColor(colors: ColorScheme): Color = colors.outline

/**
 * A notice over the bottom of the window (a download, an update, a screenshot), drawn as a
 * Material 3 snackbar in the inverse colours: dark in the light theme and light in the dark one,
 * so it stands out from any page under it. It is as wide as what it holds, up to
 * [NoticeMaxWidth], so on a wide window or in split view it covers no more of the page than it
 * needs. Material's own Snackbar takes all the width it is given (up to 600dp), so this is built
 * on a Surface with the snackbar's colours, elevation, text style, spacing and heights (48dp for
 * one line, 68dp for two). Each notice keeps its own state and timing (when it leaves by itself,
 * or that it waits for an answer); this only draws it. The whole notice, [content] (an icon or
 * picture and the text), the action and the close button, is announced politely when it changes.
 * [actionLabel] adds a text button before the close button.
 */
@Composable
internal fun NoticeSnackbar(
    testTag: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissTestTag: String? = null,
    actionLabel: String? = null,
    actionTestTag: String? = null,
    onAction: () -> Unit = {},
    content: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // The accent is meant for the normal surfaces; on the inverse one the ring takes the inverse accent
    CompositionLocalProvider(
        LocalFocusRingColor provides colors.inversePrimary,
        LocalRippleConfiguration provides noticeFocusRing(colors)
    ) {
        Surface(
            modifier = modifier
                .padding(16.dp)
                .widthIn(max = NoticeMaxWidth)
                .testTag(testTag),
            // Rounded like the menus and panes, rather than Material's 4dp
            shape = MaterialTheme.shapes.medium,
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            // Material's snackbar elevation (level 3)
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gives way to the buttons when the text is long; short text keeps the notice short
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (actionLabel != null) {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                        modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier
                    ) {
                        Text(actionLabel)
                    }
                }
                RemIconButton(
                    icon = Icons.Outlined.Close,
                    label = dismissLabel,
                    onClick = onDismiss,
                    tint = colors.inverseOnSurface,
                    iconSize = 18.dp,
                    modifier = if (dismissTestTag != null) Modifier.testTag(dismissTestTag) else Modifier
                )
            }
        }
    }
}

/**
 * Focus rings on a notice's inverse surface: the inverse accent, with the inner stroke in the
 * notice's own colour, so the ring reads as one line in the colour checked in ThemeContrastTest.
 */
internal fun noticeFocusRing(colors: ColorScheme): RippleConfiguration = RippleConfiguration(
    focus = RippleConfiguration.Focus.InsetRing(outerStrokeColor = colors.inversePrimary, innerStrokeColor = colors.inverseSurface)
)

/** How wide a notice ([NoticeSnackbar]) can grow; shorter ones are as wide as their text. */
internal val NoticeMaxWidth = 520.dp

/** Keyboard shortcut hint shown at the trailing edge of menu and palette rows. */
@Composable
fun ShortcutHint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * Standard RemBrowse dialog: capped to a readable width on large windows, title row with an
 * explicit close button, then free-form content.
 */
@Composable
fun RemDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    maxHeight: Dp = 640.dp,
    testTag: String? = null,
    headerActions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TouchpadAsMouseInDialog()
        Surface(
            modifier = modifier
                .padding(16.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    headerActions()
                    RemIconButton(
                        icon = Icons.Outlined.Close,
                        label = "Close",
                        onClick = onDismiss
                    )
                }
                content()
            }
        }
    }
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** A bordered row in a list of things to manage: an extension, a download. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        content()
    }
}
