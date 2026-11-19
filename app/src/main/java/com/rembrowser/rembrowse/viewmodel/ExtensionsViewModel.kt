package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.engine.ExtensionInstallCancelledException
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionsTab
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.util.UrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    // Only the newest catalog request may publish results; a slow earlier one is cancelled.
    private var catalogJob: Job? = null

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
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null, statusMessage = null) }
        if (tab == ExtensionsTab.DISCOVER_AMO && _uiState.value.catalogAddons.isEmpty() && !_uiState.value.isLoadingCatalog) {
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
        loadCatalog(fallbackError = "Couldn't reach addons.mozilla.org") {
            amoApiClient.fetchFeaturedExtensions()
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
        loadCatalog(fallbackError = "Extension search failed") {
            amoApiClient.searchExtensions(query)
        }
    }

    private fun loadCatalog(fallbackError: String, request: suspend () -> Result<List<AmoAddon>>) {
        catalogJob?.cancel()
        catalogJob = scope.launch {
            _uiState.update { it.copy(isLoadingCatalog = true, catalogError = null) }
            request()
                .onSuccess { addons ->
                    _uiState.update {
                        it.copy(catalogAddons = addons, isLoadingCatalog = false, catalogError = null)
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoadingCatalog = false, catalogError = err.message ?: fallbackError)
                    }
                }
        }
    }

    fun installAddon(addon: AmoAddon) {
        installFromUriInternal(addon.xpiUrl, displayLabel = addon.name, key = addon.id)
    }

    /**
     * @param onFinished runs once the install attempt is over, whatever the outcome. Used to
     *   delete the temporary copy of a locally picked `.xpi`.
     */
    fun installFromUri(uri: String, onFinished: (() -> Unit)? = null) {
        val trimmed = uri.trim()
        if (!UrlResolver.isValidExtensionUri(trimmed)) {
            _uiState.update {
                it.copy(
                    errorMessage = "Enter a secure https:// link to an .xpi file, or choose a file from this device.",
                    statusMessage = null
                )
            }
            onFinished?.invoke()
            return
        }
        installFromUriInternal(
            uri = trimmed,
            displayLabel = trimmed.substringAfterLast('/').ifBlank { "extension" },
            key = trimmed,
            onFinished = onFinished
        )
    }

    /** Surfaces a problem that happened outside the view model (for example reading a file). */
    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message, statusMessage = null) }
    }

    private fun installFromUriInternal(
        uri: String,
        displayLabel: String,
        key: String,
        onFinished: (() -> Unit)? = null
    ) {
        if (_uiState.value.isInstalling) {
            // One install at a time: a second request would race the first one's permission prompt.
            onFinished?.invoke()
            return
        }
        scope.launch {
            _uiState.update {
                it.copy(
                    isInstalling = true,
                    installingIdOrUrl = key,
                    errorMessage = null,
                    statusMessage = "Installing $displayLabel…"
                )
            }
            try {
                extensionManager.installFromUri(uri)
                    .onSuccess { installed ->
                        _uiState.update {
                            it.copy(
                                isInstalling = false,
                                installingIdOrUrl = null,
                                statusMessage = "Added ${installed.name} ${installed.version}",
                                errorMessage = null
                            )
                        }
                    }
                    .onFailure { err ->
                        // Backing out of the permission prompt is a choice, not a failure.
                        val cancelled = err is ExtensionInstallCancelledException
                        _uiState.update {
                            it.copy(
                                isInstalling = false,
                                installingIdOrUrl = null,
                                statusMessage = null,
                                errorMessage = if (cancelled) null else err.message ?: "Extension installation failed"
                            )
                        }
                    }
            } finally {
                onFinished?.invoke()
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
