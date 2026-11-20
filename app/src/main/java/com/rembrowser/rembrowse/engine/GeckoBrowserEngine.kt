package com.rembrowser.rembrowse.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.MimeTypeMap
import com.rembrowser.rembrowse.BuildConfig
import com.rembrowser.rembrowse.data.UploadCache
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PromptOption
import com.rembrowser.rembrowse.util.ErrorPages
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
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

class GeckoSessionHandle(
    override val tabId: String,
    private val runtime: GeckoRuntime,
    val session: GeckoSession,
    private val mainHandler: Handler,
    private val listener: SessionEventListener,
    private val onFocused: (GeckoSession) -> Unit
) : BrowserSessionHandle {

    /**
     * True for a session created on behalf of `window.open` / `target=_blank` / `tabs.create`.
     * GeckoView insists on opening those itself, so we must not open or load them first.
     */
    internal var awaitingGeckoOpen = false

    internal var lastUrl: String? = null
    internal var lastState: GeckoSession.SessionState? = null

    private var needsRestore = false
    private var isVisible = false
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
    providedRuntime: GeckoRuntime? = null
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
        sessions[tabId] = handle

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

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
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

            override fun onCrash(session: GeckoSession) {
                Log.w(TAG, "Content process crashed for tab $tabId")
                handle.onContentProcessGone(crashed = true)
            }

            override fun onKill(session: GeckoSession) {
                Log.w(TAG, "Content process was killed for tab $tabId")
                handle.onContentProcessGone(crashed = false)
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
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
    }

    override fun getSession(tabId: String): BrowserSessionHandle? = sessions[tabId]

    fun getGeckoSession(tabId: String): GeckoSession? = sessions[tabId]?.session

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
            // Make sure the compositor is active so a newly shown tab paints immediately
            if (targetSession.isOpen) {
                targetSession.setActive(true)
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
            handle.close()
        }
    }

    override fun closeAll() {
        val allIds = sessions.keys.toList()
        allIds.forEach { closeSession(it) }
    }

    override fun setEnhancedPrivacy(enabled: Boolean) {
        runOnMain {
            runCatching {
                // Neither level uses Gecko's "content" list (AntiTracking.CONTENT / STRICT, or
                // EtpLevel.STRICT). That list contains general-purpose hosts such as
                // cloudflare.com, gstatic.com, google.com and cdn.jsdelivr.net, so blocking it
                // breaks Cloudflare's bot check, reCAPTCHA and a large part of the web.
                val standard = ContentBlocking.AntiTracking.DEFAULT
                val enhanced = standard or
                    ContentBlocking.AntiTracking.CRYPTOMINING or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.EMAIL
                runtime.settings.contentBlocking
                    .setAntiTracking(if (enabled) enhanced else standard)
                    .setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                    .setStrictSocialTrackingProtection(enabled)
            }.onFailure { Log.w(TAG, "Could not update tracking protection", it) }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        fun createRuntime(appContext: Context): GeckoRuntime {
            // Preferences GeckoView has no setter for (see EngineConfig). Null means "none":
            // the builder is then left alone, as before.
            val configFile = EngineConfig.prepare(
                directory = appContext.filesDir,
                smallestScreenWidthDp = appContext.resources.configuration.smallestScreenWidthDp
            )
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
