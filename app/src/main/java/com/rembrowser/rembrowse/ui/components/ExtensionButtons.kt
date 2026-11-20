package com.rembrowser.rembrowse.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.ui.theme.RemDimens

/** Badges longer than this are cut, as desktop browsers do; the full text is in the name. */
private const val MAX_BADGE_CHARS = 4

/**
 * An extension's toolbar icon, or a puzzle piece until it has loaded or when it has none.
 * Always decorative; the surrounding control carries the accessible name.
 */
@Composable
fun ExtensionIcon(
    icon: Bitmap?,
    modifier: Modifier = Modifier,
    size: Dp = RemDimens.IconSize,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (icon != null) {
        val image = remember(icon) { icon.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            // Icons are rendered larger than shown; filter them so they stay smooth
            filterQuality = FilterQuality.High,
            modifier = modifier.size(size)
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}

/**
 * The small count or label an extension shows on its button, such as an ad blocker's count
 * of blocked requests. Uses the extension's own colours when it set them; a ring in the
 * background colour keeps it apart from the icon underneath.
 */
@Composable
fun ExtensionBadge(
    text: String,
    backgroundColor: Int?,
    textColor: Int?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val background = backgroundColor?.let { Color(it) }?.takeIf { it.alpha > 0f } ?: colors.primary
    val foreground = when {
        textColor != null -> Color(textColor)
        backgroundColor == null -> colors.onPrimary
        // A colour the extension chose: pick whichever of black or white reads on it
        background.luminance() > 0.4f -> Color.Black
        else -> Color.White
    }
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .border(1.5.dp, colors.background, shape)
            .padding(1.dp)
            .heightIn(min = 13.dp)
            .widthIn(min = 13.dp)
            .clip(shape)
            .background(background)
            .padding(horizontal = 3.dp)
            .testTag("extension_badge"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(MAX_BADGE_CHARS),
            color = foreground,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** A row in a menu that opens an extension's popup: its icon, its name and its badge. */
@Composable
fun ExtensionMenuItem(
    extension: InstalledExtension,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenuItem(
        text = {
            Text(
                text = extension.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        },
        leadingIcon = { ExtensionIcon(icon = extension.icon) },
        trailingIcon = {
            val badge = extension.badgeText
            if (!badge.isNullOrBlank()) ShortcutHint(badge)
        },
        onClick = onClick,
        modifier = modifier.testTag("menu_extension_${extension.id}")
    )
}
