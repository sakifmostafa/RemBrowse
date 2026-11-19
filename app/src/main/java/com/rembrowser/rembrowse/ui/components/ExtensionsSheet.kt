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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.ExtensionsTab
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.ui.theme.RemAccentAmber
import com.rembrowser.rembrowse.ui.theme.RemAccentCyan
import com.rembrowser.rembrowse.ui.theme.RemAccentEmerald
import com.rembrowser.rembrowse.ui.theme.RemBackground
import com.rembrowser.rembrowse.ui.theme.RemBorder
import com.rembrowser.rembrowse.ui.theme.RemDanger
import com.rembrowser.rembrowse.ui.theme.RemSurface
import com.rembrowser.rembrowse.ui.theme.RemSurfaceElevated
import com.rembrowser.rembrowse.ui.theme.RemTextPrimary
import com.rembrowser.rembrowse.ui.theme.RemTextSecondary
import java.util.Locale

@Composable
fun ExtensionsManagerDialog(
    uiState: ExtensionsUiState,
    onDismiss: () -> Unit,
    onSelectTab: (ExtensionsTab) -> Unit,
    onRefreshInstalled: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onInstallAddon: (AmoAddon) -> Unit,
    onInstallUri: (String) -> Unit,
    onPickLocalXpi: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenAction: (String) -> Unit,
    onClearMessages: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(RemSurface)
                .border(1.dp, RemBorder, RoundedCornerShape(16.dp))
                .testTag("extensions_dialog")
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RemSurfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = RemAccentAmber,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RemBrowse WebExtensions",
                        color = RemTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mozilla GeckoView native .xpi runtime",
                        color = RemTextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onRefreshInstalled) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Extensions",
                        tint = RemTextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Extensions",
                        tint = RemTextPrimary
                    )
                }
            }

            // Tabs
            val tabs = listOf(
                ExtensionsTab.INSTALLED to "Installed (${uiState.installedExtensions.size})",
                ExtensionsTab.DISCOVER_AMO to "Browse AMO",
                ExtensionsTab.INSTALL_XPI to "Install .xpi"
            )
            TabRow(
                selectedTabIndex = tabs.indexOfFirst { it.first == uiState.selectedTab }.coerceAtLeast(0),
                containerColor = RemSurface,
                contentColor = RemAccentCyan
            ) {
                tabs.forEach { (tab, label) ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (uiState.selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Status / Error Banner
            if (!uiState.statusMessage.isNullOrBlank() || !uiState.errorMessage.isNullOrBlank()) {
                val isError = !uiState.errorMessage.isNullOrBlank()
                val text = uiState.errorMessage ?: uiState.statusMessage.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isError) RemDanger.copy(alpha = 0.16f)
                            else RemAccentEmerald.copy(alpha = 0.16f)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = text,
                        color = if (isError) RemDanger else RemAccentEmerald,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Dismiss",
                        color = RemTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(onClick = onClearMessages)
                    )
                }
            }

            // Body
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                when (uiState.selectedTab) {
                    ExtensionsTab.INSTALLED -> InstalledExtensionsList(
                        extensions = uiState.installedExtensions,
                        onSetEnabled = onSetEnabled,
                        onUninstall = onUninstall,
                        onOpenAction = { id ->
                            onDismiss()
                            onOpenAction(id)
                        },
                        onGoToDiscover = { onSelectTab(ExtensionsTab.DISCOVER_AMO) }
                    )

                    ExtensionsTab.DISCOVER_AMO -> DiscoverAmoTab(
                        uiState = uiState,
                        onSearchQueryChange = onSearchQueryChange,
                        onSubmitSearch = onSubmitSearch,
                        onInstallAddon = onInstallAddon
                    )

                    ExtensionsTab.INSTALL_XPI -> InstallXpiTab(
                        isInstalling = uiState.isInstalling,
                        onInstallUri = onInstallUri,
                        onPickLocalXpi = onPickLocalXpi
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionsList(
    extensions: List<InstalledExtension>,
    onSetEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenAction: (String) -> Unit,
    onGoToDiscover: () -> Unit
) {
    if (extensions.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = RemTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "No WebExtensions installed yet",
                color = RemTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Browse addons.mozilla.org or install a signed .xpi file.",
                color = RemTextSecondary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onGoToDiscover,
                colors = ButtonDefaults.buttonColors(containerColor = RemAccentCyan, contentColor = RemBackground)
            ) {
                Text("Browse Add-ons (AMO)", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(extensions, key = { it.id }) { ext ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(RemSurfaceElevated)
                    .border(1.dp, RemBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ext.name,
                            color = RemTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "v${ext.version}",
                            color = RemTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (ext.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = ext.description,
                            color = RemTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (ext.hasBrowserAction && ext.enabled) {
                    IconButton(onClick = { onOpenAction(ext.id) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open Extension Popup",
                            tint = RemAccentCyan
                        )
                    }
                }

                Switch(
                    checked = ext.enabled,
                    onCheckedChange = { onSetEnabled(ext.id, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RemBackground,
                        checkedTrackColor = RemAccentCyan
                    )
                )

                IconButton(onClick = { onUninstall(ext.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Uninstall Extension",
                        tint = RemDanger
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverAmoTab(
    uiState: ExtensionsUiState,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onInstallAddon: (AmoAddon) -> Unit
) {
    val installedIds = remember(uiState.installedExtensions) {
        uiState.installedExtensions.map { it.id }.toSet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search uBlock Origin, Dark Reader, Bitwarden...", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RemAccentCyan,
                    unfocusedBorderColor = RemBorder,
                    focusedTextColor = RemTextPrimary,
                    unfocusedTextColor = RemTextPrimary
                ),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSubmitSearch,
                colors = ButtonDefaults.buttonColors(containerColor = RemAccentCyan, contentColor = RemBackground)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.isLoadingCatalog) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RemAccentCyan)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.catalogAddons, key = { it.id }) { addon ->
                    val isInstalled = addon.id in installedIds
                    val isCurrentlyInstalling = uiState.isInstalling && uiState.installingIdOrUrl == addon.id

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(RemSurfaceElevated)
                            .border(1.dp, RemBorder, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!addon.iconUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = addon.iconUrl,
                                contentDescription = addon.name,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RemSurface)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RemSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Extension, contentDescription = null, tint = RemAccentAmber)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = addon.name,
                                color = RemTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${addon.author} • v${addon.version}",
                                color = RemTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (addon.summary.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = addon.summary,
                                    color = RemTextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (addon.rating > 0.0) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = RemAccentAmber,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.1f", addon.rating),
                                        color = RemTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isInstalled) {
                            Text(
                                text = "Installed",
                                color = RemAccentEmerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Button(
                                onClick = { onInstallAddon(addon) },
                                enabled = !uiState.isInstalling,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RemAccentCyan,
                                    contentColor = RemBackground
                                )
                            ) {
                                if (isCurrentlyInstalling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = RemBackground
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallXpiTab(
    isInstalling: Boolean,
    onInstallUri: (String) -> Unit,
    onPickLocalXpi: () -> Unit
) {
    var xpiUrlInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Install from Direct .xpi URL",
            color = RemTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Paste a Mozilla-signed .xpi download link from addons.mozilla.org or another HTTPS source.",
            color = RemTextSecondary,
            fontSize = 12.sp
        )

        OutlinedTextField(
            value = xpiUrlInput,
            onValueChange = { xpiUrlInput = it },
            placeholder = { Text("https://addons.mozilla.org/.../extension.xpi", fontSize = 13.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RemAccentCyan,
                unfocusedBorderColor = RemBorder,
                focusedTextColor = RemTextPrimary,
                unfocusedTextColor = RemTextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onInstallUri(xpiUrlInput) },
            enabled = !isInstalling && xpiUrlInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = RemAccentCyan, contentColor = RemBackground)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isInstalling) "Installing..." else "Install from URL", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Install Local .xpi Package",
            color = RemTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Select a Mozilla-signed .xpi file saved on your device.",
            color = RemTextSecondary,
            fontSize = 12.sp
        )

        OutlinedButton(
            onClick = onPickLocalXpi,
            enabled = !isInstalling
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = RemAccentAmber)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose .xpi File from Device", color = RemTextPrimary)
        }
    }
}

@Composable
fun ExtensionInstallPromptDialog(
    prompt: ExtensionInstallPrompt,
    onDecision: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        containerColor = RemSurfaceElevated,
        title = {
            Text(
                text = "Add \"${prompt.extensionName}\"?",
                color = RemTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Version ${prompt.version} requests the following permissions:",
                    color = RemTextSecondary,
                    fontSize = 13.sp
                )
                if (prompt.permissions.isEmpty()) {
                    Text(
                        text = "• No special permissions requested",
                        color = RemTextPrimary,
                        fontSize = 12.sp
                    )
                } else {
                    prompt.permissions.take(10).forEach { perm ->
                        Text(
                            text = "• $perm",
                            color = RemTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                    if (prompt.permissions.size > 10) {
                        Text(
                            text = "• ...and ${prompt.permissions.size - 10} more",
                            color = RemTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(true) },
                colors = ButtonDefaults.buttonColors(containerColor = RemAccentCyan, contentColor = RemBackground)
            ) {
                Text("Add Extension", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) {
                Text("Cancel", color = RemTextSecondary)
            }
        }
    )
}

@Composable
fun ExtensionPopupDialog(
    popup: ExtensionPopupState,
    onDismiss: () -> Unit,
    popupContent: @Composable (Modifier) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(14.dp))
                .background(RemSurface)
                .border(1.dp, RemAccentAmber, RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RemSurfaceElevated)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    tint = RemAccentAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = popup.extensionName,
                    color = RemTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Extension Popup",
                        tint = RemTextPrimary
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                popupContent(Modifier.fillMaxSize())
            }
        }
    }
}
