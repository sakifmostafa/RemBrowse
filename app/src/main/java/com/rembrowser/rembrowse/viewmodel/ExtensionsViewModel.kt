package com.rembrowser.rembrowse.viewmodel

import com.rembrowser.rembrowse.data.AddonUpdatePreferences
import com.rembrowser.rembrowse.data.AlreadyProvisionedDefaults
import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.data.DefaultExtensionStore
import com.rembrowser.rembrowse.data.InMemoryAddonUpdatePreferences
import com.rembrowser.rembrowse.engine.ExtensionInstallCancelledException
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ArchivedAdBlocker
import com.rembrowser.rembrowse.model.DefaultAdBlocker
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionsTab
import com.rembrowser.rembrowse.model.ExtensionsUiState
import com.rembrowser.rembrowse.model.InstalledExtension
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
    private val scope: CoroutineScope,
    private val defaults: DefaultExtensionStore = AlreadyProvisionedDefaults,
    addonUpdatePreferences: AddonUpdatePreferences = InMemoryAddonUpdatePreferences(),
    /** Whether the device has a network connection, for the add-on update check. */
    isOnline: () -> Boolean = { true }
) {
    private val _uiState = MutableStateFlow(ExtensionsUiState())
    val uiState: StateFlow<ExtensionsUiState> = _uiState.asStateFlow()

    /** Keeps installed add-ons up to date; its schedule runs from [startAddonUpdates]. */
    private val addonUpdater = AddonUpdater(extensionManager, addonUpdatePreferences, scope, isOnline)

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

        addonUpdater.uiState
            .onEach { updates ->
                _uiState.update { it.copy(addonUpdates = updates) }
            }
            .launchIn(scope)

        scope.launch {
            val installed = extensionManager.refreshInstalled()
                .onFailure { err ->
                    _uiState.update {
                        it.copy(errorMessage = err.message ?: "Unable to list installed extensions")
                    }
                }
                .getOrNull()
            // The refreshed list itself: its copy in uiState only arrives a moment later
            provisionDefaultAdBlocker(installed)
        }
    }

    /**
     * First launch only: installs the default ad blocker. Once that has succeeded it is never
     * repeated, so removing the blocker sticks. A failure (offline, for instance) is silent and
     * simply tried again on the next launch. A copy set up before uBlock Origin became the
     * default moves to it once (see [moveToUblock]).
     */
    private fun provisionDefaultAdBlocker(installed: List<InstalledExtension>?) {
        if (defaults.defaultBlockerProvisioned) {
            // Without the list, AdGuard's state is unknown: try again next launch
            if (!defaults.blockerMovedToUblock && installed != null) moveToUblock(installed)
            return
        }
        if (installed?.any { it.id == DefaultAdBlocker.ID } == true) {
            defaults.defaultBlockerProvisioned = true
            defaults.blockerMovedToUblock = true
            return
        }
        installDefaultAdBlocker(userInitiated = false)
    }

    /**
     * Once, for a copy set up with AdGuard AdBlocker as its blocker. With AdGuard switched on,
     * uBlock Origin is installed and AdGuard is then switched off, not removed: archived, with
     * its settings, for anyone who wants it back. With AdGuard switched off or removed, the user
     * had chosen no blocker and nothing is installed. A failed download is tried again on the
     * next launch, and AdGuard stays on until uBlock Origin is in place.
     */
    private fun moveToUblock(installed: List<InstalledExtension>) {
        val uBlock = installed.firstOrNull { it.id == DefaultAdBlocker.ID }
        val adGuard = installed.firstOrNull { it.id == ArchivedAdBlocker.ID }
        when {
            uBlock != null -> scope.launch {
                // Both on: AdGuard steps down. The move counts once it is actually off.
                if (adGuard?.enabled == true && uBlock.enabled && !archiveAdGuard()) return@launch
                defaults.blockerMovedToUblock = true
            }
            adGuard?.enabled != true -> defaults.blockerMovedToUblock = true
            else -> installDefaultAdBlocker(userInitiated = false)
        }
    }

    /** Switches AdGuard AdBlocker off, keeping it installed with its settings. True once it is off. */
    private suspend fun archiveAdGuard(): Boolean =
        extensionManager.setEnabled(ArchivedAdBlocker.ID, false).isSuccess

    private fun installDefaultAdBlocker(userInitiated: Boolean) {
        if (_uiState.value.isInstallingAdBlocker) return
        _uiState.update { it.copy(isInstallingAdBlocker = true, adBlockerError = null) }
        scope.launch {
            // Prefer the catalog's Android-compatible package; fall back to the stable link.
            val url = amoApiClient.searchExtensions(DefaultAdBlocker.NAME).getOrNull()
                ?.firstOrNull { it.id == DefaultAdBlocker.ID }
                ?.xpiUrl
                ?: DefaultAdBlocker.FALLBACK_URL
            val result = extensionManager.installTrusted(url, DefaultAdBlocker.ID)
            if (result.isSuccess) {
                defaults.defaultBlockerProvisioned = true
                // Until the move is done AdGuard may still be on, however uBlock Origin got here
                // (first launch, the move, or the Settings switch): two blockers would filter every
                // page twice. AdGuard steps down first; the move counts once it is off, and is
                // otherwise finished on the next launch.
                if (!defaults.blockerMovedToUblock) {
                    // A list read now, so a failed refresh can't pass for "AdGuard is off"
                    val current = extensionManager.refreshInstalled().getOrNull()
                    if (current != null) {
                        val adGuardOn = current.any { it.id == ArchivedAdBlocker.ID && it.enabled }
                        if (!adGuardOn || archiveAdGuard()) defaults.blockerMovedToUblock = true
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isInstallingAdBlocker = false,
                    adBlockerError = if (result.isFailure && userInitiated) {
                        "Couldn't download the ad blocker. Check your connection and try again."
                    } else {
                        null
                    }
                )
            }
        }
    }

    /** The "Block ads and trackers" switch: enables/disables the blocker, or installs it again. */
    fun setAdBlockerEnabled(enabled: Boolean) {
        val state = _uiState.value
        when {
            state.adBlocker != null -> setExtensionEnabled(DefaultAdBlocker.ID, enabled)
            enabled -> installDefaultAdBlocker(userInitiated = true)
        }
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

    /** The answer given on [prompt]'s own dialog. */
    fun respondToInstallPrompt(prompt: ExtensionInstallPrompt, allow: Boolean, shareTechnicalData: Boolean = false) {
        extensionManager.dismissPrompt(prompt, allow, shareTechnicalData)
    }

    fun dismissPopup() {
        extensionManager.dismissPopup()
    }

    /** Starts the daily add-on update check, for as long as [scope] lasts. */
    fun startAddonUpdates() {
        addonUpdater.start()
    }

    /** "Check for updates" in Settings → Extensions. */
    fun checkForAddonUpdates() {
        addonUpdater.checkNow()
    }

    /** "Update add-ons automatically" in Settings → Extensions. */
    fun setUpdateAddonsAutomatically(enabled: Boolean) {
        addonUpdater.setUpdateAutomatically(enabled)
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
