package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rembrowser.rembrowse.R
import com.rembrowser.rembrowse.model.LayoutMode
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
    iconSize: Dp = RemDimens.IconSize
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
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            focusable = false
        ) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(size)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.surfaceContainerHighest else Color.Transparent)
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
                style = if (size >= 28.dp) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.labelLarge
                },
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

/** Miniature drawing of a pane layout, used wherever a layout is offered. */
@Composable
fun LayoutGlyph(mode: LayoutMode, color: Color, modifier: Modifier = Modifier) {
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
        if (mode.columns == 2) {
            val x = size.width / 2f
            drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
        }
        if (mode.rows == 2) {
            val y = size.height / 2f
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = stroke)
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

@Composable
fun RemDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 220.dp),
        offset = offset,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
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
                        modifier = Modifier.weight(1f)
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
