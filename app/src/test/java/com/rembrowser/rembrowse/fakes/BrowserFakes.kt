package com.rembrowser.rembrowse.fakes

import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.FindResult
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
    var exitFullScreenCount: Int = 0

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
    override fun exitFullScreen() { exitFullScreenCount++ }

    /** Every find in page asked of this page: (query, backwards, matchCase). */
    val finds = mutableListOf<Triple<String, Boolean, Boolean>>()
    var findClearCount = 0
    private var pendingFind: ((FindResult) -> Unit)? = null

    override fun findInPage(query: String, backwards: Boolean, matchCase: Boolean, onResult: (FindResult) -> Unit) {
        finds += Triple(query, backwards, matchCase)
        pendingFind = onResult
    }

    override fun clearFindInPage() { findClearCount++ }

    /** The engine answers the latest find. */
    fun answerFind(result: FindResult) {
        pendingFind?.invoke(result)
    }

    /** The page pressed (or left) its fullscreen button. */
    fun simulateFullScreen(fullScreen: Boolean) {
        listener.onFullScreenChanged(tabId, fullScreen)
    }

    /** The page started or stopped using the camera or microphone. */
    fun simulateRecording(recording: Boolean) {
        listener.onRecordingChanged(tabId, recording)
    }

    /** A right click on this page that the page left to the browser: on a link, a picture, or neither. */
    fun simulateContextMenu(
        linkUrl: String? = null,
        linkText: String? = null,
        mediaUrl: String? = null,
        mediaType: com.rembrowser.rembrowse.model.PageMediaType = com.rembrowser.rembrowse.model.PageMediaType.NONE,
        serial: Long = 1
    ) {
        listener.onContextMenuRequested(
            com.rembrowser.rembrowse.model.PageContextMenu(
                tabId = tabId,
                linkUrl = linkUrl,
                linkText = linkText,
                mediaUrl = mediaUrl,
                mediaType = mediaType,
                x = 120f,
                y = 80f,
                serial = serial
            )
        )
    }

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

    /** The page in this tab offered a file the engine cannot display. */
    fun simulateDownload(
        url: String,
        fileName: String,
        bytes: ByteArray = ByteArray(0),
        mimeType: String = "application/octet-stream",
        skipConfirmation: Boolean = false
    ): com.rembrowser.rembrowse.model.DownloadRequest {
        val request = com.rembrowser.rembrowse.model.DownloadRequest(
            tabId = tabId,
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            contentLength = bytes.size.toLong(),
            skipConfirmation = skipConfirmation,
            body = TrackedInputStream(bytes)
        )
        listener.onDownloadRequested(request)
        return request
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

/** A response body that records whether it was read and closed, and can fail or call back. */
class TrackedInputStream(
    private val data: ByteArray,
    /** Fail once this many bytes have been handed out; negative for never. */
    private val failAfterBytes: Int = -1,
    /** How many bytes one read may return, to force a download into several steps. */
    private val chunkSize: Int = Int.MAX_VALUE,
    /** Called before each read with the number of bytes handed out so far. */
    private val beforeRead: (Int) -> Unit = {}
) : java.io.InputStream() {
    var position = 0
        private set
    var isClosed = false
        private set
    val wasRead: Boolean
        get() = position > 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        beforeRead(position)
        if (isClosed) throw java.io.IOException("Stream is closed")
        if (failAfterBytes in 0..position) throw java.io.IOException("Connection lost")
        if (position >= data.size) return -1
        var count = minOf(length, chunkSize, data.size - position)
        if (failAfterBytes >= 0) count = minOf(count, failAfterBytes - position)
        System.arraycopy(data, position, buffer, offset, count)
        position += count
        return count
    }

    override fun close() {
        isClosed = true
    }
}

class FakeDownloadStorage : com.rembrowser.rembrowse.data.DownloadStorage {
    override val locationLabel = "The Downloads folder on this device"
    override val isSharedFolder = true

    /** Finished files by name. */
    val saved = linkedMapOf<String, ByteArray>()
    val discarded = mutableListOf<String>()
    val missingUris = mutableSetOf<String>()
    var failOnCreate = false
    private val inFlight = mutableSetOf<String>()

    fun uriFor(fileName: String) = "content://downloads/$fileName"

    override fun create(fileName: String, mimeType: String): com.rembrowser.rembrowse.data.DownloadTarget {
        if (failOnCreate) throw java.io.IOException("No space left")
        val name = com.rembrowser.rembrowse.util.DownloadFileNames.unique(fileName) { it in saved || it in inFlight }
        inFlight += name
        val buffer = java.io.ByteArrayOutputStream()
        return object : com.rembrowser.rembrowse.data.DownloadTarget {
            override val fileName = name
            override fun openOutput(): java.io.OutputStream = buffer
            override fun commit(): String {
                inFlight -= name
                saved[name] = buffer.toByteArray()
                return uriFor(name)
            }
            override fun discard() {
                inFlight -= name
                discarded += name
            }
        }
    }

    override fun exists(contentUri: String): Boolean = contentUri !in missingUris
}

class FakeDownloadHistoryStore(
    var stored: List<com.rembrowser.rembrowse.model.DownloadItem> = emptyList()
) : com.rembrowser.rembrowse.data.DownloadHistoryStore {
    var saveCount = 0
    override fun load() = stored
    override fun save(items: List<com.rembrowser.rembrowse.model.DownloadItem>) {
        stored = items
        saveCount++
    }
}
