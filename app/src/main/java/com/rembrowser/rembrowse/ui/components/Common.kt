package com.rembrowser.rembrowse.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.model.TerminalUrls
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.util.UrlResolver

/**
 * Whether [SiteIcon] may fetch a site's `/favicon.ico`. Tests and previews turn this off so
 * they never touch the network; the monogram fallback is shown instead.
 */
val LocalSiteIconsEnabled = staticCompositionLocalOf { true }

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
    bordered: Boolean = false
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
            focusable = false
        ) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(size)
                    .keyboardFocusRing(MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.surfaceContainerHighest else Color.Transparent)
                    .then(
                        if (bordered) {
                            Modifier.border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        }
                    )
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
 * outside its edge unless [inside]. Touch and pointer use show none, as on the web's
 * :focus-visible. It is drawn, not laid out, so it never moves anything.
 *
 * Put it before the element's `clip` (a ring after the clip would be cut off) and before its
 * `clickable` or `focusable`, whose focus it follows. Use [inside] for rows in a scrolling list,
 * whose edge would cut an outside ring.
 */
@Composable
internal fun Modifier.keyboardFocusRing(shape: Shape, inside: Boolean = false): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val color = LocalFocusRingColor.current.takeOrElse { MaterialTheme.colorScheme.primary }
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused && keyboard) {
                val stroke = 2.dp.toPx()
                // Where the ring's centre line runs, from the element's edge
                val offset = if (inside) -stroke / 2 else 2.dp.toPx() + stroke / 2
                val ring = Size(size.width + offset * 2, size.height + offset * 2)
                val outline = shape.createOutline(ring, layoutDirection, this)
                translate(left = -offset, top = -offset) {
                    drawOutline(outline = outline, color = color, style = Stroke(width = stroke))
                }
            }
        }
}

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
 * A site's icon: its favicon when one can be loaded, otherwise the first letter of its host.
 * Always decorative; the surrounding control carries the accessible name.
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
    val faviconUrl = if (LocalSiteIconsEnabled.current) {
        remember(url) { UrlResolver.faviconUrl(url) }
    } else {
        null
    }
    var loaded by remember(faviconUrl) { mutableStateOf(false) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Text(
                text = UrlResolver.monogram(url, title),
                style = glyphStyle(
                    if (size >= 28.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge
                ),
                color = tint,
                maxLines = 1
            )
        }
        if (faviconUrl != null) {
            AsyncImage(
                model = faviconUrl,
                contentDescription = null,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

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
 * Invokes [onClick] for a mouse's middle (tertiary) button, without also triggering a tap. Only
 * a mouse has that button: touch and the touchpad's own clicks are left as they are.
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
                if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
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

@Composable
fun RemDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    /** Not focusable for a menu that acts on a text field, which must keep its focus meanwhile. */
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 220.dp),
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
 * A notice over the bottom of the window (a download, an update, a screenshot): a Material 3
 * snackbar in the inverse colours, dark in the light theme and light in the dark one, so it
 * stands out from any page under it. Each notice keeps its own state and timing (when it leaves
 * by itself, or that it waits for an answer); this only draws it. [content] (an icon or picture
 * and the text) is announced politely when it changes. [actionLabel] adds a text button before
 * the close button.
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
    CompositionLocalProvider(LocalFocusRingColor provides colors.inversePrimary) {
        Snackbar(
            modifier = modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .testTag(testTag),
            action = actionLabel?.let { label ->
                {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                        modifier = Modifier
                            .keyboardFocusRing(ButtonDefaults.textShape)
                            .then(if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier)
                    ) {
                        Text(label)
                    }
                }
            },
            dismissAction = {
                RemIconButton(
                    icon = Icons.Outlined.Close,
                    label = dismissLabel,
                    onClick = onDismiss,
                    // The snackbar's colour for it, inverseOnSurface
                    tint = LocalContentColor.current,
                    iconSize = 18.dp,
                    modifier = if (dismissTestTag != null) Modifier.testTag(dismissTestTag) else Modifier
                )
            },
            // Rounded like the menus and panes, rather than Material's 4dp
            shape = MaterialTheme.shapes.medium,
            containerColor = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            actionContentColor = colors.inversePrimary,
            dismissActionContentColor = colors.inverseOnSurface
        ) {
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

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

/** A 1dp line used between groups in the tab rail. */
@Composable
fun RailDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 6.dp)
            .width(24.dp)
            .heightIn(min = 1.dp, max = 1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
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
