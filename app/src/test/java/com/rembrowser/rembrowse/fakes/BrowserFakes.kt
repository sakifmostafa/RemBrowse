package com.rembrowser.rembrowse.fakes

import com.rembrowser.rembrowse.data.AmoApiClient
import com.rembrowser.rembrowse.data.BrowserStateRepository
import com.rembrowser.rembrowse.engine.BrowserEngine
import com.rembrowser.rembrowse.engine.BrowserSessionHandle
import com.rembrowser.rembrowse.engine.FindResult
import com.rembrowser.rembrowse.engine.ExtensionManager
import com.rembrowser.rembrowse.engine.SessionEventListener
import com.rembrowser.rembrowse.engine.SessionNavigationUpdate
import com.rembrowser.rembrowse.engine.SitePermissionAsk
import com.rembrowser.rembrowse.model.AmoAddon
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.PersistedBrowserState
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import com.rembrowser.rembrowse.model.SiteOrigins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSessionHandle(
    override val tabId: String,
    initialUrl: String,
    initialDesktopMode: Boolean,
    val listener: SessionEventListener
) : BrowserSessionHandle {
    val loadedUrls = mutableListOf(initialUrl)

    /** The address the page last committed to (a load or a move within it), not a page start. */
    var committedUrl: String = initialUrl
        private set
    var desktopModeEnabled: Boolean = initialDesktopMode
    var visibleState: Boolean = false
    var focusedForExtensionsState: Boolean = false
    var reloadCount: Int = 0
    var stopCount: Int = 0
    var goBackCount: Int = 0
    var goForwardCount: Int = 0
    var isClosed: Boolean = false
    var exitFullScreenCount: Int = 0
    var printCount: Int = 0

    override fun loadUrl(url: String) {
        loadedUrls.add(url)
        committedUrl = url
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
    override fun print() { printCount++ }

    /** How many times Back was offered to the page. */
    var handleBackCount = 0

    /** True when the page has something of its own open that Back closes (a dialog or popover). */
    var pageTakesBack = false

    /**
     * True to keep Back offered to the page waiting for [answerBack], as the real engine's answer
     * comes later; otherwise the page answers at once, with [pageTakesBack].
     */
    var answersBackLater = false
    private val unansweredBacks = ArrayDeque<Pair<() -> Unit, () -> Unit>>()

    /** Back offered to the page and not answered yet. */
    val unansweredBackCount: Int get() = unansweredBacks.size

    override fun handleBack(onHandled: () -> Unit, onUnhandled: () -> Unit) {
        handleBackCount++
        when {
            answersBackLater -> unansweredBacks.addLast(onHandled to onUnhandled)
            pageTakesBack -> onHandled()
            else -> onUnhandled()
        }
    }

    /**
     * The page answers the oldest Back still waiting: [handled] when it closed something of its
     * own. An engine error answers as not handled.
     */
    fun answerBack(handled: Boolean) {
        val (onHandled, onUnhandled) = unansweredBacks.removeFirst()
        if (handled) onHandled() else onUnhandled()
    }

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

    /**
     * The page now captures [devices] (CAMERA and/or MICROPHONE; none when it stopped), reported
     * as the real engine does: whether it records at all, then which devices, with the origin of
     * the page committed in this tab (by default the last load, not a page start).
     */
    fun simulateCaptureDevices(
        devices: Set<SitePermissionKind>,
        pageOrigin: String? = SiteOrigins.originOf(committedUrl)
    ) {
        listener.onRecordingChanged(tabId, devices.isNotEmpty())
        listener.onCaptureDevicesChanged(tabId, devices, pageOrigin)
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
        committedUrl = url
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
        committedUrl = url
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
    /** The page called window.close(). */
    fun simulateCloseRequest() {
        listener.onCloseRequested(tabId)
    }

    /** A Ctrl+click or middle click on a link, reported for [fromTabId] (this tab unless told otherwise). */
    fun simulateOpenLink(url: String, inForeground: Boolean = false, fromTabId: String = tabId) {
        listener.onOpenLinkRequested(fromTabId, url, inForeground)
    }

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

    /** The engine finished laying this tab's page out as a PDF for printing. */
    fun simulatePrintReady(
        pdfPath: String,
        documentName: String = "Example",
        forTabId: String = tabId,
        requestedByPage: Boolean = false
    ) {
        listener.onPrintReady(forTabId, pdfPath, documentName, requestedByPage)
    }

    fun simulatePrompt(prompt: com.rembrowser.rembrowse.model.PagePrompt) {
        listener.onPromptRequested(prompt)
    }

    fun simulatePromptWithdrawn(promptId: String) {
        listener.onPromptDismissed(promptId)
    }
}

/** A site permission request made with [FakeBrowserEngine.simulatePermissionRequest], and how it was answered. */
class FakePermissionAsk internal constructor() {
    lateinit var ask: SitePermissionAsk
        internal set

    val id: String
        get() = ask.id

    /** The answer the engine received, or null while the request waits. */
    var answer: PermissionAnswer? = null
        internal set

    val isAnswered: Boolean
        get() = answer != null

    /** Whether the engine itself withdrew the request (its page or tab went away). */
    var withdrawn: Boolean = false
        internal set
}

/** One call to [FakeBrowserEngine.setSitePermission]. */
data class SitePermissionChange(
    val origin: String,
    val kind: SitePermissionKind,
    val state: SitePermissionState?,
    val embeddedOrigin: String?
)

class FakeBrowserEngine : BrowserEngine {
    val sessions = linkedMapOf<String, FakeSessionHandle>()
    var enhancedPrivacy: Boolean? = null

    override fun setEnhancedPrivacy(enabled: Boolean) {
        enhancedPrivacy = enabled
    }

    /**
     * The site permission decisions this engine remembers, as the real one keeps them in its
     * stores. Tests may seed it; answers and setSitePermission calls change it as they would there.
     */
    val storedPermissions = mutableListOf<SitePermissionEntry>()

    /** Every setSitePermission call, in order. */
    val sitePermissionChanges = mutableListOf<SitePermissionChange>()

    /** Every origin clearSitePermissions was called for, in order. */
    val clearedPermissionOrigins = mutableListOf<String>()

    /** Every request simulated so far, oldest first. */
    val permissionAsks = mutableListOf<FakePermissionAsk>()

    /** The requests still waiting for an answer. */
    val pendingPermissionAsks: List<FakePermissionAsk>
        get() = permissionAsks.filterNot { it.isAnswered }

    private var permissionAskSerial = 0

    /**
     * The page in [tabId] asks for [kinds] on [origin]. [stored] is what the engine reports as
     * already decided; by default it is read from [storedPermissions], as the real engine reads
     * its stores. Answers are recorded on the returned handle, and ALLOW and BLOCK are remembered
     * in [storedPermissions] for the kinds not already decided, as the real engine does.
     */
    fun simulatePermissionRequest(
        tabId: String,
        origin: String,
        kinds: Set<SitePermissionKind>,
        embeddedOrigin: String? = null,
        stored: Map<SitePermissionKind, SitePermissionState>? = null
    ): FakePermissionAsk {
        val session = sessions[tabId] ?: error("No session for tab $tabId")
        val remembered = stored ?: storedPermissions
            .filter { it.origin == origin && it.kind in kinds && it.embeddedOrigin == embeddedOrigin }
            .associate { it.kind to it.state }
        val fake = FakePermissionAsk()
        fake.ask = SitePermissionAsk(
            id = "perm-${++permissionAskSerial}",
            tabId = tabId,
            origin = origin,
            kinds = kinds,
            embeddedOrigin = embeddedOrigin,
            stored = remembered
        ) { answer ->
            fake.answer = answer
            val state = when (answer) {
                PermissionAnswer.ALLOW -> SitePermissionState.ALLOW
                PermissionAnswer.BLOCK -> SitePermissionState.BLOCK
                else -> null
            }
            if (state != null) (kinds - remembered.keys).forEach { remember(origin, it, state, embeddedOrigin) }
        }
        permissionAsks += fake
        session.listener.onPermissionRequested(fake.ask)
        return fake
    }

    /**
     * The engine's remembered decisions changed other than as answered (an Allow Android refused
     * the app the device for). The fake changes [storedPermissions] as [change] says first.
     */
    fun simulateSitePermissionsChanged(change: MutableList<SitePermissionEntry>.() -> Unit = {}) {
        storedPermissions.change()
        sessions.values.firstOrNull()?.listener?.onSitePermissionsChanged()
    }

    /** The engine withdraws [fake] itself, as the real one does when its page goes: DISMISS, then a notice. */
    fun simulatePermissionWithdrawn(fake: FakePermissionAsk) {
        if (fake.isAnswered) return
        fake.withdrawn = true
        fake.ask.answer(PermissionAnswer.DISMISS)
        sessions[fake.ask.tabId]?.listener?.onPermissionRequestWithdrawn(fake.id)
    }

    override fun loadSitePermissions(onResult: (List<SitePermissionEntry>) -> Unit) {
        onResult(storedPermissions.toList())
    }

    override fun setSitePermission(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState?,
        embeddedOrigin: String?
    ) {
        sitePermissionChanges += SitePermissionChange(origin, kind, state, embeddedOrigin)
        if (state == null || (kind == SitePermissionKind.STORAGE_ACCESS && embeddedOrigin == null)) {
            val matching = storedPermissions.filter {
                it.origin == origin && it.kind == kind && (embeddedOrigin == null || it.embeddedOrigin == embeddedOrigin)
            }
            storedPermissions.removeAll(matching)
            if (state != null) storedPermissions += matching.map { it.copy(state = state) }
        } else {
            remember(origin, kind, state, embeddedOrigin)
        }
    }

    override fun clearSitePermissions(origin: String) {
        clearedPermissionOrigins += origin
        storedPermissions.removeAll { it.origin == origin }
    }

    private fun remember(origin: String, kind: SitePermissionKind, state: SitePermissionState, embeddedOrigin: String?) {
        storedPermissions.removeAll { it.origin == origin && it.kind == kind && it.embeddedOrigin == embeddedOrigin }
        storedPermissions += SitePermissionEntry(origin, SiteOrigins.displayHost(origin), kind, state, embeddedOrigin)
    }

    /** As the real engine does, a session that goes withdraws the requests its page made. */
    private fun withdrawPermissionAsks(tabId: String) {
        pendingPermissionAsks.filter { it.ask.tabId == tabId }.forEach(::simulatePermissionWithdrawn)
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
        withdrawPermissionAsks(tabId)
        sessions.remove(tabId)?.close()
    }

    override fun closeAll() {
        sessions.keys.toList().forEach(::withdrawPermissionAsks)
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
    override var defaultBlockerProvisioned: Boolean = false,
    override var blockerMovedToUblock: Boolean = false
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
    var lastTechnicalDataDecision: Boolean? = null

    /**
     * When set, what refreshInstalled returns, without touching [installedExtensions] first: on a
     * device the state flow's copy arrives a moment after the result.
     */
    var refreshResult: Result<List<InstalledExtension>>? = null

    /** When set, setEnabled fails with this for the extension id, leaving it as it was. */
    var setEnabledFailureFor = mutableSetOf<String>()

    override suspend fun refreshInstalled(): Result<List<InstalledExtension>> {
        return refreshResult ?: Result.success(installedExtensions.value)
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
                name = "uBlock Origin",
                version = "1.61.2",
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
        if (extensionId in setEnabledFailureFor) return Result.failure(java.io.IOException("cannot switch $extensionId"))
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

    override fun dismissPrompt(prompt: ExtensionInstallPrompt, allow: Boolean, shareTechnicalData: Boolean) {
        if (pendingPrompt.value !== prompt) return
        lastPromptDecision = allow
        lastTechnicalDataDecision = shareTechnicalData
        prompt.onDecision(allow, shareTechnicalData)
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
