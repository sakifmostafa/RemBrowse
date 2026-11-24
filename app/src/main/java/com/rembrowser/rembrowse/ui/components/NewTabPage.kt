package com.rembrowser.rembrowse.ui.components

import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max

/**
 * The start page shown by an empty tab: a large clock over a soft accent-coloured backdrop,
 * with the date in the corner. It is drawn natively, so a new tab opens instantly and offline.
 */
@Composable
fun NewTabPage(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val hasKeyboard = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY

    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            // Wake just after the next minute boundary rather than polling every second
            delay(60_000L - System.currentTimeMillis() % 60_000L + 250L)
        }
    }
    val is24Hour = DateFormat.is24HourFormat(context)
    val time = remember(now, is24Hour) {
        now.format(DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm"))
    }
    val date = remember(now.toLocalDate()) {
        now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }

    val base = colors.surfaceContainerLowest
    val glow = colors.tertiaryContainer

    BoxWithConstraints(
        modifier = modifier
            .drawBehind {
                drawRect(base)
                val reach = max(size.width, size.height)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.60f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.12f),
                        radius = reach * 0.85f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height),
                        radius = reach * 0.6f
                    )
                )
            }
            .testTag("new_tab_page")
    ) {
        val roomy = maxWidth >= LargeClockMinWidth && maxHeight >= LargeClockMinHeight
        Text(
            text = time,
            style = if (roomy) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .semantics { contentDescription = "Current time $time" }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (roomy) 32.dp else 16.dp)
        ) {
            Text(
                text = date,
                style = if (roomy) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = colors.onSurface
            )
            if (hasKeyboard) {
                Text(
                    text = "Press Ctrl+K for commands",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** From this width, and [LargeClockMinHeight] high, the start page has room for its large clock and wider margins. */
private val LargeClockMinWidth = 420.dp
private val LargeClockMinHeight = 360.dp
