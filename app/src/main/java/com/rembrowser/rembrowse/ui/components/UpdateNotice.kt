package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    NoticeSnackbar(
        testTag = "update_notice",
        dismissLabel = "Not now",
        onDismiss = onDismiss,
        modifier = modifier,
        actionLabel = "Update",
        actionTestTag = "update_notice_action",
        onAction = onUpdate
    ) {
        Icon(
            imageVector = Icons.Outlined.SystemUpdate,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "RemBrowse ${update.versionName} is available",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
