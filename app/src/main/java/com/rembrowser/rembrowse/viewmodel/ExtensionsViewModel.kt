package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionsTab
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.util.UrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExtensionsViewModel(
    private val extensionManager: ExtensionManager,
    private val amoApiClient: AmoApiClient,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow(ExtensionsUiState())
    val uiState: StateFlow<ExtensionsUiState> = _uiState.asStateFlow()

    init {
        extensionManager.installedExtensions
            .onEach { list ->
                _uiState.update { it.copy(installedExtensions = list) }
            }
            .launchIn(scope)

        extensionManager.pendingPrompt
            .onEach { prompt ->
                _uiState.update { it.copy(pendingPrompt = prompt) }
            }
            .launchIn(scope)

        extensionManager.activePopup
            .onEach { popup ->
                _uiState.update { it.copy(activePopup = popup) }
            }
            .launchIn(scope)

        refreshInstalled()
    }

    fun selectTab(tab: ExtensionsTab) {
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null) }
        if (tab == ExtensionsTab.DISCOVER_AMO && _uiState.value.catalogAddons.isEmpty()) {
            loadFeaturedAddons()
        } else if (tab == ExtensionsTab.INSTALLED) {
            refreshInstalled()
        }
    }

    fun refreshInstalled() {
        scope.launch {
            extensionManager.refreshInstalled()
                .onFailure { err ->
                    _uiState.update {
                        it.copy(errorMessage = err.message ?: "Unable to list installed extensions")
                    }
                }
        }
    }

    fun loadFeaturedAddons() {
        scope.launch {
            _uiState.update { it.copy(isLoadingCatalog = true, errorMessage = null) }
            amoApiClient.fetchFeaturedExtensions()
                .onSuccess { addons ->
                    _uiState.update {
                        it.copy(
                            catalogAddons = addons,
                            isLoadingCatalog = false
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingCatalog = false,
                            errorMessage = err.message ?: "Failed to reach addons.mozilla.org"
                        )
                    }
                }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun submitSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            loadFeaturedAddons()
            return
        }
        scope.launch {
            _uiState.update { it.copy(isLoadingCatalog = true, errorMessage = null) }
            amoApiClient.searchExtensions(query)
                .onSuccess { addons ->
                    _uiState.update {
                        it.copy(
                            catalogAddons = addons,
                            isLoadingCatalog = false
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingCatalog = false,
                            errorMessage = err.message ?: "Extension search failed"
                        )
                    }
                }
        }
    }

    fun installAddon(addon: AmoAddon) {
        installFromUriInternal(addon.xpiUrl, displayLabel = addon.name, key = addon.id)
    }

    fun installFromUri(uri: String) {
        val trimmed = uri.trim()
        if (!UrlResolver.isValidExtensionUri(trimmed)) {
            _uiState.update {
                it.copy(
                    errorMessage = "Enter a valid https://, http://, or file:// .xpi extension URI.",
                    statusMessage = null
                )
            }
            return
        }
        installFromUriInternal(trimmed, displayLabel = trimmed.substringAfterLast('/'), key = trimmed)
    }

    private fun installFromUriInternal(uri: String, displayLabel: String, key: String) {
        scope.launch {
            _uiState.update {
                it.copy(
                    isInstalling = true,
                    installingIdOrUrl = key,
                    errorMessage = null,
                    statusMessage = "Installing $displayLabel..."
                )
            }
            extensionManager.installFromUri(uri)
                .onSuccess { installed ->
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingIdOrUrl = null,
                            statusMessage = "Installed ${installed.name} (${installed.version})",
                            errorMessage = null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingIdOrUrl = null,
                            statusMessage = null,
                            errorMessage = err.message ?: "Extension installation failed"
                        )
                    }
                }
        }
    }

    fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        scope.launch {
            extensionManager.setEnabled(extensionId, enabled)
                .onFailure { err ->
                    _uiState.update {
                        it.copy(errorMessage = err.message ?: "Failed to update extension state")
                    }
                }
        }
    }

    fun uninstallExtension(extensionId: String) {
        scope.launch {
            extensionManager.uninstall(extensionId)
                .onSuccess {
                    _uiState.update { it.copy(statusMessage = "Extension removed", errorMessage = null) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(errorMessage = err.message ?: "Failed to uninstall extension")
                    }
                }
        }
    }

    fun triggerBrowserAction(extensionId: String) {
        extensionManager.triggerBrowserAction(extensionId)
    }

    fun respondToInstallPrompt(allow: Boolean) {
        extensionManager.dismissPrompt(allow)
    }

    fun dismissPopup() {
        extensionManager.dismissPopup()
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
