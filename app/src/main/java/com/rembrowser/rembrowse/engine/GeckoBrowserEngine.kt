package com.rembrowser.rembrowse.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "GeckoBrowserEngine"

class GeckoSessionHandle(
    override val tabId: String,
    private val runtime: GeckoRuntime,
    val session: GeckoSession,
    private val mainHandler: Handler
) : BrowserSessionHandle {

    override fun loadUrl(url: String) {
        runOnMain {
            if (!session.isOpen) {
                session.open(runtime)
            }
            session.loadUri(url)
        }
    }

    override fun reload() {
        runOnMain { session.reload() }
    }

    override fun stop() {
        runOnMain { session.stop() }
    }

    override fun goBack() {
        runOnMain { session.goBack() }
    }

    override fun goForward() {
        runOnMain { session.goForward() }
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
            session.reload()
        }
    }

    override fun setVisible(visible: Boolean) {
        runOnMain {
            session.setActive(visible)
        }
    }

    override fun setFocusedForExtensions(focused: Boolean) {
        runOnMain {
            runCatching {
                runtime.webExtensionController.setTabActive(session, focused)
            }
        }
    }

    override fun close() {
        runOnMain {
            if (session.isOpen) {
                session.close()
            }
        }
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

    private val sessions = ConcurrentHashMap<String, GeckoSessionHandle>()
    private val sessionOwners = WeakHashMap<GeckoSession, GeckoView>()

    override fun createSession(
        tabId: String,
        initialUrl: String,
        desktopMode: Boolean,
        listener: SessionEventListener
    ): BrowserSessionHandle {
        sessions[tabId]?.let { return it }

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

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
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
                if (uri.isNotBlank()) {
                    listener.onNewTabRequested(uri)
                }
                return GeckoResult.fromValue(null)
            }
        }

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    listener.onSessionUpdated(tabId, SessionNavigationUpdate(title = title))
                }
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                listener.onSessionUpdated(
                    tabId,
                    SessionNavigationUpdate(url = url, isLoading = true, progress = 10)
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
        }

        val handle = GeckoSessionHandle(tabId, runtime, geckoSession, mainHandler)
        sessions[tabId] = handle

        runOnMain {
            geckoSession.open(runtime)
            if (initialUrl.isNotBlank()) {
                geckoSession.loadUri(initialUrl)
            }
        }

        return handle
    }

    override fun getSession(tabId: String): BrowserSessionHandle? = sessions[tabId]

    fun getGeckoSession(tabId: String): GeckoSession? = sessions[tabId]?.session

    fun attachSessionToView(tabId: String, geckoView: GeckoView) {
        val targetSession = sessions[tabId]?.session ?: return
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

            if (!targetSession.isOpen) {
                targetSession.open(runtime)
            }

            // Ensure compositor is active before and after attaching so switched tabs paint immediately
            targetSession.setActive(true)
            geckoView.setSession(targetSession)
            targetSession.setActive(true)
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

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        fun createRuntime(appContext: Context): GeckoRuntime {
            val settings = GeckoRuntimeSettings.Builder()
                .extensionsProcessEnabled(true)
                .extensionsWebAPIEnabled(true)
                .aboutConfigEnabled(true)
                .webManifest(true)
                .javaScriptEnabled(true)
                .doubleTapZoomingEnabled(true)
                .inputAutoZoomEnabled(false)
                .consoleOutput(true)
                .build()
            return GeckoRuntime.create(appContext, settings)
        }
    }
}

class GeckoExtensionManager(
    private val runtime: GeckoRuntime
) : ExtensionManager {

    private val controller: WebExtensionController = runtime.webExtensionController
    private val rawExtensions = ConcurrentHashMap<String, WebExtension>()
    private val browserActions = ConcurrentHashMap<String, WebExtension.Action>()

    private var activePopupSession: GeckoSession? = null
    private var pendingPromptResult: GeckoResult<WebExtension.PermissionPromptResponse>? = null

    private val _installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installedExtensions: StateFlow<List<InstalledExtension>> = _installedExtensions.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<ExtensionInstallPrompt?>(null)
    override val pendingPrompt: StateFlow<ExtensionInstallPrompt?> = _pendingPrompt.asStateFlow()

    private val _activePopup = MutableStateFlow<ExtensionPopupState?>(null)
    override val activePopup: StateFlow<ExtensionPopupState?> = _activePopup.asStateFlow()

    init {
        configureDelegates()
    }

    fun getPopupGeckoSession(): GeckoSession? = activePopupSession

    private fun configureDelegates() {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                pendingPromptResult = result
                val allPermissions = (permissions.toList() + origins.toList()).distinct()
                val meta = extension.metaData
                _pendingPrompt.value = ExtensionInstallPrompt(
                    extensionId = extension.id,
                    extensionName = meta.name?.takeIf { it.isNotBlank() } ?: extension.id,
                    version = meta.version?.takeIf { it.isNotBlank() } ?: "1.0",
                    permissions = allPermissions,
                    onDecision = { allow ->
                        pendingPromptResult?.complete(
                            WebExtension.PermissionPromptResponse(allow, false)
                        )
                        pendingPromptResult = null
                        _pendingPrompt.value = null
                    }
                )
                return result
            }

            override fun onUpdatePrompt(
                currentlyInstalled: WebExtension,
                updatedExtension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>
            ): GeckoResult<AllowOrDeny> {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>
            ): GeckoResult<AllowOrDeny> {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        })
    }

    override suspend fun refreshInstalled(): Result<List<InstalledExtension>> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val list = controller.list().awaitResult() ?: emptyList()
                rawExtensions.clear()
                list.forEach { ext ->
                    rawExtensions[ext.id] = ext
                    registerActionDelegate(ext)
                }
                val models = list.map { toModel(it) }.sortedBy { it.name.lowercase() }
                _installedExtensions.value = models
                models
            }
        }

    override suspend fun installFromUri(uri: String): Result<InstalledExtension> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val installed = controller.install(uri).awaitResult()
                    ?: error("Extension installation was cancelled or returned empty")
                rawExtensions[installed.id] = installed
                registerActionDelegate(installed)
                val model = toModel(installed)
                refreshInstalled()
                model
            }
        }

    override suspend fun setEnabled(extensionId: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val ext = rawExtensions[extensionId]
                    ?: error("Extension $extensionId not found")
                val updated = if (enabled) {
                    controller.enable(ext, WebExtensionController.EnableSource.USER).awaitResult()
                } else {
                    controller.disable(ext, WebExtensionController.EnableSource.USER).awaitResult()
                }
                if (updated != null) {
                    rawExtensions[updated.id] = updated
                    registerActionDelegate(updated)
                }
                refreshInstalled()
                Unit
            }
        }

    override suspend fun uninstall(extensionId: String): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val ext = rawExtensions[extensionId]
                    ?: error("Extension $extensionId not found")
                controller.uninstall(ext).awaitResult()
                rawExtensions.remove(extensionId)
                browserActions.remove(extensionId)
                if (_activePopup.value?.extensionId == extensionId) {
                    dismissPopup()
                }
                refreshInstalled()
                Unit
            }
        }

    override fun triggerBrowserAction(extensionId: String) {
        val action = browserActions[extensionId]
        if (action != null) {
            action.click()
        } else {
            val ext = rawExtensions[extensionId]
            val optionsUrl = ext?.metaData?.optionsPageUrl
            if (!optionsUrl.isNullOrBlank()) {
                openPopupSession(ext, optionsUrl)
            }
        }
    }

    override fun dismissPopup() {
        activePopupSession?.let { session ->
            if (session.isOpen) session.close()
        }
        activePopupSession = null
        _activePopup.value = null
    }

    override fun dismissPrompt(allow: Boolean) {
        val prompt = _pendingPrompt.value
        if (prompt != null) {
            prompt.onDecision(allow)
        } else {
            pendingPromptResult?.complete(WebExtension.PermissionPromptResponse(allow, false))
            pendingPromptResult = null
        }
        _pendingPrompt.value = null
    }

    private fun registerActionDelegate(extension: WebExtension) {
        extension.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                ext: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                browserActions[ext.id] = action
                updateModelAction(ext.id, action.badgeText)
            }

            override fun onPageAction(
                ext: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                if (!browserActions.containsKey(ext.id)) {
                    browserActions[ext.id] = action
                }
                updateModelAction(ext.id, action.badgeText)
            }

            override fun onOpenPopup(
                ext: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession> {
                val popupSession = preparePopupSession(ext)
                return GeckoResult.fromValue(popupSession)
            }

            override fun onTogglePopup(
                ext: WebExtension,
                action: WebExtension.Action
            ): GeckoResult<GeckoSession> {
                if (_activePopup.value?.extensionId == ext.id) {
                    dismissPopup()
                    return GeckoResult.fromValue(null)
                }
                val popupSession = preparePopupSession(ext)
                return GeckoResult.fromValue(popupSession)
            }
        })
    }

    private fun preparePopupSession(extension: WebExtension): GeckoSession {
        dismissPopup()
        val popupSession = GeckoSession()
        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                dismissPopup()
            }
        }
        popupSession.open(runtime)
        activePopupSession = popupSession
        _activePopup.value = ExtensionPopupState(
            extensionId = extension.id,
            extensionName = extension.metaData.name?.takeIf { it.isNotBlank() } ?: extension.id,
            sessionId = "popup-${extension.id}"
        )
        return popupSession
    }

    private fun openPopupSession(extension: WebExtension, url: String) {
        val session = preparePopupSession(extension)
        session.loadUri(url)
    }

    private fun updateModelAction(extensionId: String, badgeText: String?) {
        _installedExtensions.value = _installedExtensions.value.map { ext ->
            if (ext.id == extensionId) {
                ext.copy(hasBrowserAction = true, badgeText = badgeText)
            } else {
                ext
            }
        }
    }

    private fun toModel(ext: WebExtension): InstalledExtension {
        val meta = ext.metaData
        val action = browserActions[ext.id]
        val hasAction = action != null || !meta.optionsPageUrl.isNullOrBlank()
        return InstalledExtension(
            id = ext.id,
            name = meta.name?.takeIf { it.isNotBlank() } ?: ext.id,
            version = meta.version?.takeIf { it.isNotBlank() } ?: "1.0",
            description = meta.description ?: "",
            enabled = meta.enabled,
            hasBrowserAction = hasAction,
            badgeText = action?.badgeText
        )
    }
}

private suspend fun <T> GeckoResult<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value ->
            if (cont.isActive) cont.resume(value)
        },
        { throwable ->
            Log.e(TAG, "GeckoResult failed", throwable)
            if (cont.isActive) {
                cont.resumeWith(Result.failure(throwable ?: IllegalStateException("Unknown GeckoView error")))
            }
        }
    )
}
