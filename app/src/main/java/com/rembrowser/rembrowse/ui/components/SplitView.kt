package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.LayoutMode
import kotlin.math.abs

/** Preset sizes for the first pane of a split. */
private val SPLIT_PRESETS = listOf(0.5f to "50/50", 0.7f to "70/30")

/**
 * The bar above a split: which tabs are linked into it, and the controls that act on the split
 * as a whole (swap, preset sizes, expand the focused pane, close the split).
 *
 * There is deliberately no address bar per pane. The one address bar at the top always acts
 * on the focused pane, which this strip (underlined chip) and the pane outline both point out.
 */
@Composable
fun SplitStrip(
    uiState: BrowserUiState,
    onFocusPane: (Int) -> Unit,
    onCloseTab: (String) -> Unit,
    onSwap: () -> Unit,
    onSetPreset: (Float) -> Unit,
    onExpandFocusedPane: () -> Unit,
    onCloseSplit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val showPresets = maxWidth >= 720.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(end = 6.dp)
                .testTag("split_strip"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = "Tabs linked in this split",
                tint = colors.primary,
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .size(20.dp)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (paneIndex in 0 until uiState.layoutMode.paneCount) {
                    val tab = uiState.tabForPane(paneIndex) ?: continue
                    LinkedTabChip(
                        tab = tab,
                        isActive = paneIndex == uiState.activePaneIndex,
                        onSelect = { onFocusPane(paneIndex) },
                        onClose = { onCloseTab(tab.id) },
                        modifier = Modifier.testTag("split_chip_$paneIndex")
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            RemIconButton(
                icon = Icons.Outlined.SwapHoriz,
                label = "Swap panes",
                onClick = onSwap,
                bordered = true,
                size = 36.dp,
                modifier = Modifier.testTag("split_swap")
            )
            if (showPresets) {
                SplitPresets(
                    current = if (uiState.layoutMode == LayoutMode.SPLIT_2X1) {
                        uiState.splitRatios.vertical
                    } else {
                        uiState.splitRatios.horizontal
                    },
                    onSetPreset = onSetPreset
                )
            }
            RemIconButton(
                icon = Icons.Outlined.OpenInFull,
                label = "Expand focused pane",
                onClick = onExpandFocusedPane,
                bordered = true,
                size = 36.dp,
                modifier = Modifier.testTag("split_expand")
            )
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Close split",
                onClick = onCloseSplit,
                bordered = true,
                size = 36.dp,
                modifier = Modifier.testTag("split_close")
            )
        }
    }
}

@Composable
private fun LinkedTabChip(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val underline = colors.primary
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (isActive) colors.surfaceContainerHighest else colors.surface)
            .border(1.dp, colors.outlineVariant, shape)
            // The focused pane's chip is underlined as well as filled, so it does not rely on colour
            .drawBehind {
                if (isActive) {
                    val y = size.height - 1.5.dp.toPx()
                    drawLine(underline, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
                }
            }
            .selectable(selected = isActive, role = Role.Tab, onClick = onSelect)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SiteIcon(url = tab.url, title = tab.displayTitle, size = 16.dp, isNewTab = tab.isNewTabPage)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = tab.displayTitle,
            style = MaterialTheme.typography.labelLarge,
            color = if (isActive) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp)
        )
        RemIconButton(
            icon = Icons.Outlined.Close,
            label = "Close ${tab.displayTitle}",
            onClick = onClose,
            size = 28.dp,
            iconSize = 16.dp
        )
    }
}

@Composable
private fun SplitPresets(current: Float, onSetPreset: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .height(36.dp)
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SPLIT_PRESETS.forEach { (fraction, label) ->
            val selected = abs(current - fraction) < 0.01f
            val content = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .background(if (selected) colors.primaryContainer else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSetPreset(fraction) })
                    .semantics { contentDescription = "Split $label" }
                    .testTag("split_preset_${(fraction * 100).toInt()}")
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LayoutGlyph(mode = LayoutMode.SPLIT_1X2, color = content, firstPaneFraction = fraction)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}
