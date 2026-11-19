package com.rembrowser.rembrowse.fakes

import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PersistedBrowserState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSessionHandle(
    override val tabId: String,
    initialUrl: String,
    initialDesktopMode: Boolean,
    private val listener: SessionEventListener
) : BrowserSessionHandle {
    val loadedUrls = mutableListOf(initialUrl)
    var desktopModeEnabled: Boolean = initialDesktopMode
    var visibleState: Boolean = false
    var focusedForExtensionsState: Boolean = false
    var reloadCount: Int = 0
    var stopCount: Int = 0
    var goBackCount: Int = 0
    var goForwardCount: Int = 0
    var isClosed: Boolean = false

    override fun loadUrl(url: String) {
        loadedUrls.add(url)
        listener.onSessionUpdated(tabId, SessionNavigationUpdate(url = url))
    }

    override fun reload() { reloadCount++ }
    override fun stop() { stopCount++ }
    override fun goBack() { goBackCount++ }
    override fun goForward() { goForwardCount++ }
    override fun setDesktopMode(enabled: Boolean) { desktopModeEnabled = enabled }
    override fun setVisible(visible: Boolean) { visibleState = visible }
    override fun setFocusedForExtensions(focused: Boolean) { focusedForExtensionsState = focused }
    override fun close() { isClosed = true }

    fun simulatePageLoad(url: String, title: String, canGoBack: Boolean = true) {
        listener.onSessionUpdated(
            tabId,
            SessionNavigationUpdate(
                url = url,
                title = title,
                isLoading = false,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = false
            )
        )
    }

    /** The user moved through this tab's history (back or forward) and landed on [url]. */
    fun simulateHistoryMove(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        listener.onSessionUpdated(
            tabId,
            SessionNavigationUpdate(url = url, isLoading = false, canGoBack = canGoBack, canGoForward = canGoForward)
        )
    }

    fun simulatePageStart(url: String) {
        listener.onSessionUpdated(
            tabId,
            SessionNavigationUpdate(url = url, isLoading = true, progress = 10, isSecure = false, isPageStart = true)
        )
    }

    fun simulateSecurityChange(isSecure: Boolean) {
        listener.onSessionUpdated(tabId, SessionNavigationUpdate(isSecure = isSecure))
    }

    fun simulateCrashLoop() {
        listener.onSessionUpdated(tabId, SessionNavigationUpdate(isLoading = false, crashed = true))
    }

    /** A page in this tab opened a new window (`window.open` / `target=_blank`). */
    fun simulateNewTab(url: String) {
        listener.onNewTabRequested(url, tabId)
    }

    fun simulatePrompt(prompt: com.rembrowser.rembrowse.model.PagePrompt) {
        listener.onPromptRequested(prompt)
    }

    fun simulatePromptWithdrawn(promptId: String) {
        listener.onPromptDismissed(promptId)
    }
}

class FakeBrowserEngine : BrowserEngine {
    val sessions = linkedMapOf<String, FakeSessionHandle>()
    var enhancedPrivacy: Boolean? = null

    override fun setEnhancedPrivacy(enabled: Boolean) {
        enhancedPrivacy = enabled
    }

    override fun createSession(
        tabId: String,
        initialUrl: String,
        desktopMode: Boolean,
        listener: SessionEventListener
    ): BrowserSessionHandle {
        val handle = FakeSessionHandle(tabId, initialUrl, desktopMode, listener)
        sessions[tabId] = handle
        return handle
    }

    override fun getSession(tabId: String): BrowserSessionHandle? = sessions[tabId]

    override fun closeSession(tabId: String) {
        sessions.remove(tabId)?.close()
    }

    override fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}

class FakeBrowserStateRepository(
    var storedState: PersistedBrowserState = PersistedBrowserState()
) : BrowserStateRepository {
    var saveCount = 0

    override fun loadState(): PersistedBrowserState = storedState

    override fun saveState(state: PersistedBrowserState) {
        saveCount++
        storedState = state
    }
}

class FakeDefaultExtensionStore(
    override var defaultBlockerProvisioned: Boolean = false
) : com.rembrowser.rembrowse.data.DefaultExtensionStore

class FakeAmoApiClient : AmoApiClient {
    var featuredResult: Result<List<AmoAddon>> = Result.success(emptyList())
    var searchResult: Result<List<AmoAddon>> = Result.success(emptyList())
    val searchedQueries = mutableListOf<String>()

    /** When set, the featured request suspends until this is completed. */
    var featuredGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun fetchFeaturedExtensions(pageSize: Int): Result<List<AmoAddon>> {
        featuredGate?.await()
        return featuredResult
    }

    override suspend fun searchExtensions(query: String, pageSize: Int): Result<List<AmoAddon>> {
        searchedQueries.add(query)
        return searchResult
    }
}

class FakeExtensionManager : ExtensionManager {
    override val installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val pendingPrompt = MutableStateFlow<ExtensionInstallPrompt?>(null)
    override val activePopup = MutableStateFlow<ExtensionPopupState?>(null)

    var nextInstallResult: Result<InstalledExtension>? = null

    /** When set, installs suspend until this is completed, to exercise in-flight behaviour. */
    var installGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val installedUris = mutableListOf<String>()
    val triggeredActionIds = mutableListOf<String>()
    var lastPromptDecision: Boolean? = null

    override suspend fun refreshInstalled(): Result<List<InstalledExtension>> {
        return Result.success(installedExtensions.value)
    }

    override suspend fun installFromUri(uri: String): Result<InstalledExtension> {
        installedUris.add(uri)
        installGate?.await()
        val result = nextInstallResult ?: Result.success(
            InstalledExtension(
                id = "ext-${installedUris.size}@rembrowse",
                name = "Installed Extension ${installedUris.size}",
                version = "1.0.0",
                description = "Installed from $uri",
                enabled = true,
                hasBrowserAction = true
            )
        )
        result.onSuccess { ext ->
            installedExtensions.value = installedExtensions.value.filterNot { it.id == ext.id } + ext
        }
        return result
    }

    /** uri to expectedId, for installs that skipped the permission review. */
    val trustedInstalls = mutableListOf<Pair<String, String>>()
    var nextTrustedInstallResult: Result<InstalledExtension>? = null

    override suspend fun installTrusted(uri: String, expectedId: String): Result<InstalledExtension> {
        trustedInstalls.add(uri to expectedId)
        val result = nextTrustedInstallResult ?: Result.success(
            InstalledExtension(
                id = expectedId,
                name = "AdGuard AdBlocker",
                version = "5.0.0",
                description = "Blocks ads and trackers",
                enabled = true,
                hasBrowserAction = true
            )
        )
        result.onSuccess { ext ->
            installedExtensions.value = installedExtensions.value.filterNot { it.id == ext.id } + ext
        }
        return result
    }

    override suspend fun setEnabled(extensionId: String, enabled: Boolean): Result<Unit> {
        installedExtensions.value = installedExtensions.value.map {
            if (it.id == extensionId) it.copy(enabled = enabled) else it
        }
        return Result.success(Unit)
    }

    override suspend fun uninstall(extensionId: String): Result<Unit> {
        installedExtensions.value = installedExtensions.value.filterNot { it.id == extensionId }
        return Result.success(Unit)
    }

    override fun triggerBrowserAction(extensionId: String) {
        triggeredActionIds.add(extensionId)
        val ext = installedExtensions.value.firstOrNull { it.id == extensionId }
        if (ext != null) {
            activePopup.value = ExtensionPopupState(ext.id, ext.name, "popup-${ext.id}")
        }
    }

    override fun dismissPopup() {
        activePopup.value = null
    }

    override fun dismissPrompt(allow: Boolean) {
        lastPromptDecision = allow
        pendingPrompt.value?.onDecision?.invoke(allow)
        pendingPrompt.value = null
    }
}
