package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.ui.theme.RemAccentAmber
import com.rembrowser.rembrowse.ui.theme.RemAccentCyan
import com.rembrowser.rembrowse.ui.theme.RemAccentIndigo
import com.rembrowser.rembrowse.ui.theme.RemBackground
import com.rembrowser.rembrowse.ui.theme.RemBorder
import com.rembrowser.rembrowse.ui.theme.RemDanger
import com.rembrowser.rembrowse.ui.theme.RemSurface
import com.rembrowser.rembrowse.ui.theme.RemSurfaceElevated
import com.rembrowser.rembrowse.ui.theme.RemTextPrimary
import com.rembrowser.rembrowse.ui.theme.RemTextSecondary
import com.rembrowser.rembrowse.util.UrlResolver

@Composable
fun TabsAndBookmarksDialog(
    uiState: BrowserUiState,
    onDismiss: () -> Unit,
    onAssignTabToPane: (paneIndex: Int, tabId: String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(16.dp))
                .background(RemSurface)
                .border(1.dp, RemBorder, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RemSurfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (selectedSection == 0) Icons.Default.Tab else Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = if (selectedSection == 0) RemAccentIndigo else RemAccentAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Tabs & Bookmarks",
                    color = RemTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onNewTab()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RemAccentCyan,
                        contentColor = RemBackground
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Tab", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = RemTextPrimary)
                }
            }

            TabRow(
                selectedTabIndex = selectedSection,
                containerColor = RemSurface,
                contentColor = RemAccentCyan
            ) {
                Tab(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    text = { Text("Open Tabs (${uiState.tabs.size})", fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    text = { Text("Bookmarks (${uiState.bookmarks.size})", fontSize = 13.sp) }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                if (selectedSection == 0) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.tabs, key = { it.id }) { tab ->
                            val assignedPane = uiState.paneTabIds.indexOf(tab.id)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(RemSurfaceElevated)
                                    .border(
                                        width = 1.dp,
                                        color = if (assignedPane == uiState.activePaneIndex) RemAccentCyan else RemBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onAssignTabToPane(uiState.activePaneIndex, tab.id)
                                        onDismiss()
                                    }
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tab.title.ifBlank { UrlResolver.extractDisplayHost(tab.url) },
                                            color = RemTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = tab.url,
                                            color = RemTextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (assignedPane >= 0) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(RemAccentCyan.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "Pane ${assignedPane + 1}",
                                                color = RemAccentCyan,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onCloseTab(tab.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Tab",
                                            tint = RemTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (uiState.layoutMode.paneCount > 1) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (paneIdx in 0 until uiState.layoutMode.paneCount) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(RemSurface)
                                                    .border(1.dp, RemBorder, RoundedCornerShape(5.dp))
                                                    .clickable {
                                                        onAssignTabToPane(paneIdx, tab.id)
                                                        onDismiss()
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "Send to P${paneIdx + 1}",
                                                    color = RemTextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (uiState.bookmarks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Tap the bookmark icon in the address bar to save pages here.",
                                color = RemTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.bookmarks, key = { it.id }) { bookmark ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(RemSurfaceElevated)
                                        .border(1.dp, RemBorder, RoundedCornerShape(10.dp))
                                        .clickable {
                                            onOpenBookmark(bookmark.url)
                                            onDismiss()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = null,
                                        tint = RemAccentAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bookmark.title,
                                            color = RemTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = bookmark.url,
                                            color = RemTextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Bookmark",
                                            tint = RemDanger
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
