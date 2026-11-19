package com.rembrowser.rembrowse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rembrowser.rembrowse.model.BrowserTab
import com.rembrowser.rembrowse.ui.components.BrowserTopBar
import com.rembrowser.rembrowse.ui.components.ExtensionInstallPromptDialog
import com.rembrowser.rembrowse.ui.components.ExtensionPopupDialog
import com.rembrowser.rembrowse.ui.components.ExtensionsManagerDialog
import com.rembrowser.rembrowse.ui.components.TabsAndBookmarksDialog
import com.rembrowser.rembrowse.ui.components.TiledBrowserGrid
import com.rembrowser.rembrowse.ui.theme.RemBackground
import com.rembrowser.rembrowse.viewmodel.BrowserViewModel
import com.rembrowser.rembrowse.viewmodel.ExtensionsViewModel

@Composable
fun RemBrowseScreen(
    browserViewModel: BrowserViewModel,
    extensionsViewModel: ExtensionsViewModel,
    addressBarFocusRequester: FocusRequester = remember { FocusRequester() },
    onPickLocalXpi: () -> Unit,
    paneViewport: @Composable (tab: BrowserTab, modifier: Modifier) -> Unit,
    extensionPopupContent: @Composable (Modifier) -> Unit
) {
    val browserState by browserViewModel.uiState.collectAsStateWithLifecycle()
    val extensionsState by extensionsViewModel.uiState.collectAsStateWithLifecycle()

    var showExtensionsDialog by remember { mutableStateOf(false) }
    var showTabsDrawer by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RemBackground)
            .safeDrawingPadding()
    ) {
        BrowserTopBar(
            uiState = browserState,
            installedExtensions = extensionsState.installedExtensions,
            addressBarFocusRequester = addressBarFocusRequester,
            onAddressBarChange = browserViewModel::onAddressBarChange,
            onAddressBarFocusChange = browserViewModel::onAddressBarFocusChange,
            onSubmitAddressBar = browserViewModel::submitAddressBar,
            onBack = { browserViewModel.goBack() },
            onForward = { browserViewModel.goForward() },
            onReloadOrStop = { browserViewModel.reloadOrStop() },
            onToggleDesktopMode = { browserViewModel.toggleDesktopMode() },
            onToggleBookmark = browserViewModel::toggleBookmarkForActiveTab,
            onSelectLayout = browserViewModel::setLayoutMode,
            onSelectTab = browserViewModel::selectTab,
            onCloseTab = browserViewModel::closeTab,
            onNewTab = { browserViewModel.openNewTab() },
            onOpenTabsDrawer = { showTabsDrawer = true },
            onOpenExtensions = {
                extensionsViewModel.refreshInstalled()
                showExtensionsDialog = true
            },
            onTriggerExtensionAction = extensionsViewModel::triggerBrowserAction
        )

        TiledBrowserGrid(
            uiState = browserState,
            onFocusPane = browserViewModel::focusPane,
            onUpdateHorizontalSplit = browserViewModel::updateHorizontalSplit,
            onUpdateVerticalSplit = browserViewModel::updateVerticalSplit,
            onResetSplitRatios = browserViewModel::resetSplitRatios,
            onBackPane = browserViewModel::goBack,
            onReloadPane = browserViewModel::reloadOrStop,
            onSwapPanes = browserViewModel::swapPanes,
            onMaximizePane = browserViewModel::maximizePane,
            onCloseTab = browserViewModel::closeTab,
            paneViewport = paneViewport,
            modifier = Modifier.weight(1f)
        )
    }

    if (showExtensionsDialog) {
        ExtensionsManagerDialog(
            uiState = extensionsState,
            onDismiss = { showExtensionsDialog = false },
            onSelectTab = extensionsViewModel::selectTab,
            onRefreshInstalled = extensionsViewModel::refreshInstalled,
            onSearchQueryChange = extensionsViewModel::onSearchQueryChange,
            onSubmitSearch = extensionsViewModel::submitSearch,
            onInstallAddon = extensionsViewModel::installAddon,
            onInstallUri = extensionsViewModel::installFromUri,
            onPickLocalXpi = onPickLocalXpi,
            onSetEnabled = extensionsViewModel::setExtensionEnabled,
            onUninstall = extensionsViewModel::uninstallExtension,
            onOpenAction = extensionsViewModel::triggerBrowserAction,
            onClearMessages = extensionsViewModel::clearMessages
        )
    }

    if (showTabsDrawer) {
        TabsAndBookmarksDialog(
            uiState = browserState,
            onDismiss = { showTabsDrawer = false },
            onAssignTabToPane = browserViewModel::assignTabToPane,
            onNewTab = { browserViewModel.openNewTab() },
            onCloseTab = browserViewModel::closeTab,
            onOpenBookmark = browserViewModel::openBookmarkInActivePane,
            onRemoveBookmark = browserViewModel::removeBookmark
        )
    }

    extensionsState.pendingPrompt?.let { prompt ->
        ExtensionInstallPromptDialog(
            prompt = prompt,
            onDecision = extensionsViewModel::respondToInstallPrompt
        )
    }

    extensionsState.activePopup?.let { popup ->
        ExtensionPopupDialog(
            popup = popup,
            onDismiss = extensionsViewModel::dismissPopup,
            popupContent = extensionPopupContent
        )
    }
}
