package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PagePrompt
import kotlinx.coroutines.flow.StateFlow

data class SessionNavigationUpdate(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean? = null,
    val progress: Int? = null,
    val canGoBack: Boolean? = null,
    val canGoForward: Boolean? = null,
    val isSecure: Boolean? = null,
    val crashed: Boolean? = null,
    /** A new document started loading (as opposed to an in-page navigation). */
    val isPageStart: Boolean = false
)

interface SessionEventListener {
    fun onSessionUpdated(tabId: String, update: SessionNavigationUpdate)

    /**
     * Content (or an extension) asked for a new tab. The listener must create the tab's session
     * with [BrowserEngine.createSession] before returning, so the engine can hand that session
     * back to the page that opened it and keep `window.opener` working.
     */
    fun onNewTabRequested(url: String, openerTabId: String?)

    fun onPromptRequested(prompt: PagePrompt) {}

    /** The page withdrew a prompt (for example by navigating away) before the user answered. */
    fun onPromptDismissed(promptId: String)

    /**
     * A page offered a file to save. The receiver owns [DownloadRequest.body] from here on and
     * must close it, whether or not the file is kept.
     */
    fun onDownloadRequested(request: DownloadRequest) {}

    /** The page in [tabId] asked to fill the screen (a video's fullscreen button), or left it. */
    fun onFullScreenChanged(tabId: String, fullScreen: Boolean) {}

    /** The page in [tabId] started or stopped using the camera or microphone (a call, a recording). */
    fun onRecordingChanged(tabId: String, recording: Boolean) {}
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

    /** Takes the page out of fullscreen, as its own exit button or Esc would. */
    fun exitFullScreen() {}
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

    /**
     * Standard tracking protection is always on. Enhanced additionally blocks known
     * fingerprinters, cryptominers and email trackers.
     */
    fun setEnhancedPrivacy(enabled: Boolean) {}
}

/** The user backed out of the permission prompt; not an error worth reporting. */
class ExtensionInstallCancelledException : Exception("Extension installation was cancelled")

interface ExtensionManager {
    val installedExtensions: StateFlow<List<InstalledExtension>>
    val pendingPrompt: StateFlow<ExtensionInstallPrompt?>
    val activePopup: StateFlow<ExtensionPopupState?>

    suspend fun refreshInstalled(): Result<List<InstalledExtension>>
    suspend fun installFromUri(uri: String): Result<InstalledExtension>

    /**
     * Installs one of the browser's own default extensions without showing the permission
     * review. The review is skipped only if the package really has the id [expectedId].
     */
    suspend fun installTrusted(uri: String, expectedId: String): Result<InstalledExtension>
    suspend fun setEnabled(extensionId: String, enabled: Boolean): Result<Unit>
    suspend fun uninstall(extensionId: String): Result<Unit>
    fun triggerBrowserAction(extensionId: String)
    fun dismissPopup()
    fun dismissPrompt(allow: Boolean)
}
