package com.rembrowser.rembrowse.ui.components

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.ExtensionPromptKind
import com.rembrowser.rembrowse.model.ExtensionsTab
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.ui.theme.RemDimens
import com.rembrowser.rembrowse.ui.theme.RemMotion
import java.util.Locale
import kotlinx.coroutines.delay

/** Everything the extensions panel can ask for. */
class ExtensionsPanelActions(
    val onSelectTab: (ExtensionsTab) -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onSubmitSearch: () -> Unit,
    val onInstallAddon: (AmoAddon) -> Unit,
    val onInstallUri: (String) -> Unit,
    val onPickLocalXpi: () -> Unit,
    val onSetEnabled: (String, Boolean) -> Unit,
    val onUninstall: (String) -> Unit,
    val onOpenAction: (String) -> Unit,
    val onClearMessages: () -> Unit
)

/**
 * Extension management as it appears in Settings: what is installed, the Firefox Add-ons
 * catalog, and installing a package from a link or a file.
 */
@Composable
fun ExtensionsPanel(
    uiState: ExtensionsUiState,
    actions: ExtensionsPanelActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.testTag("extensions_panel")) {
        val tabs = listOf(
            ExtensionsTab.INSTALLED to "Installed",
            ExtensionsTab.DISCOVER_AMO to "Firefox Add-ons",
            ExtensionsTab.INSTALL_XPI to "From file"
        )
        val selectedIndex = tabs.indexOfFirst { it.first == uiState.selectedTab }.coerceAtLeast(0)
        SecondaryTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = Color.Transparent,
            contentColor = colors.primary,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedIndex),
                    color = colors.primary
                )
            },
            divider = { HorizontalDivider(color = colors.outlineVariant) }
        ) {
            tabs.forEach { (tab, label) ->
                val selected = uiState.selectedTab == tab
                Tab(
                    selected = selected,
                    onClick = { actions.onSelectTab(tab) },
                    selectedContentColor = colors.primary,
                    unselectedContentColor = colors.onSurfaceVariant,
                    text = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("extensions_tab_${tab.name.lowercase()}")
                )
            }
        }

        StatusBanner(
            status = uiState.statusMessage,
            error = uiState.errorMessage,
            onDismiss = actions.onClearMessages
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            when (uiState.selectedTab) {
                ExtensionsTab.INSTALLED -> InstalledExtensionsList(
                    extensions = uiState.installedExtensions,
                    onSetEnabled = actions.onSetEnabled,
                    onUninstall = actions.onUninstall,
                    onOpenAction = actions.onOpenAction,
                    onGoToDiscover = { actions.onSelectTab(ExtensionsTab.DISCOVER_AMO) }
                )

                ExtensionsTab.DISCOVER_AMO -> DiscoverAmoTab(
                    uiState = uiState,
                    onSearchQueryChange = actions.onSearchQueryChange,
                    onSubmitSearch = actions.onSubmitSearch,
                    onInstallAddon = actions.onInstallAddon
                )

                ExtensionsTab.INSTALL_XPI -> InstallXpiTab(
                    isInstalling = uiState.isInstalling,
                    onInstallUri = actions.onInstallUri,
                    onPickLocalXpi = actions.onPickLocalXpi
                )
            }
        }
    }
}

/** Success and error feedback, announced to screen readers and marked by icon as well as colour. */
@Composable
private fun StatusBanner(status: String?, error: String?, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val isError = !error.isNullOrBlank()
    val text = error?.takeIf { it.isNotBlank() } ?: status?.takeIf { it.isNotBlank() } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) colors.errorContainer else colors.surfaceContainerHigh)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = if (isError) "Error" else null,
            tint = if (isError) colors.onErrorContainer else colors.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) colors.onErrorContainer else colors.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        )
        RemIconButton(
            icon = Icons.Outlined.Close,
            label = "Dismiss message",
            onClick = onDismiss,
            tint = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
            iconSize = 18.dp
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryDialogButton(label = actionLabel, onClick = onAction)
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
    val colors = MaterialTheme.colorScheme
    var pendingRemoval by remember { mutableStateOf<InstalledExtension?>(null) }

    if (extensions.isEmpty()) {
        EmptyState(
            title = "No extensions yet",
            message = "Add content blockers, password managers and more from Firefox Add-ons.",
            actionLabel = "Browse Firefox Add-ons",
            onAction = onGoToDiscover
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(extensions, key = { it.id }) { ext ->
            ListCard {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ext.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Version ${ext.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        if (ext.description.isNotBlank()) {
                            Text(
                                text = ext.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (ext.hasBrowserAction && ext.enabled) {
                        RemIconButton(
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            label = "Open ${ext.name}",
                            onClick = { onOpenAction(ext.id) }
                        )
                    }
                    Switch(
                        checked = ext.enabled,
                        onCheckedChange = { onSetEnabled(ext.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.primaryContainer,
                            checkedThumbColor = colors.onPrimaryContainer,
                            checkedBorderColor = colors.primaryContainer
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = if (ext.enabled) "Disable ${ext.name}" else "Enable ${ext.name}"
                        }
                    )
                    RemIconButton(
                        icon = Icons.Outlined.DeleteOutline,
                        label = "Remove ${ext.name}",
                        onClick = { pendingRemoval = ext }
                    )
                }
            }
        }
    }

    // Removing an extension deletes its data, so it is confirmed first
    pendingRemoval?.let { ext ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${ext.name}?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "The extension and the data it stored will be deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onUninstall(ext.id)
                    },
                    modifier = Modifier.testTag("confirm_remove_extension")
                ) {
                    Text("Remove", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DiscoverAmoTab(
    uiState: ExtensionsUiState,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onInstallAddon: (AmoAddon) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val installedIds = remember(uiState.installedExtensions) {
        uiState.installedExtensions.map { it.id }.toSet()
    }

    Column {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Search Firefox Add-ons") },
            placeholder = { Text("uBlock Origin, Dark Reader, Bitwarden…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("extensions_search")
        )

        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.isLoadingCatalog -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                RemLoadingIndicator(modifier = Modifier.semantics { contentDescription = "Loading add-ons" })
            }

            uiState.catalogError != null -> EmptyState(
                title = "Couldn't load add-ons",
                message = uiState.catalogError,
                actionLabel = "Try again",
                onAction = onSubmitSearch
            )

            uiState.catalogAddons.isEmpty() -> EmptyState(
                title = "No add-ons found",
                message = "Try a different search term."
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.catalogAddons, key = { it.id }) { addon ->
                    AddonRow(
                        addon = addon,
                        isInstalled = addon.id in installedIds,
                        isInstalling = uiState.isInstalling && uiState.installingIdOrUrl == addon.id,
                        canInstall = !uiState.isInstalling,
                        onInstall = { onInstallAddon(addon) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    addon: AmoAddon,
    isInstalled: Boolean,
    isInstalling: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ListCard {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (!addon.iconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = addon.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = addon.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (addon.rating > 0.0) {
                        val rating = String.format(Locale.US, "%.1f", addon.rating)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .semantics { contentDescription = "Rated $rating out of 5" }
                        )
                    }
                }
                if (addon.summary.isNotBlank()) {
                    Text(
                        text = addon.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            when {
                isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Added", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }

                isInstalling -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = "Adding ${addon.name}" },
                    strokeWidth = 2.dp,
                    color = colors.primary
                )

                else -> PrimaryDialogButton(
                    label = "Add",
                    enabled = canInstall,
                    onClick = onInstall,
                    modifier = Modifier.semantics { contentDescription = "Add ${addon.name}" }
                )
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
    val colors = MaterialTheme.colorScheme
    var xpiUrlInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupTitle("From a link")
        OutlinedTextField(
            value = xpiUrlInput,
            onValueChange = { xpiUrlInput = it },
            label = { Text("Extension link") },
            placeholder = { Text("https://…/extension.xpi") },
            supportingText = { Text("Must be an https:// link to a Mozilla-signed .xpi file.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!isInstalling && xpiUrlInput.isNotBlank()) onInstallUri(xpiUrlInput) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("xpi_url_input")
        )
        PrimaryDialogButton(
            label = if (isInstalling) "Installing…" else "Install from link",
            enabled = !isInstalling && xpiUrlInput.isNotBlank(),
            onClick = { onInstallUri(xpiUrlInput) }
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        GroupTitle("From this device")
        Text(
            text = "Choose a Mozilla-signed .xpi file you have already downloaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        RemOutlinedButton(onClick = onPickLocalXpi, enabled = !isInstalling) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose file")
        }
    }
}

@Composable
fun ExtensionInstallPromptDialog(
    prompt: ExtensionInstallPrompt,
    onDecision: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val (title, intro, confirmLabel) = when (prompt.kind) {
        ExtensionPromptKind.INSTALL -> Triple(
            "Add ${prompt.extensionName}?",
            "Version ${prompt.version} will be able to:",
            "Add"
        )
        ExtensionPromptKind.UPDATE -> Triple(
            "Update ${prompt.extensionName}?",
            "Version ${prompt.version} needs new permissions:",
            "Update"
        )
        ExtensionPromptKind.OPTIONAL_PERMISSIONS -> Triple(
            "Allow ${prompt.extensionName}?",
            "It is asking for additional permissions:",
            "Allow"
        )
    }
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (prompt.permissions.isEmpty()) {
                        "It doesn't ask for any special permissions."
                    } else {
                        intro
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                prompt.permissions.take(10).forEach { permission ->
                    Text(
                        text = "•  $permission",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                }
                if (prompt.permissions.size > 10) {
                    Text(
                        text = "and ${prompt.permissions.size - 10} more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { PrimaryDialogButton(label = confirmLabel, onClick = { onDecision(true) }) },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text("Cancel") }
        }
    )
}

/**
 * An extension's popup. On windows wide enough for the tab rail it opens at the bottom-left,
 * beside the rail's extension buttons, the way a desktop toolbar popup drops from its button,
 * and leaves the page undimmed. On phone-sized windows it is centred.
 */
@Composable
fun ExtensionPopupDialog(
    popup: ExtensionPopupState,
    onDismiss: () -> Unit,
    popupContent: @Composable (Modifier) -> Unit,
    icon: Bitmap? = null,
    anchorToRail: Boolean = false,
    /** The rail's width beside the page: wider while the rail is expanded there. */
    railWidth: Dp = RemDimens.RailWidth
) {
    val colors = MaterialTheme.colorScheme
    // Sized to what the popup's content asked for, as desktop Firefox does, within its limits
    // (the window caps it further); the default until it has said, or if it never does
    val width = popup.contentWidth?.dp?.coerceIn(PopupMinWidth, PopupMaxWidth) ?: PopupDefaultWidth
    val pageHeight = popup.contentHeight?.dp?.coerceIn(PopupMinPageHeight, PopupMaxPageHeight)
        ?: PopupDefaultPageHeight
    val atDefaultSize = width == PopupDefaultWidth && pageHeight == PopupDefaultPageHeight
    val shown = rememberPopupShown(popup, atDefaultSize)
    // A short fade once it is shown, in place; hidden again at once for the next popup
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (shown) RemMotion.fastEffects() else snap(),
        label = "extension popup"
    )
    val dismiss by rememberUpdatedState(onDismiss)
    val bounds = remember { PopupBounds() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PlaceDialogWindow(anchorToRail)
        // Beside the rail the dialog's window fills the screen and the popup is placed in it, at
        // the bottom corner on the rail's side, just past the rail. The dialog itself centres what
        // it shows across its window's width and cannot be told otherwise, so placing the window
        // (its gravity) only ever moved the popup up and down. Elsewhere the dialog centres it.
        Box(
            modifier = if (anchorToRail) {
                Modifier
                    .fillMaxSize()
                    .onPlaced { bounds.window = it }
                    .pointerInput(bounds) { closeOnTapOutside(bounds) { dismiss() } }
                    // The visible popup starts 4dp past the rail; at the bottom, its shadow margin
                    // lines it up with the panes' bottom edge
                    .padding(start = (railWidth + PopupRailGap - PopupShadowMargin).coerceAtLeast(0.dp))
            } else {
                Modifier
            },
            // The popup grows and shrinks from this corner, so a later size keeps it in place
            contentAlignment = Alignment.BottomStart
        ) {
            Surface(
                modifier = Modifier
                    .padding(PopupShadowMargin)
                    .onPlaced { bounds.popup = it }
                    .width(width)
                    .graphicsLayer { this.alpha = alpha }
                    .testTag("extension_popup"),
                shape = MaterialTheme.shapes.large,
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 12.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .padding(start = 14.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExtensionIcon(icon = icon, size = 18.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = popup.extensionName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RemIconButton(
                            icon = Icons.Outlined.Close,
                            label = "Close ${popup.extensionName}",
                            onClick = onDismiss,
                            size = 32.dp,
                            iconSize = 18.dp
                        )
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .height(pageHeight)
                            .testTag("extension_popup_page")
                    ) {
                        popupContent(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Room around the popup for its shadow. */
private val PopupShadowMargin = 8.dp

/** Between the rail and the popup beside it. */
private val PopupRailGap = 4.dp

/** The popup's size before its content has reported one, or when it never does. */
private val PopupDefaultWidth = 420.dp
private val PopupDefaultPageHeight = 540.dp

/** Desktop Firefox's largest popup, and a floor so a mis-measured page stays usable. */
private val PopupMinWidth = 200.dp
private val PopupMaxWidth = 800.dp
private val PopupMinPageHeight = 160.dp
private val PopupMaxPageHeight = 600.dp

/** How long a popup stays hidden waiting for its content's size before it is shown anyway. */
internal const val POPUP_SIZE_WAIT_MS = 700L

/**
 * How long a reported size has to stand before the popup is shown at it. The popup helper
 * measures a page 100ms after it last changed, so a page that builds itself in steps reports
 * its next size sooner than this, and so does one that reflows once the popup takes its size
 * (Proton VPN reports 370 wide, then 420 at that width, 100ms apart).
 */
internal const val POPUP_SIZE_SETTLE_MS = 250L

/**
 * Whether [popup] is shown yet. It stays hidden until its content's size has settled, so it
 * appears once, at its own size, instead of growing (or jumping from the default size) on
 * screen: a size counts once no new one has followed it for [POPUP_SIZE_SETTLE_MS], or at once
 * when it is the size the popup already has ([atDefaultSize]: a popup made for Android reports
 * that), and after [POPUP_SIZE_WAIT_MS] the popup is shown whatever it has said. Once shown it
 * stays shown; later sizes resize it in place.
 */
@Composable
internal fun rememberPopupShown(popup: ExtensionPopupState, atDefaultSize: Boolean): Boolean {
    var shown by remember(popup.sessionId) { mutableStateOf(false) }
    LaunchedEffect(popup.sessionId) {
        delay(POPUP_SIZE_WAIT_MS)
        shown = true
    }
    LaunchedEffect(popup.sessionId, popup.contentWidth, popup.contentHeight) {
        if (popup.contentWidth == null || popup.contentHeight == null) return@LaunchedEffect
        if (!atDefaultSize) delay(POPUP_SIZE_SETTLE_MS)
        shown = true
    }
    return shown
}

/** Where the popup is in a dialog window that fills the screen beside the rail. */
private class PopupBounds {
    var window: LayoutCoordinates? = null
    var popup: LayoutCoordinates? = null

    /** Whether [position], in the window's coordinates, is on the popup. */
    operator fun contains(position: Offset): Boolean {
        val window = window?.takeIf { it.isAttached } ?: return false
        val popup = popup?.takeIf { it.isAttached } ?: return false
        return window.localBoundingBoxOf(popup, clipBounds = false).contains(position)
    }
}

/**
 * Closes the popup when a press outside it is let go. The dialog does that by itself only for
 * presses outside what it shows, which here fills the window.
 */
private suspend fun PointerInputScope.closeOnTapOutside(bounds: PopupBounds, close: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position in bounds) return@awaitEachGesture
        if (waitForUpOrCancellation() != null) close()
    }
}

/**
 * Sets up the dialog's window. Beside the rail it fills the screen (its content does) and
 * leaves the page behind undimmed; it has no animation of its own, which would move all of it,
 * so the popup only fades in where it stands. Otherwise the popup is centred over the dimmed
 * page and opens and closes as a dialog does.
 *
 * Both cases are set every time, because the same dialog can move from one to the other: the
 * activity handles rotation and resizing itself, so a popup opened on a wide window survives
 * the window becoming phone-sized.
 */
@Composable
private fun PlaceDialogWindow(anchorToRail: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    // The theme's dialog animation, to go back to
    val dialogAnimations = remember(window) { window?.attributes?.windowAnimations ?: 0 }
    DisposableEffect(window, anchorToRail) {
        if (window != null) {
            val params = window.attributes
            if (anchorToRail) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                params.windowAnimations = 0
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                params.windowAnimations = dialogAnimations
            }
            window.attributes = params
        }
        onDispose { }
    }
}
