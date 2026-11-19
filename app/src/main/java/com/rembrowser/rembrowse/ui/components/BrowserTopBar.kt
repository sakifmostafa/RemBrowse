package com.rembrowser.rembrowse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rembrowser.rembrowse.model.BrowserUiState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.LayoutMode
import com.rembrowser.rembrowse.ui.theme.RemAccentAmber
import com.rembrowser.rembrowse.ui.theme.RemAccentCyan
import com.rembrowser.rembrowse.ui.theme.RemAccentIndigo
import com.rembrowser.rembrowse.ui.theme.RemBackground
import com.rembrowser.rembrowse.ui.theme.RemBorder
import com.rembrowser.rembrowse.ui.theme.RemSurface
import com.rembrowser.rembrowse.ui.theme.RemSurfaceElevated
import com.rembrowser.rembrowse.ui.theme.RemTextPrimary
import com.rembrowser.rembrowse.ui.theme.RemTextSecondary
import com.rembrowser.rembrowse.util.UrlResolver

@Composable
fun BrowserTopBar(
    uiState: BrowserUiState,
    installedExtensions: List<InstalledExtension>,
    addressBarFocusRequester: FocusRequester,
    onAddressBarChange: (String) -> Unit,
    onAddressBarFocusChange: (Boolean) -> Unit,
    onSubmitAddressBar: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSelectLayout: (LayoutMode) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onOpenTabsDrawer: () -> Unit,
    onOpenExtensions: () -> Unit,
    onTriggerExtensionAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTab = uiState.activeTab
    val activeTabId = uiState.activeTabId
    val enabledExtensions = installedExtensions.filter { it.enabled }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RemSurface)
    ) {
        // Desktop Tab Bar Strip — shows all open tabs (including tabs collapsed from 2x2 -> 1x1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RemBackground)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            uiState.tabs.forEach { tab ->
                val isFocusedTab = tab.id == activeTabId
                val paneIndex = uiState.paneTabIds
                    .take(uiState.layoutMode.paneCount)
                    .indexOf(tab.id)
                val isVisibleInPane = paneIndex >= 0

                val tabBg = when {
                    isFocusedTab -> RemSurfaceElevated
                    isVisibleInPane -> RemSurface
                    else -> RemBackground
                }
                val tabBorder = when {
                    isFocusedTab -> RemAccentCyan
                    isVisibleInPane -> RemBorder
                    else -> RemBorder.copy(alpha = 0.55f)
                }

                Row(
                    modifier = Modifier
                        .widthIn(min = 120.dp, max = 210.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tabBg)
                        .border(
                            width = if (isFocusedTab) 1.2.dp else 1.dp,
                            color = tabBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTab(tab.id) }
                        .testTag("tab_bar_item_${tab.id}")
                        .padding(start = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (uiState.layoutMode.paneCount > 1 && isVisibleInPane) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isFocusedTab) RemAccentCyan
                                    else RemAccentIndigo.copy(alpha = 0.35f)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "P${paneIndex + 1}",
                                color = if (isFocusedTab) RemBackground else RemTextPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isFocusedTab) RemAccentCyan else RemTextSecondary.copy(alpha = 0.5f))
                        )
                    }

                    Text(
                        text = tab.title.ifBlank { UrlResolver.extractDisplayHost(tab.url) },
                        color = if (isFocusedTab) RemTextPrimary else RemTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isFocusedTab) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onCloseTab(tab.id) }
                            .testTag("tab_bar_close_${tab.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close ${tab.title}",
                            tint = RemTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Compact '+' New Tab button right next to tabs
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(RemSurfaceElevated)
                    .border(1.dp, RemBorder, RoundedCornerShape(7.dp))
                    .clickable(onClick = onNewTab)
                    .testTag("new_tab_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Tab",
                    tint = RemAccentCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Primary Omnibox & Navigation Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                enabled = activeTab?.canGoBack == true,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (activeTab?.canGoBack == true) RemTextPrimary else RemTextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onForward,
                enabled = activeTab?.canGoForward == true,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (activeTab?.canGoForward == true) RemTextPrimary else RemTextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onReloadOrStop,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (activeTab?.isLoading == true) "Stop" else "Reload",
                    tint = RemTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Omnibox input
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RemSurfaceElevated)
                    .border(
                        width = 1.dp,
                        color = if (uiState.isAddressBarFocused) RemAccentCyan else RemBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.layoutMode.paneCount > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(RemAccentCyan.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "P${uiState.activePaneIndex + 1}",
                            color = RemAccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                BasicTextField(
                    value = uiState.addressBarInput,
                    onValueChange = onAddressBarChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = RemTextPrimary,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(RemAccentCyan),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { onSubmitAddressBar() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("omnibox_input")
                        .focusRequester(addressBarFocusRequester)
                        .onFocusChanged { onAddressBarFocusChange(it.isFocused) },
                    decorationBox = { innerTextField ->
                        if (uiState.addressBarInput.isEmpty()) {
                            Text(
                                text = "Search or enter URL...",
                                color = RemTextSecondary,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isActiveTabBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (uiState.isActiveTabBookmarked) RemAccentAmber else RemTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Desktop / Mobile UA quick toggle
            IconButton(
                onClick = onToggleDesktopMode,
                modifier = Modifier.size(34.dp)
            ) {
                val isDesktop = activeTab?.desktopMode != false
                Icon(
                    imageVector = if (isDesktop) Icons.Default.Computer else Icons.Default.Smartphone,
                    contentDescription = if (isDesktop) "Desktop Mode On" else "Mobile Mode On",
                    tint = if (isDesktop) RemAccentCyan else RemTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Extensions button with badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RemSurfaceElevated)
                    .border(1.dp, RemBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenExtensions)
                    .testTag("extensions_button")
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = "Extensions",
                        tint = RemAccentAmber,
                        modifier = Modifier.size(17.dp)
                    )
                    if (enabledExtensions.isNotEmpty()) {
                        Text(
                            text = enabledExtensions.size.toString(),
                            color = RemTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Tabs / Bookmarks manager button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(RemSurfaceElevated)
                    .border(1.dp, RemBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenTabsDrawer)
                    .testTag("tabs_drawer_button")
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tab,
                        contentDescription = "Tabs and Bookmarks",
                        tint = RemAccentIndigo,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = uiState.tabs.size.toString(),
                        color = RemTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Layout Switcher & Quick Extension Action Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GridView,
                contentDescription = "Layouts",
                tint = RemTextSecondary,
                modifier = Modifier.size(14.dp)
            )
            LayoutMode.entries.forEach { mode ->
                val selected = uiState.layoutMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) RemAccentCyan else RemSurfaceElevated)
                        .border(
                            width = 1.dp,
                            color = if (selected) RemAccentCyan else RemBorder,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectLayout(mode) }
                        .testTag("layout_${mode.label}")
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = mode.label,
                        color = if (selected) RemSurface else RemTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            val actionableExtensions = enabledExtensions.filter { it.hasBrowserAction }
            if (actionableExtensions.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                actionableExtensions.forEach { ext ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RemAccentAmber.copy(alpha = 0.14f))
                            .border(1.dp, RemAccentAmber.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .clickable { onTriggerExtensionAction(ext.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(RemAccentAmber)
                        )
                        Text(
                            text = ext.name.take(16),
                            color = RemTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!ext.badgeText.isNullOrBlank()) {
                            Text(
                                text = ext.badgeText,
                                color = RemAccentAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Loading progress hairline
        if (activeTab?.isLoading == true) {
            LinearProgressIndicator(
                progress = { (activeTab.progress.coerceIn(5, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = RemAccentCyan,
                trackColor = RemSurfaceElevated
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(RemBorder)
            )
        }
    }
}
