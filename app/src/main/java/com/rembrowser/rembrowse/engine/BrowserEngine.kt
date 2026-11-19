package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import kotlinx.coroutines.flow.StateFlow

data class SessionNavigationUpdate(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean? = null,
    val progress: Int? = null,
    val canGoBack: Boolean? = null,
    val canGoForward: Boolean? = null
)

interface SessionEventListener {
    fun onSessionUpdated(tabId: String, update: SessionNavigationUpdate)
    fun onNewTabRequested(url: String)
}

interface BrowserSessionHandle {
    val tabId: String
    fun loadUrl(url: String)
    fun reload()
    fun stop()
    fun goBack()
    fun goForward()
    fun setDesktopMode(enabled: Boolean)
    fun setVisible(visible: Boolean)
    fun setFocusedForExtensions(focused: Boolean)
    fun close()
}

interface BrowserEngine {
    fun createSession(
        tabId: String,
        initialUrl: String,
        desktopMode: Boolean,
        listener: SessionEventListener
    ): BrowserSessionHandle

    fun getSession(tabId: String): BrowserSessionHandle?
    fun closeSession(tabId: String)
    fun closeAll()
}

interface ExtensionManager {
    val installedExtensions: StateFlow<List<InstalledExtension>>
    val pendingPrompt: StateFlow<ExtensionInstallPrompt?>
    val activePopup: StateFlow<ExtensionPopupState?>

    suspend fun refreshInstalled(): Result<List<InstalledExtension>>
    suspend fun installFromUri(uri: String): Result<InstalledExtension>
    suspend fun setEnabled(extensionId: String, enabled: Boolean): Result<Unit>
    suspend fun uninstall(extensionId: String): Result<Unit>
    fun triggerBrowserAction(extensionId: String)
    fun dismissPopup()
    fun dismissPrompt(allow: Boolean)
}
