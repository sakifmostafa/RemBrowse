package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.data.AvailableUpdate

/** Everything the update controls on the About page can ask for. */
data class UpdateActions(
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onInstall: () -> Unit,
    val onSetCheckAutomatically: (Boolean) -> Unit
)

/**
 * Offers a new version found by the daily check. Unlike the download notice it stays until
 * answered: "Update" downloads it, closing it waits for the next version.
 */
@Composable
fun UpdateNoticeBar(
    update: AvailableUpdate?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (update == null) return
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = 520.dp)
            .testTag("update_notice"),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHighest,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "RemBrowse ${update.versionName} is available",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onUpdate, modifier = Modifier.testTag("update_notice_action")) {
                Text("Update")
            }
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Not now",
                onClick = onDismiss,
                iconSize = 18.dp
            )
        }
    }
}
