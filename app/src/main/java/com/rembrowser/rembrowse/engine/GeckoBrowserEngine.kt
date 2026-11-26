package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.MimeTypeMap
import com.rembrowser.rembrowse.BuildConfig
import com.rembrowser.rembrowse.data.ClipboardImages
import com.rembrowser.rembrowse.data.PageImageClipboard
import com.rembrowser.rembrowse.data.SharedPrefsSitePermissionStore
import com.rembrowser.rembrowse.data.SitePermissionStore
import com.rembrowser.rembrowse.data.UploadCache
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.model.PageContextMenu
import com.rembrowser.rembrowse.model.PageMediaType
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PromptOption
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SiteIcons
import com.rembrowser.rembrowse.model.SitePermissionState
import com.rembrowser.rembrowse.util.ErrorPages
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GeckoBrowserEngine"

/** How long Gecko gets to open a session it asked us to create before we open it ourselves. */
private const val ADOPTION_TIMEOUT_MS = 2_000L
private const val ADOPTION_POLL_MS = 50L

/** A second crash this soon after a restore is treated as a crash loop and left for the user. */
private const val CRASH_LOOP_WINDOW_MS = 10_000L

/**
 * RemBrowse's page menu helper, shipped in the APK's assets: a content script that reports a
 * right click a page left to the browser on its background or text, where GeckoView itself
 * reports nothing, and a Ctrl+click or middle click on a link, which GeckoView ignores; and that
 * hands over a picture a page copies with `navigator.clipboard.write`, which GeckoView can't put
 * on Android's clipboard; and that reports the icons a page names for itself, which GeckoView
 * doesn't (see assets/extensions/page_menu/pageMenu.js). Its manifest version must
 * go up whenever the script changes: `ensureBuiltIn` keeps an installed copy of the same version.
 */
internal const val PAGE_MENU_EXTENSION_ID = "page-menu@rembrowse"
internal const val PAGE_MENU_EXTENSION_URI = "resource://android/assets/extensions/page_menu/"

/** The name the page menu helper sends its messages to. */
internal const val PAGE_MENU_NATIVE_APP = "rembrowse_page_menu"

/** A link the page menu helper asks to open in a new tab; see [GeckoBrowserEngine.linkOpenRequest]. */
internal data class LinkOpenRequest(val url: String, val inForeground: Boolean)

/**
 * Offers Back to a page with [processBackPressed] (GeckoSession's, which runs the page's
 * CloseWatchers: an open `<dialog>` or popover closes) and calls, on the main thread, [onHandled]
 * when the page took it, or [onUnhandled] when it had nothing to close or the engine failed.
 *
 * The engine answers "taken" as soon as it has passed Back on, before the page has closed
 * anything, and until the page has, it would answer "taken" again. So a taken Back is reported
 * only once [reachPage], a question answered by the page itself and so after it got Back, comes
 * back (or fails): a second Back then finds the page as it now is, and goes back.
 */
internal fun offerBackToPage(
    processBackPressed: () -> GeckoResult<Boolean>?,
    reachPage: () -> GeckoResult<*>?,
    onHandled: () -> Unit,
    onUnhandled: () -> Unit
) {
    val answer = try {
        processBackPressed()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Back could not be offered to the page", e)
        null
    }
    if (answer == null) {
        onUnhandled()
        return
    }
    answer.accept(
        { handled ->
            if (handled == true) afterPageCaughtUp(reachPage, onHandled) else onUnhandled()
        },
        { error ->
            Log.w(TAG, "The page did not answer Back", error)
            onUnhandled()
        }
    )
}

private fun afterPageCaughtUp(reachPage: () -> GeckoResult<*>?, then: () -> Unit) {
    val reached = try {
        reachPage()
    } catch (e: RuntimeException) {
        null
    }
    if (reached == null) {
        then()
        return
    }
    reached.accept({ then() }, { then() })
}

class GeckoSessionHandle(
    override val tabId: String,
    private val runtime: GeckoRuntime,
    val session: GeckoSession,
    private val mainHandler: Handler,
    internal val listener: SessionEventListener,
    private val onFocused: (GeckoSession) -> Unit
) : BrowserSessionHandle {

    /**
     * True for a session created on behalf of `window.open` / `target=_blank` / `tabs.create`.
     * GeckoView insists on opening those itself, so we must not open or load them first.
     */
    internal var awaitingGeckoOpen = false

    internal var lastUrl: String? = null
    internal var lastTitle: String? = null
    internal var lastState: GeckoSession.SessionState? = null

    private var needsRestore = false
    /** Whether the page is meant to be active: what [setVisible] was last told. */
    internal var isVisible = false
        private set
    private var lastRestoreAt = 0L
    private var closed = false

    override fun loadUrl(url: String) {
        runOnMain {
            lastUrl = url
            whenOpen {
                needsRestore = false
                session.loadUri(url)
            }
        }
    }

    override fun reload() {
        runOnMain {
            if (needsRestore) {
                restore()
            } else {
                whenOpen { session.reload() }
            }
        }
    }

    override fun stop() {
        runOnMain { if (session.isOpen) session.stop() }
    }

    override fun goBack() {
        runOnMain { if (session.isOpen) session.goBack() }
    }

    override fun goForward() {
        runOnMain { if (session.isOpen) session.goForward() }
    }

    override fun handleBack(onHandled: () -> Unit, onUnhandled: () -> Unit) {
        runOnMain {
            // The engine closes only what the page with the keyboard's focus has open, yet answers
            // "taken" for any page with something open: a page without focus is not asked, or its
            // dialog would keep Back for ever while staying open
            val pageHasFocus = session.textInput.view?.hasFocus() == true
            if (closed || !session.isOpen || !pageHasFocus) {
                onUnhandled()
            } else {
                offerBackToPage(session::processBackPressed, session::containsFormData, onHandled, onUnhandled)
            }
        }
    }

    override fun print() {
        runOnMain { if (session.isOpen) session.printPageContent() }
    }

    override fun setDesktopMode(enabled: Boolean) {
        runOnMain {
            session.settings.userAgentMode = if (enabled) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
            session.settings.viewportMode = if (enabled) {
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            }
            if (session.isOpen) session.reload()
        }
    }

    override fun setVisible(visible: Boolean) {
        runOnMain {
            isVisible = visible
            if (visible && needsRestore) {
                // The OS reclaimed this tab's process while it was in the background.
                restore()
            } else if (session.isOpen) {
                session.setActive(visible)
            } else if (visible && awaitingGeckoOpen) {
                whenOpen { session.setActive(isVisible) }
            }
        }
    }

    override fun exitFullScreen() {
        runOnMain { if (session.isOpen) session.exitFullScreen() }
    }

    // Whether every match is highlighted yet; set once, on the first search
    private var findHighlightsAll = false

    override fun findInPage(query: String, backwards: Boolean, matchCase: Boolean, onResult: (FindResult) -> Unit) {
        runOnMain {
            if (!session.isOpen) return@runOnMain
            val finder = session.finder
            if (!findHighlightsAll) {
                finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
                findHighlightsAll = true
            }
            var flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
            if (matchCase) flags = flags or GeckoSession.FINDER_FIND_MATCH_CASE
            finder.find(query, flags).accept(
                { result ->
                    if (result != null) {
                        onResult(FindResult(result.searchString, result.found, result.current, result.total))
                    }
                },
                { error ->
                    Log.w(TAG, "Find in page failed", error)
                    onResult(FindResult(query, found = false, current = 0, total = 0))
                }
            )
        }
    }

    override fun clearFindInPage() {
        runOnMain { if (session.isOpen) session.finder.clear() }
    }

    override fun setFocusedForExtensions(focused: Boolean) {
        runOnMain {
            runCatching {
                runtime.webExtensionController.setTabActive(session, focused)
            }
            if (focused) onFocused(session)
        }
    }

    override fun close() {
        runOnMain {
            closed = true
            awaitingGeckoOpen = false
            if (session.isOpen) {
                session.close()
            }
        }
    }

    /** Called when the content process behind this session crashed or was killed by the OS. */
    internal fun onContentProcessGone(crashed: Boolean) {
        if (closed) return
        needsRestore = true
        val crashLoop = crashed && SystemClock.uptimeMillis() - lastRestoreAt < CRASH_LOOP_WINDOW_MS
        if (isVisible && !crashLoop) {
            restore()
        } else {
            // Background tabs restore lazily when shown again; a crash loop waits for a manual reload.
            listener.onSessionUpdated(
                tabId,
                SessionNavigationUpdate(isLoading = false, progress = 100, crashed = isVisible)
            )
        }
    }

    /** Opens an adopted session ourselves if Gecko never did, so the tab is not left blank. */
    internal fun openIfGeckoNeverDid() {
        if (closed || !awaitingGeckoOpen) return
        awaitingGeckoOpen = false
        if (!session.isOpen) {
            Log.w(TAG, "Gecko did not open the adopted session for tab $tabId; opening it directly")
            session.open(runtime)
            session.setActive(isVisible)
            lastUrl?.takeIf { it.isNotBlank() }?.let { session.loadUri(it) }
        }
    }

    private fun restore() {
        if (closed) return
        needsRestore = false
        lastRestoreAt = SystemClock.uptimeMillis()
        if (!session.isOpen) {
            session.open(runtime)
        }
        session.setActive(isVisible)
        val state = lastState
        val url = lastUrl
        if (state != null) {
            session.restoreState(state)
        } else if (!url.isNullOrBlank()) {
            session.loadUri(url)
        }
        listener.onSessionUpdated(tabId, SessionNavigationUpdate(crashed = false))
    }

    /** Runs [block] once the session is open, opening it ourselves unless Gecko is about to. */
    private fun whenOpen(block: () -> Unit) {
        if (closed) return
        if (session.isOpen) {
            awaitingGeckoOpen = false
            block()
            return
        }
        if (awaitingGeckoOpen) {
            mainHandler.postDelayed({ whenOpen(block) }, ADOPTION_POLL_MS)
            return
        }
        session.open(runtime)
        block()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

class GeckoBrowserEngine(
    context: Context,
    providedRuntime: GeckoRuntime? = null,
    /** Where camera and microphone decisions are kept; Gecko keeps the other kinds itself. */
    sitePermissionStore: SitePermissionStore = SharedPrefsSitePermissionStore(context),
    /** Asks Android for the app's camera, microphone and location permissions; null asks for none. */
    androidPermissions: AndroidPermissionRequester? = null,
    /** Puts pictures that pages copy themselves on the clipboard; null refuses them. */
    private val pageImageClipboard: PageImageClipboard? = null
) : BrowserEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    val runtime: GeckoRuntime = providedRuntime ?: createRuntime(context.applicationContext)

    private val appContext: Context = context.applicationContext

    // Copies of files picked for upload buttons; last run's copies are no longer needed
    private val uploads = UploadCache(appContext).also { it.clearPrevious() }

    /** Told which session has browser focus so per-tab extension badges can follow it. */
    var onFocusedSessionChanged: ((GeckoSession) -> Unit)? = null

    private val sessions = ConcurrentHashMap<String, GeckoSessionHandle>()
    private val sessionOwners = WeakHashMap<GeckoSession, GeckoView>()

    private var currentListener: SessionEventListener? = null

    // Main-thread only: set while a page/extension is waiting for us to hand it a brand new session.
    private var adoptionArmed = false
    private var adoptedSession: GeckoSession? = null

    /** The page menu helper once it is installed (main thread only); null until then, or if it failed. */
    private var pageMenuHelper: WebExtension? = null

    /** Tells context menus apart, so the same menu asked for twice opens twice. */
    private var contextMenuSerial = 0L

    // Without a permission delegate Gecko refuses camera, microphone, location and notifications
    // to every site ("No listener for GeckoView:ContentPermission")
    private val sitePermissions = GeckoSitePermissions(appContext, sitePermissionStore, androidPermissions)

    init {
        GeckoPagePrinter.clearLeftoverPrintFiles(appContext)
        installPageMenuHelper()
        sitePermissions.start()
    }

    override fun createSession(
        tabId: String,
        initialUrl: String,
        desktopMode: Boolean,
        listener: SessionEventListener
    ): BrowserSessionHandle {
        sessions[tabId]?.let { return it }
        currentListener = listener

        val adopt = adoptionArmed && Looper.myLooper() == Looper.getMainLooper()
        if (adopt) adoptionArmed = false

        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .useTrackingProtection(true)
            .userAgentMode(
                if (desktopMode) {
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                }
            )
            .viewportMode(
                if (desktopMode) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                }
            )
            .suspendMediaWhenInactive(false)
            .build()

        val geckoSession = GeckoSession(settings)
        val handle = GeckoSessionHandle(
            tabId = tabId,
            runtime = runtime,
            session = geckoSession,
            mainHandler = mainHandler,
            listener = listener,
            onFocused = { focused -> onFocusedSessionChanged?.invoke(focused) }
        )
        handle.lastUrl = initialUrl
        installDelegates(geckoSession, handle, listener)
        listenForPageMenus(handle)
        sessions[tabId] = handle
        onGeckoSessionCreated?.invoke(geckoSession)

        if (adopt) {
            // Gecko opens this session and loads the page itself once we return it.
            handle.awaitingGeckoOpen = true
            adoptedSession = geckoSession
            mainHandler.postDelayed({ handle.openIfGeckoNeverDid() }, ADOPTION_TIMEOUT_MS)
        } else {
            runOnMain {
                if (!geckoSession.isOpen) geckoSession.open(runtime)
                if (initialUrl.isNotBlank()) {
                    geckoSession.loadUri(initialUrl)
                }
            }
        }

        return handle
    }

    /**
     * Asks the browser to open a tab for [url] and returns that tab's *unopened* session, which
     * is what GeckoView expects back from `onNewSession` and `tabs.create`. Returns null when no
     * tab was created. Must be called on the main thread.
     */
    fun adoptNewTab(url: String, openerTabId: String?): GeckoSession? {
        val listener = currentListener ?: return null
        adoptionArmed = true
        adoptedSession = null
        try {
            listener.onNewTabRequested(url, openerTabId)
        } finally {
            adoptionArmed = false
        }
        val adopted = adoptedSession
        adoptedSession = null
        return adopted
    }

    private fun installDelegates(
        geckoSession: GeckoSession,
        handle: GeckoSessionHandle,
        listener: SessionEventListener
    ) {
        val tabId = handle.tabId

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    handle.lastUrl = url
                    listener.onSessionUpdated(tabId, SessionNavigationUpdate(url = url))
                }
                sitePermissions.onLocationChange(handle, url, perms)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                listener.onSessionUpdated(tabId, SessionNavigationUpdate(canGoBack = canGoBack))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                listener.onSessionUpdated(tabId, SessionNavigationUpdate(canGoForward = canGoForward))
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                // Returning the new tab's own session (instead of null plus a separate load)
                // keeps window.opener, POST data and popup-based sign-in flows working.
                return GeckoResult.fromValue(adoptNewTab(uri, tabId))
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                val (title, message) = describeLoadError(error)
                return GeckoResult.fromValue(
                    ErrorPages.buildDataUrl(failedUrl = uri.orEmpty(), title = title, message = message)
                )
            }
        }

        // A call must not be paused with the window minimised or the tab in the background
        geckoSession.mediaDelegate = object : GeckoSession.MediaDelegate {
            override fun onRecordingStatusChanged(
                session: GeckoSession,
                devices: Array<GeckoSession.MediaDelegate.RecordingDevice>
            ) {
                val capturing = devices
                    .filter { it.status == GeckoSession.MediaDelegate.RecordingDevice.Status.RECORDING }
                    .mapNotNull {
                        when (it.type) {
                            GeckoSession.MediaDelegate.RecordingDevice.Type.CAMERA -> SitePermissionKind.CAMERA
                            GeckoSession.MediaDelegate.RecordingDevice.Type.MICROPHONE -> SitePermissionKind.MICROPHONE
                            else -> null
                        }
                    }
                    .toSet()
                listener.onRecordingChanged(tabId, capturing.isNotEmpty())
                listener.onCaptureDevicesChanged(tabId, capturing, sitePermissions.pageOriginOf(tabId))
            }
        }

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                handle.lastTitle = title
                if (!title.isNullOrBlank()) {
                    listener.onSessionUpdated(tabId, SessionNavigationUpdate(title = title))
                }
            }

            // A response Gecko cannot show: a file to save. The body's network channel stays
            // suspended until the first read, so it can wait for the user's answer.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val body = response.body ?: return
                listener.onDownloadRequested(
                    downloadRequestFrom(
                        tabId = tabId,
                        url = response.uri,
                        headers = response.headers,
                        skipConfirmation = response.skipConfirmation,
                        body = body,
                        extensionForMime = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    )
                )
            }

            // Without this the page only fills its own view: the browser has to give it the
            // whole window.
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                listener.onFullScreenChanged(tabId, fullScreen)
            }

            // A right click (or a touchscreen long press) on a link, picture or media that the
            // page left to the browser. GeckoView cancels the page's event and shows nothing of
            // its own: without this, right clicking a link did nothing at all.
            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val mediaType = when (element.type) {
                    GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> PageMediaType.IMAGE
                    GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> PageMediaType.VIDEO
                    GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> PageMediaType.AUDIO
                    else -> PageMediaType.NONE
                }
                listener.onContextMenuRequested(
                    contextMenuAtPointer(session, tabId).copy(
                        linkUrl = element.linkUri?.takeIf { it.isNotBlank() },
                        linkText = element.linkText?.trim()?.takeIf { it.isNotEmpty() },
                        mediaUrl = element.srcUri?.takeIf { it.isNotBlank() && mediaType != PageMediaType.NONE },
                        mediaType = mediaType
                    )
                )
            }

            // window.close(): GeckoView leaves closing to the browser. Without this a sign-in
            // popup (Sign in with Google, say) stayed open as a tab after it was done.
            override fun onCloseRequest(session: GeckoSession) {
                listener.onCloseRequested(tabId)
            }

            // A dead content process captures nothing, and can't send the "stopped" report itself.
            // Without this the tab still counted as capturing on its old site, and blocking that
            // site's camera later reloaded it wherever it had gone since.
            private fun captureEnded() {
                listener.onRecordingChanged(tabId, false)
                listener.onCaptureDevicesChanged(tabId, emptySet(), null)
            }

            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "Content process crashed for tab $tabId")
                sitePermissions.onPageGone(handle)
                captureEnded()
                handle.onContentProcessGone(crashed = true)
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "Content process was killed for tab $tabId")
                sitePermissions.onPageGone(handle)
                captureEnded()
                handle.onContentProcessGone(crashed = false)
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                sitePermissions.onPageStart(handle, url)
                listener.onSessionUpdated(
                    tabId,
                    SessionNavigationUpdate(
                        url = url,
                        isLoading = true,
                        progress = 10,
                        isSecure = false,
                        crashed = false,
                        isPageStart = true
                    )
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                listener.onSessionUpdated(
                    tabId,
                    SessionNavigationUpdate(progress = progress, isLoading = progress < 100)
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                listener.onSessionUpdated(
                    tabId,
                    SessionNavigationUpdate(isLoading = false, progress = 100)
                )
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                listener.onSessionUpdated(
                    tabId,
                    SessionNavigationUpdate(isSecure = securityInfo.isSecure && !securityInfo.isException)
                )
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                // Kept so history and scroll position survive a content process crash or kill.
                handle.lastState = sessionState
            }
        }

        geckoSession.promptDelegate = GeckoPagePrompts(tabId, listener, appContext, uploads)

        geckoSession.permissionDelegate = sitePermissions.delegateFor(handle)

        // Set before a view takes the session, so GeckoView keeps it instead of its own
        geckoSession.printDelegate = GeckoPagePrinter(
            appContext = appContext,
            session = geckoSession,
            // Only a page on screen prints: a page in a background tab cannot open the print screen
            isOnScreen = { sessionOwners[geckoSession]?.session === geckoSession && handle.isVisible },
            pageTitle = { handle.lastTitle?.takeIf { it.isNotBlank() } ?: handle.lastUrl?.let { Uri.parse(it).host } },
            onReady = { file, name, byPage -> listener.onPrintReady(tabId, file.path, name, byPage) }
        )
    }

    /** A menu for [tabId], opening where the pointer last was on the view showing [session]. */
    private fun contextMenuAtPointer(session: GeckoSession, tabId: String): PageContextMenu {
        val pointer = (sessionOwners[session] as? PaneGeckoView)?.lastPointerPosition
        return PageContextMenu(tabId = tabId, x = pointer?.x, y = pointer?.y, serial = ++contextMenuSerial)
    }

    /**
     * Installs (or updates, after an app update) the page menu helper, then listens to it in
     * every page. Without it links and pictures still get their menu; the page's own does not.
     *
     * Lint cannot tell that the result arrives on the main thread: a GeckoResult calls back on
     * the thread that created it, and the engine is created on the main thread.
     */
    @SuppressLint("WrongThread")
    private fun installPageMenuHelper() {
        runCatching {
            runtime.webExtensionController.ensureBuiltIn(PAGE_MENU_EXTENSION_URI, PAGE_MENU_EXTENSION_ID).accept(
                { helper ->
                    if (helper != null) {
                        pageMenuHelper = helper
                        sessions.values.forEach(::listenForPageMenus)
                    }
                },
                { error -> Log.w(TAG, "Page menu helper could not be installed", error) }
            )
        }.onFailure { Log.w(TAG, "Page menu helper could not be installed", it) }
    }

    /** Hears the page menu helper's content script in [handle]'s page, once the helper is there. */
    private fun listenForPageMenus(handle: GeckoSessionHandle) {
        val helper = pageMenuHelper ?: return
        runOnMain {
            runCatching {
                handle.session.webExtensionController.setMessageDelegate(
                    helper,
                    object : WebExtension.MessageDelegate {
                        override fun onMessage(
                            nativeApp: String,
                            message: Any,
                            sender: WebExtension.MessageSender
                        ): GeckoResult<Any>? {
                            if (isPageMenuMessage(message)) {
                                handle.listener.onContextMenuRequested(contextMenuAtPointer(handle.session, handle.tabId))
                            }
                            linkOpenRequest(message)?.let {
                                handle.listener.onOpenLinkRequested(handle.tabId, it.url, it.inForeground)
                            }
                            // The page's address as the engine knows it, never as the page says
                            declaredSiteIcons(message, sender.url, sender.isTopLevel)?.takeIf { it.isNotEmpty() }?.let {
                                handle.listener.onSiteIconsDeclared(handle.tabId, sender.url, it)
                            }
                            if (isPageImageCopy(message)) {
                                // The page's promise settles with the answer: true once it is copied.
                                // The picture is decoded and checked off this (the main) thread.
                                val data = pageImageCopyData(message)
                                val clipboard = pageImageClipboard
                                if (data == null || clipboard == null) return GeckoResult.fromValue<Any>(false)
                                val reply = GeckoResult<Any>()
                                clipboard.copyPageImage(data) { copied -> reply.complete(copied) }
                                return reply
                            }
                            return null
                        }
                    },
                    PAGE_MENU_NATIVE_APP
                )
            }.onFailure { Log.w(TAG, "Could not listen for page menus in tab ${handle.tabId}", it) }
        }
    }

    override fun getSession(tabId: String): BrowserSessionHandle? = sessions[tabId]

    fun getGeckoSession(tabId: String): GeckoSession? = sessions[tabId]?.session

    /** Told about each tab's session as it is created, after its own delegates (see ExtensionTabControl). */
    var onGeckoSessionCreated: ((GeckoSession) -> Unit)? = null

    /** Every tab's session. */
    fun geckoSessions(): List<GeckoSession> = sessions.values.map { it.session }

    /** The tab whose page [session] shows, or null when it is not one of the browser's tabs (an extension's popup). */
    fun tabIdOf(session: GeckoSession): String? = sessions.entries.firstOrNull { it.value.session === session }?.key

    fun attachSessionToView(tabId: String, geckoView: GeckoView) {
        val handle = sessions[tabId] ?: return
        val targetSession = handle.session
        runOnMain {
            if (geckoView.session === targetSession) return@runOnMain

            // Detach targetSession from any previous GeckoView first to avoid attachment conflicts
            val previousOwner = sessionOwners[targetSession]
            if (previousOwner != null && previousOwner !== geckoView && previousOwner.session === targetSession) {
                previousOwner.releaseSession()
            }

            // Release whatever session this GeckoView was holding
            val currentSessionOnView = geckoView.session
            if (currentSessionOnView != null && currentSessionOnView !== targetSession) {
                geckoView.releaseSession()
                if (sessionOwners[currentSessionOnView] === geckoView) {
                    sessionOwners.remove(currentSessionOnView)
                }
            }

            // A session Gecko is about to open (new window) must be left closed until it does.
            val canOpen = !handle.awaitingGeckoOpen
            if (!targetSession.isOpen && canOpen) {
                targetSession.open(runtime)
            }

            geckoView.setSession(targetSession)
            // A newly shown tab paints at once; a pane nobody can see (under a fullscreen page,
            // or while the window is away) stays paused
            if (targetSession.isOpen) {
                targetSession.setActive(handle.isVisible)
            }
            sessionOwners[targetSession] = geckoView
        }
    }

    fun detachView(geckoView: GeckoView) {
        runOnMain {
            val current = geckoView.session
            if (current != null) {
                geckoView.releaseSession()
                if (sessionOwners[current] === geckoView) {
                    sessionOwners.remove(current)
                }
            }
        }
    }

    override fun closeSession(tabId: String) {
        val handle = sessions.remove(tabId) ?: return
        runOnMain {
            val owner = sessionOwners.remove(handle.session)
            if (owner != null && owner.session === handle.session) {
                owner.releaseSession()
            }
            sitePermissions.onSessionClosed(handle)
            handle.close()
        }
    }

    override fun closeAll() {
        val allIds = sessions.keys.toList()
        allIds.forEach { closeSession(it) }
    }

    override fun setEnhancedPrivacy(enabled: Boolean) {
        runOnMain {
            runCatching { applyTrackingProtection(runtime.settings.contentBlocking, enhanced = enabled) }
                .onFailure { Log.w(TAG, "Could not update tracking protection", it) }
        }
    }

    override fun loadSitePermissions(onResult: (List<SitePermissionEntry>) -> Unit) {
        runOnMain { sitePermissions.load(onResult) }
    }

    override fun setSitePermission(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState?,
        embeddedOrigin: String?
    ) {
        runOnMain { sitePermissions.set(origin, kind, state, embeddedOrigin) }
    }

    override fun clearSitePermissions(origin: String) {
        runOnMain { sitePermissions.clear(origin) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        /**
         * Sets tracking protection on [settings] for the Enhanced privacy switch ([enhanced]).
         *
         * Neither level uses Gecko's "content" list (AntiTracking.CONTENT / STRICT, or
         * EtpLevel.STRICT). That list contains general-purpose hosts such as cloudflare.com,
         * gstatic.com, google.com and cdn.jsdelivr.net, so blocking it breaks Cloudflare's bot
         * check, reCAPTCHA and a large part of the web.
         *
         * Bounce-tracking protection clears the cookies and site data of sites a page was
         * redirected through but that the user never used themselves, which is how a redirect
         * tracker follows people from site to site. It acts only under Enhanced privacy: Firefox
         * itself runs it in dry-run mode outside Nightly, so that is the cautious choice. With the
         * switch off it is on standby, GeckoView's "disabled" state for a feature that can be
         * switched on and off: no site is noted as a redirect tracker and nothing is cleared.
         * The engine only goes on recording which sites the user has used, so that once the
         * switch is on it already knows which sites to leave alone.
         */
        internal fun applyTrackingProtection(settings: ContentBlocking.Settings, enhanced: Boolean) {
            val standard = ContentBlocking.AntiTracking.DEFAULT
            val enhancedCategories = standard or
                ContentBlocking.AntiTracking.CRYPTOMINING or
                ContentBlocking.AntiTracking.FINGERPRINTING or
                ContentBlocking.AntiTracking.EMAIL
            settings
                .setAntiTracking(if (enhanced) enhancedCategories else standard)
                .setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                .setStrictSocialTrackingProtection(enhanced)
                .setBounceTrackingProtectionMode(
                    if (enhanced) {
                        ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED
                    } else {
                        ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED_STANDBY
                    }
                )
        }

        /** Whether [message] from the page menu helper asks for the page's own menu. */
        internal fun isPageMenuMessage(message: Any?): Boolean =
            (message as? org.json.JSONObject)?.optString("type") == "pageMenu"

        /**
         * The link that [message] from the page menu helper asks to open in a new tab (a
         * Ctrl+click or middle click), or null. Only web addresses: a page must not get the
         * browser to open script, `data:`, `file:` or the browser's own pages for it.
         */
        internal fun linkOpenRequest(message: Any?): LinkOpenRequest? {
            val json = message as? org.json.JSONObject ?: return null
            if (json.optString("type") != "openLink") return null
            val url = json.optString("url")
            val isWebAddress = url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
            if (!isWebAddress) return null
            return LinkOpenRequest(url = url, inForeground = json.optBoolean("foreground", false))
        }

        /** The most icons read from one page's list, and the longest address and `sizes` taken. */
        private const val MAX_DECLARED_ICONS_READ = 16
        private const val MAX_ICON_ADDRESS_LENGTH = 1024
        private const val MAX_ICON_SIZES_LENGTH = 200

        /**
         * The icons that [message] from the page menu helper says the page at [pageUrl] names for
         * itself, or null when it is not such a message, or it came from a frame inside the page
         * (not [isTopLevel]: a frame's icons are another site's), or [pageUrl] is not a web page.
         *
         * The page wrote the list, so each icon is checked as the icon client checks a redirect
         * from the page's own address ([GeckoImageNetworkClient.refuseRedirect]): a page on the
         * internet may not have the browser fetch from this device or the local network, nor from
         * another site except over https, nor from a port pages may not use. Only `rel` values
         * "icon" and "apple-touch-icon" are kept, without user names or passwords in the address,
         * and not SVG (by its `type` or its `.svg` path), which RemBrowse can't decode. At most
         * [SiteIcons.MAX_ICONS_PER_SITE], in the page's order.
         */
        internal fun declaredSiteIcons(message: Any?, pageUrl: String?, isTopLevel: Boolean): List<DeclaredSiteIcon>? {
            val json = message as? org.json.JSONObject ?: return null
            if (json.optString("type") != "siteIcons" || !isTopLevel) return null
            val page = pageUrl?.toHttpUrlOrNull() ?: return null
            val list = json.optJSONArray("icons") ?: return null
            val icons = mutableListOf<DeclaredSiteIcon>()
            for (index in 0 until minOf(list.length(), MAX_DECLARED_ICONS_READ)) {
                val entry = list.optJSONObject(index) ?: continue
                val rel = entry.opt("rel") as? String
                if (rel != "icon" && rel != "apple-touch-icon") continue
                val isTouchIcon = rel == "apple-touch-icon"
                val href = entry.opt("href") as? String ?: continue
                if (href.length > MAX_ICON_ADDRESS_LENGTH) continue
                val url = href.toHttpUrlOrNull() ?: continue
                if (url.username.isNotEmpty() || url.password.isNotEmpty()) continue
                val type = entry.opt("type") as? String ?: ""
                if (type.contains("svg", ignoreCase = true) || url.encodedPath.endsWith(".svg", ignoreCase = true)) continue
                if (GeckoImageNetworkClient.refuseRedirect(page, url) != null) continue
                val sizes = (entry.opt("sizes") as? String)?.take(MAX_ICON_SIZES_LENGTH)?.let(SiteIcons::parseSizes).orEmpty()
                icons += DeclaredSiteIcon(url = url.toString(), isTouchIcon = isTouchIcon, sizes = sizes)
            }
            return icons.distinctBy { it.url }.take(SiteIcons.MAX_ICONS_PER_SITE)
        }

        /** Whether [message] from the page menu helper hands over a picture a page copied. */
        internal fun isPageImageCopy(message: Any?): Boolean =
            (message as? org.json.JSONObject)?.optString("type") == "copyImage"

        /**
         * The picture that [message] from the page menu helper asks to put on the clipboard (a
         * page's `navigator.clipboard.write` with a PNG), as its bytes in base64, or null. Only its
         * length is checked here, against [ClipboardImages.PAGE_IMAGE_MAX_BASE64]: it is decoded
         * and checked off the main thread ([PageImageClipboard.copyPageImage]).
         */
        internal fun pageImageCopyData(message: Any?): String? {
            if (!isPageImageCopy(message)) return null
            val data = (message as org.json.JSONObject).opt("data") as? String ?: return null
            if (data.isEmpty() || data.length > ClipboardImages.PAGE_IMAGE_MAX_BASE64) return null
            return data
        }

        fun createRuntime(appContext: Context): GeckoRuntime {
            // Preferences GeckoView has no setter for (see EngineConfig). Null means "none":
            // the builder is then left alone, as before.
            val configFile = EngineConfig.prepare(
                directory = appContext.filesDir,
                smallestScreenWidthDp = DeviceScreen.smallestWidthDp(appContext),
                hasMouseOrTouchpad = PointerDevices.hasMouseOrTouchpad(appContext)
            )
            // Site isolation is not set here: in GeckoView 161 each site's pages run in their own
            // content process by default (fission.autostart is true; it was off on Android in
            // 138), as on desktop Firefox. fissionEnabled(false) on this builder would turn it off.
            val settings = GeckoRuntimeSettings.Builder()
                .apply { if (configFile != null) configFilePath(configFile) }
                .extensionsProcessEnabled(true)
                .extensionsWebAPIEnabled(true)
                .aboutConfigEnabled(true)
                .webManifest(true)
                .javaScriptEnabled(true)
                .doubleTapZoomingEnabled(true)
                .inputAutoZoomEnabled(false)
                // Page console output can contain private data; keep it out of release logcat.
                .consoleOutput(BuildConfig.DEBUG)
                .build()
            return GeckoRuntime.create(appContext, settings)
        }

        internal fun describeLoadError(error: WebRequestError): Pair<String, String> {
            return when (error.code) {
                WebRequestError.ERROR_OFFLINE ->
                    "You're offline" to "Check your network connection and try again."

                WebRequestError.ERROR_UNKNOWN_HOST ->
                    "Site not found" to "The address may be misspelled, or the site may no longer exist."

                WebRequestError.ERROR_CONNECTION_REFUSED,
                WebRequestError.ERROR_NET_RESET,
                WebRequestError.ERROR_NET_INTERRUPT ->
                    "Can't connect" to "The site refused or dropped the connection. It may be down or busy."

                WebRequestError.ERROR_NET_TIMEOUT ->
                    "The connection timed out" to "The site took too long to respond."

                WebRequestError.ERROR_SECURITY_BAD_CERT,
                WebRequestError.ERROR_SECURITY_SSL,
                WebRequestError.ERROR_BAD_HSTS_CERT ->
                    "This connection isn't secure" to
                        "The site's security certificate couldn't be verified, so the page was not loaded."

                WebRequestError.ERROR_HTTPS_ONLY ->
                    "Secure connection unavailable" to "This site doesn't support HTTPS."

                WebRequestError.ERROR_REDIRECT_LOOP ->
                    "Too many redirects" to "The page keeps redirecting and never finishes loading."

                WebRequestError.ERROR_MALFORMED_URI,
                WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
                    "That address isn't valid" to "Check the address for typing mistakes."

                WebRequestError.ERROR_FILE_NOT_FOUND,
                WebRequestError.ERROR_FILE_ACCESS_DENIED ->
                    "File not available" to "The file doesn't exist or can't be opened."

                WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
                WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
                WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
                WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
                    "Dangerous site blocked" to
                        "This page was reported as unsafe and has been blocked to protect you."

                // Android itself refused RemBrowse a connection to the local network (GeckoView 155:
                // only NS_ERROR_OS_LOCAL_NETWORK_ACCESS_DENIED, from Android 17's local network
                // permission, becomes this code). The engine's own refusal of a site
                // (NS_ERROR_LOCAL_NETWORK_ACCESS_DENIED) has no code and gets the general page.
                WebRequestError.ERROR_LOCAL_NETWORK_ACCESS_DENIED ->
                    "Local network access blocked" to
                        "Android didn't let RemBrowse connect to devices on your network, so the page couldn't be loaded."

                // The address is on the harmful add-on block list (GeckoView 148)
                WebRequestError.ERROR_HARMFULADDON_URI ->
                    "Dangerous add-on blocked" to
                        "This page was reported as distributing a harmful add-on and has been blocked to protect you."

                WebRequestError.ERROR_PROXY_CONNECTION_REFUSED,
                WebRequestError.ERROR_UNKNOWN_PROXY_HOST ->
                    "Proxy problem" to "The proxy server couldn't be reached. Check your proxy settings."

                else -> "This page couldn't be loaded" to "Something went wrong while loading the page."
            }
        }
    }
}

/**
 * Bridges Gecko's page prompts to the browser UI. Without a prompt delegate, `alert()`,
 * `confirm()`, `prompt()`, HTTP sign-in and every `<select>` dropdown silently do nothing.
 */
private class GeckoPagePrompts(
    private val tabId: String,
    private val listener: SessionEventListener,
    private val context: Context,
    private val uploads: UploadCache
) : PromptDelegate {

    /** Completes a prompt's result at most once, whichever of its callbacks fires first. */
    private class Completer(val result: GeckoResult<PromptDelegate.PromptResponse>) {
        private var done = false
        fun complete(response: () -> PromptDelegate.PromptResponse) {
            if (done) return
            done = true
            runCatching { result.complete(response()) }
                .onFailure { Log.w(TAG, "Prompt was already resolved", it) }
        }
    }

    private fun present(
        prompt: PromptDelegate.BasePrompt,
        build: (id: String, completer: Completer) -> PagePrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        val id = UUID.randomUUID().toString()
        val completer = Completer(GeckoResult())
        prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: PromptDelegate.BasePrompt) {
                listener.onPromptDismissed(id)
            }
        })
        listener.onPromptRequested(build(id, completer))
        return completer.result
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AlertPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> = present(prompt) { id, completer ->
        PagePrompt.Alert(
            id = id,
            tabId = tabId,
            title = prompt.title,
            message = prompt.message,
            onDismiss = { completer.complete { prompt.dismiss() } }
        )
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ButtonPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> = present(prompt) { id, completer ->
        PagePrompt.Confirm(
            id = id,
            tabId = tabId,
            title = prompt.title,
            message = prompt.message,
            onResult = { confirmed ->
                completer.complete {
                    prompt.confirm(
                        if (confirmed) {
                            PromptDelegate.ButtonPrompt.Type.POSITIVE
                        } else {
                            PromptDelegate.ButtonPrompt.Type.NEGATIVE
                        }
                    )
                }
            },
            onDismiss = { completer.complete { prompt.dismiss() } }
        )
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.TextPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> = present(prompt) { id, completer ->
        PagePrompt.TextInput(
            id = id,
            tabId = tabId,
            title = prompt.title,
            message = prompt.message,
            defaultValue = prompt.defaultValue.orEmpty(),
            onResult = { text -> completer.complete { prompt.confirm(text) } },
            onDismiss = { completer.complete { prompt.dismiss() } }
        )
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.AuthPrompt
    ): GeckoResult<PromptDelegate.PromptResponse> = present(prompt) { id, completer ->
        val options = prompt.authOptions
        val flags = options.flags
        val passwordOnly = flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        PagePrompt.Authentication(
            id = id,
            tabId = tabId,
            title = prompt.title,
            message = prompt.message,
            passwordOnly = passwordOnly,
            defaultUsername = options.username.orEmpty(),
            previousAttemptFailed =
                flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED != 0,
            onResult = { username, password ->
                completer.complete {
                    if (passwordOnly) prompt.confirm(password) else prompt.confirm(username, password)
                }
            },
            onDismiss = { completer.complete { prompt.dismiss() } }
        )
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.ChoicePrompt
    ): GeckoResult<PromptDelegate.PromptResponse> = present(prompt) { id, completer ->
        val multiple = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
        val options = mutableListOf<PromptOption>()
        flattenChoices(prompt.choices, indented = false, out = options)
        PagePrompt.Choice(
            id = id,
            tabId = tabId,
            title = prompt.title,
            message = prompt.message,
            multiple = multiple,
            options = options,
            onResult = { selectedIds ->
                completer.complete {
                    when {
                        multiple -> prompt.confirm(selectedIds.toTypedArray())
                        selectedIds.isNotEmpty() -> prompt.confirm(selectedIds.first())
                        else -> prompt.dismiss()
                    }
                }
            },
            onDismiss = { completer.complete { prompt.dismiss() } }
        )
    }

    /**
     * A page's upload button. Without this, choosing files did nothing on any site. The UI
     * opens the system picker; the chosen files are copied where the engine can read them
     * (see [UploadCache]) and handed over. Folder uploads (`webkitdirectory`) are not offered.
     */
    override fun onFilePrompt(
        session: GeckoSession,
        prompt: PromptDelegate.FilePrompt
    ): GeckoResult<PromptDelegate.PromptResponse> {
        if (prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        return present(prompt) { id, completer ->
            PagePrompt.FilePicker(
                id = id,
                tabId = tabId,
                title = prompt.title,
                multiple = multiple,
                mimeTypes = UploadCache.pickerMimeTypes(prompt.mimeTypes?.toList()),
                onResult = { uris ->
                    uploads.copyInBackground(uris.map(Uri::parse)) { files ->
                        val picked = if (multiple) files else files.take(1)
                        completer.complete {
                            if (picked.isEmpty()) {
                                prompt.dismiss()
                            } else {
                                prompt.confirm(context, picked.map { Uri.fromFile(it) }.toTypedArray())
                            }
                        }
                    }
                },
                onDismiss = { completer.complete { prompt.dismiss() } }
            )
        }
    }

    private fun flattenChoices(
        choices: Array<PromptDelegate.ChoicePrompt.Choice>,
        indented: Boolean,
        out: MutableList<PromptOption>
    ) {
        for (choice in choices) {
            val children = choice.items
            when {
                choice.separator -> out += PromptOption(id = choice.id, label = "", isSeparator = true)
                children != null -> {
                    out += PromptOption(
                        id = choice.id,
                        label = choice.label,
                        disabled = choice.disabled,
                        isGroupHeader = true
                    )
                    flattenChoices(children, indented = true, out = out)
                }
                else -> out += PromptOption(
                    id = choice.id,
                    label = choice.label,
                    selected = choice.selected,
                    disabled = choice.disabled,
                    indented = indented
                )
            }
        }
    }
}
