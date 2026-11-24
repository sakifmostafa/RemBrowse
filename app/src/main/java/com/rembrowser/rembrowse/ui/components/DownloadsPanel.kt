package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadNotice
import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.DownloadStatus
import com.rembrowser.rembrowse.model.DownloadsUiState
import com.rembrowser.rembrowse.util.ByteSizes
import kotlinx.coroutines.delay

/** What the downloads list can ask for. Ids are [DownloadItem.id]. */
class DownloadsPanelActions(
    val onOpen: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onClearFinished: () -> Unit,
    val onOpenFolder: () -> Unit
)

/** Where files go, and the list of recent downloads, as shown in Settings → Downloads. */
@Composable
fun DownloadsPanel(
    uiState: DownloadsUiState,
    locationLabel: String,
    actions: DownloadsPanelActions,
    modifier: Modifier = Modifier,
    /** Off where the system's downloads screen would not list the files saved here. */
    showOpenFolder: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.testTag("downloads_panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Files are saved to", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = locationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (showOpenFolder) {
                Spacer(modifier = Modifier.width(16.dp))
                RemOutlinedButton(onClick = actions.onOpenFolder, modifier = Modifier.testTag("downloads_open_folder")) {
                    Text("Open folder")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Recent downloads",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = actions.onClearFinished,
                enabled = uiState.hasFinishedItems,
                modifier = Modifier.testTag("downloads_clear")
            ) {
                Text("Clear list")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (uiState.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "No downloads yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Files you download appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            // Bounded (the list keeps at most a few dozen entries), so a plain column inside the
            // page's own scroll container is enough.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.items.forEach { item ->
                    DownloadRow(item = item, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, actions: DownloadsPanelActions) {
    val colors = MaterialTheme.colorScheme
    val failed = item.status == DownloadStatus.FAILED
    ListCard(modifier = Modifier.testTag("download_item_${item.id}")) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                // Failure is marked by the icon's shape as well as its colour
                Icon(
                    imageVector = if (failed) Icons.Outlined.ErrorOutline else Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = if (failed) colors.error else colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = downloadStatusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isInProgress) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val progress = item.progress
                    val barModifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Downloading ${item.fileName}" }
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            color = colors.primary,
                            trackColor = colors.surfaceContainerHighest,
                            modifier = barModifier
                        )
                    } else {
                        // The server did not say how big the file is
                        LinearProgressIndicator(
                            color = colors.primary,
                            trackColor = colors.surfaceContainerHighest,
                            modifier = barModifier
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (item.status) {
                DownloadStatus.IN_PROGRESS -> RemIconButton(
                    icon = Icons.Outlined.Close,
                    label = "Cancel download of ${item.fileName}",
                    onClick = { actions.onCancel(item.id) },
                    modifier = Modifier.testTag("download_cancel_${item.id}")
                )

                else -> {
                    if (item.status == DownloadStatus.COMPLETED) {
                        TextButton(
                            onClick = { actions.onOpen(item.id) },
                            modifier = Modifier
                                .testTag("download_open_${item.id}")
                                .semantics { contentDescription = "Open ${item.fileName}" }
                        ) {
                            Text("Open")
                        }
                    }
                    RemIconButton(
                        icon = Icons.Outlined.Close,
                        label = "Remove ${item.fileName} from the list",
                        onClick = { actions.onRemove(item.id) },
                        modifier = Modifier.testTag("download_remove_${item.id}")
                    )
                }
            }
        }
    }
}

/** "3.2 MB of 12.4 MB · example.com", "12.4 MB · example.com", "Couldn't download · example.com". */
internal fun downloadStatusLine(item: DownloadItem): String {
    val state = when (item.status) {
        DownloadStatus.IN_PROGRESS -> {
            val received = ByteSizes.format(item.receivedBytes)
            if (item.totalBytes > 0) "$received of ${ByteSizes.format(item.totalBytes)}" else received
        }
        DownloadStatus.COMPLETED -> ByteSizes.format(item.receivedBytes)
        DownloadStatus.FAILED -> "Couldn't download"
        DownloadStatus.CANCELLED -> "Cancelled"
    }
    return listOf(state, item.sourceHost).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Asks before a file is saved, naming the file, its size and the site it comes from. */
@Composable
fun DownloadPromptDialog(request: DownloadRequest, onDecision: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val details = listOf(
        if (request.contentLength > 0) ByteSizes.format(request.contentLength) else "",
        if (request.sourceHost.isNotBlank()) "from ${request.sourceHost}" else "from this page"
    ).filter { it.isNotBlank() }.joinToString(" · ")

    AlertDialog(
        onDismissRequest = { onDecision(false) },
        modifier = Modifier.testTag("download_prompt"),
        title = { Text(text = "Download this file?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = request.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = details, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            PrimaryDialogButton(
                label = "Download",
                onClick = { onDecision(true) },
                modifier = Modifier.testTag("download_confirm")
            )
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }, modifier = Modifier.testTag("download_decline")) {
                Text("Cancel")
            }
        }
    )
}

/**
 * A short-lived line over the bottom of the window: a download started, finished or failed, or
 * a file could not be opened. It leaves by itself, so it never has to be dismissed to get at
 * the page underneath.
 */
@Composable
fun DownloadNoticeBar(
    notice: DownloadNotice?,
    message: String?,
    onOpen: (String) -> Unit,
    onShowDownloads: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (notice == null && message == null) return
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(notice, message) {
        delay(NOTICE_VISIBLE_MILLIS)
        onDismiss()
    }

    val isError = message != null || notice?.status == DownloadStatus.FAILED
    val text = message ?: when (notice?.status) {
        DownloadStatus.COMPLETED -> "Downloaded ${notice.fileName}"
        DownloadStatus.FAILED -> "Couldn't download ${notice.fileName}"
        else -> "Downloading ${notice?.fileName.orEmpty()}"
    }
    val canOpen = message == null && notice?.status == DownloadStatus.COMPLETED

    Surface(
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = 520.dp)
            .testTag("download_notice"),
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
                imageVector = when {
                    isError -> Icons.Outlined.ErrorOutline
                    canOpen -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.FileDownload
                },
                contentDescription = if (isError) "Error" else null,
                tint = if (isError) colors.error else colors.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val id = notice?.itemId
                    if (canOpen && id != null) onOpen(id) else onShowDownloads()
                    onDismiss()
                },
                modifier = Modifier.testTag("download_notice_action")
            ) {
                Text(if (canOpen) "Open" else "Show")
            }
            RemIconButton(
                icon = Icons.Outlined.Close,
                label = "Dismiss",
                onClick = onDismiss,
                iconSize = 18.dp
            )
        }
    }
}

private const val NOTICE_VISIBLE_MILLIS = 6_000L
