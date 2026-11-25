package com.rembrowser.rembrowse.engine

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rembrowser.rembrowse.model.ExtensionDataCollection
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.ExtensionPromptKind
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.Image
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.InstallException.ErrorCodes
import org.mozilla.geckoview.WebExtensionController
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val TAG = "GeckoExtensionManager"

/** How long a freshly installed default extension is kept from opening tabs of its own. */
private const val WELCOME_TAB_QUIET_MS = 30_000L

/** Toolbar icons are drawn at 20dp; rendering them larger keeps them sharp. */
private const val ICON_RENDER_DP = 32

/**
 * How long a changed toolbar icon waits before it is rendered. Every action update (an ad
 * blocker's badge counting up while a page loads) arrives with a new icon object, usually of
 * the same picture, so updates in a burst are rendered once.
 */
private const val ICON_REFRESH_DELAY_MS = 300L

/**
 * RemBrowse's own helper extension, shipped in the APK's assets. It gives other extensions'
 * popups a viewport that fits the popup; see assets/extensions/popup_viewport/popupViewport.js.
 * Built-in extensions are never listed to the user.
 */
internal const val POPUP_VIEWPORT_EXTENSION_ID = "extension-popups@rembrowse"
internal const val POPUP_VIEWPORT_EXTENSION_URI = "resource://android/assets/extensions/popup_viewport/"

/**
 * The answer to an install prompt: [allow] grants the extension's permissions and, as RemBrowse
 * always does (see allowInPrivateBrowsing), private browsing; technical and interaction data
 * only when the extension offered it and the user ticked its box ([shareTechnicalData]).
 */
internal fun installAnswer(allow: Boolean, shareTechnicalData: Boolean): WebExtension.PermissionPromptResponse =
    WebExtension.PermissionPromptResponse(allow, allow, allow && shareTechnicalData)

/**
 * The answer for one of the browser's own default extensions, installed without a prompt: all
 * its permissions and private browsing, never technical and interaction data, which nobody was
 * asked about.
 */
internal fun trustedInstallAnswer(): WebExtension.PermissionPromptResponse =
    WebExtension.PermissionPromptResponse(true, true, false)

/**
 * The extension prompts waiting for an answer, oldest first; the oldest is the one [onShow].
 * Main thread only.
 */
internal class ExtensionPromptQueue {
    private val waiting = ArrayDeque<ExtensionInstallPrompt>()

    private val _onShow = MutableStateFlow<ExtensionInstallPrompt?>(null)
    val onShow: StateFlow<ExtensionInstallPrompt?> = _onShow.asStateFlow()

    fun add(prompt: ExtensionInstallPrompt) {
        waiting.addLast(prompt)
        _onShow.value = waiting.firstOrNull()
    }

    /** Takes [prompt] out, bringing the next one on show; false when it was not waiting. */
    fun remove(prompt: ExtensionInstallPrompt): Boolean {
        // By identity: two requests may look alike
        val index = waiting.indexOfFirst { it === prompt }
        if (index < 0) return false
        waiting.removeAt(index)
        _onShow.value = waiting.firstOrNull()
        return true
    }

    /**
     * Gives [prompt] its answer, but only while it is the one on show. An answer meant for a
     * prompt that has gone (a second tap on its dialog before the next one is drawn) is dropped,
     * and so never reaches the prompt that took its place, which nobody has seen yet.
     */
    fun answer(prompt: ExtensionInstallPrompt, allow: Boolean, shareTechnicalData: Boolean) {
        if (_onShow.value === prompt) prompt.onDecision(allow, shareTechnicalData)
    }
}

/**
 * @param openExtensionTab creates a browser tab for [url] and returns its *unopened* session,
 *   used to honour `browser.tabs.create()`.
 * @param openUrlInNewTab opens [url] in an ordinary new tab (used for extension options pages).
 */
class GeckoExtensionManager(
    private val runtime: GeckoRuntime,
    private val openExtensionTab: (url: String) -> GeckoSession? = { null },
    private val openUrlInNewTab: (url: String) -> Unit = {}
) : ExtensionManager {

    private val controller: WebExtensionController = runtime.webExtensionController
    private val rawExtensions = ConcurrentHashMap<String, WebExtension>()

    // Toolbar actions are touched on the main thread only (Gecko delegates and our own calls).
    private val defaultActions = HashMap<String, WebExtension.Action>()
    private val sessionActions = WeakHashMap<GeckoSession, MutableMap<String, WebExtension.Action>>()
    private var activeSession: GeckoSession? = null

    private var activePopupSession: GeckoSession? = null

    // Toolbar icons. GeckoView hands over a new Image with every action update, so the Image
    // last rendered only tells us that something *may* have changed (a VPN showing that it
    // connected); renders are coalesced and a bitmap that looks the same is not published.
    private val iconBitmaps = HashMap<String, Bitmap>()
    private val iconSources = HashMap<String, Image>()
    private val iconRendersPending = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconPixelSize =
        (ICON_RENDER_DP * Resources.getSystem().displayMetrics.density).roundToInt().coerceAtLeast(ICON_RENDER_DP)

    // Ids of default extensions being installed right now; their permission review is skipped.
    private val trustedInstallIds = HashSet<String>()

    // Extensions already asked to be allowed in private browsing this run (see
    // allowInPrivateBrowsing), so a refusal is not retried on every refresh.
    private val privateBrowsingRequested = HashSet<String>()

    // Extensions that just arrived as a default must not pop open a "thanks for installing"
    // tab over whatever the user is doing. Maps extension id to an uptime deadline.
    private val quietUntil = HashMap<String, Long>()

    // Permission prompts are answered one at a time; later requests wait their turn instead of
    // overwriting (and so never resolving) the one already on screen.
    private val prompts = ExtensionPromptQueue()

    private val _installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installedExtensions: StateFlow<List<InstalledExtension>> = _installedExtensions.asStateFlow()

    override val pendingPrompt: StateFlow<ExtensionInstallPrompt?> = prompts.onShow

    private val _activePopup = MutableStateFlow<ExtensionPopupState?>(null)
    override val activePopup: StateFlow<ExtensionPopupState?> = _activePopup.asStateFlow()

    // The popup helper reports the size each desktop-style popup's content wants
    private val popupSizeMessages = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            PopupContentSize.parse(message)?.let(::onPopupContentSize)
            return null
        }
    }

    init {
        configureDelegates()
        installBuiltInHelpers()
    }

    /**
     * Installs (or updates, after an app update) the built-in helper. If it cannot be set up,
     * popups still open; they are just shown the way Gecko lays them out by default.
     *
     * Lint cannot tell that the result arrives on the main thread: a GeckoResult calls back on
     * the thread that created it, and this runs on the main thread.
     */
    @SuppressLint("WrongThread")
    private fun installBuiltInHelpers() {
        runCatching {
            controller.ensureBuiltIn(POPUP_VIEWPORT_EXTENSION_URI, POPUP_VIEWPORT_EXTENSION_ID).accept(
                { helper ->
                    helper?.setMessageDelegate(popupSizeMessages, PopupContentSize.NATIVE_APP)
                    Log.d(TAG, "Popup viewport helper ready")
                },
                { error -> Log.w(TAG, "Popup viewport helper could not be installed", error) }
            )
        }.onFailure { Log.w(TAG, "Popup viewport helper could not be installed", it) }
    }

    /** Sizes the open popup to what its content asked for; reports for other popups are stale. */
    private fun onPopupContentSize(size: PopupContentSize) {
        val popup = _activePopup.value ?: return
        if (popup.extensionId != size.extensionId) return
        _activePopup.value = popup.copy(contentWidth = size.width, contentHeight = size.height)
    }

    fun getPopupGeckoSession(): GeckoSession? = activePopupSession

    /** The session that currently has browser focus; per-tab badges and popups follow it. */
    fun setActiveSession(session: GeckoSession) {
        if (activeSession === session) return
        activeSession = session
        refreshActionModels()
    }

    private fun configureDelegates() {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            // dataCollectionPermissions is what the extension's manifest says it collects and
            // sends its developer, shown in the prompt (see ExtensionDataCollection). On an
            // install it also holds the optional "technical and interaction data", which is
            // granted only when the user ticks its box: the third value of
            // PermissionPromptResponse.
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                if (extension.id in trustedInstallIds) {
                    return GeckoResult.fromValue(trustedInstallAnswer())
                }
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                enqueuePrompt(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions,
                    ExtensionPromptKind.INSTALL
                ) { allow, shareTechnicalData ->
                    result.complete(installAnswer(allow, shareTechnicalData))
                }
                return result
            }

            // Updates that ask for more access, and optional permission requests, used to be
            // approved silently. They now go through the same review dialog as an install.
            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny> {
                val result = GeckoResult<AllowOrDeny>()
                enqueuePrompt(
                    extension,
                    newPermissions,
                    newOrigins,
                    newDataCollectionPermissions,
                    ExtensionPromptKind.UPDATE
                ) { allow, _ ->
                    result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                return result
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny> {
                val result = GeckoResult<AllowOrDeny>()
                enqueuePrompt(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions,
                    ExtensionPromptKind.OPTIONAL_PERMISSIONS
                ) { allow, _ ->
                    result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                return result
            }
        })
    }

    private fun enqueuePrompt(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
        kind: ExtensionPromptKind,
        complete: (allow: Boolean, shareTechnicalData: Boolean) -> Unit
    ) {
        val meta = extension.metaData
        val data = ExtensionDataCollection.forPrompt(kind, dataCollectionPermissions.toList())
        lateinit var prompt: ExtensionInstallPrompt
        prompt = ExtensionInstallPrompt(
            extensionId = extension.id,
            extensionName = meta.name?.takeIf { it.isNotBlank() } ?: extension.id,
            version = meta.version.orEmpty().ifBlank { "1.0" },
            permissions = (permissions.toList() + origins.toList()).distinct(),
            kind = kind,
            dataCollection = data.collected,
            offersTechnicalData = data.offersTechnicalData,
            onDecision = { allow, shareTechnicalData ->
                // remove() guards against a double tap answering the same prompt twice
                if (prompts.remove(prompt)) {
                    runCatching { complete(allow, allow && data.offersTechnicalData && shareTechnicalData) }
                        .onFailure { Log.w(TAG, "Extension prompt was already resolved", it) }
                }
            }
        )
        prompts.add(prompt)
    }

    override suspend fun refreshInstalled(): Result<List<InstalledExtension>> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                // Built-in helpers are part of the browser, not the user's extensions
                val list = (controller.list().awaitResult() ?: emptyList()).filterNot { it.isBuiltIn }
                rawExtensions.clear()
                list.forEach { ext ->
                    rawExtensions[ext.id] = ext
                    registerDelegates(ext)
                }
                val installedIds = list.map { it.id }.toSet()
                defaultActions.keys.retainAll(installedIds)
                iconSources.keys.retainAll(installedIds)
                iconBitmaps.keys.retainAll(installedIds)
                iconRendersPending.retainAll(installedIds)
                list.forEach { requestIcon(it.id) }
                list.forEach { allowInPrivateBrowsing(it) }
                val models = list.map { toModel(it) }.sortedBy { it.name.lowercase() }
                _installedExtensions.value = models
                models
            }
        }

    override suspend fun installFromUri(uri: String): Result<InstalledExtension> =
        withContext(Dispatchers.Main.immediate) {
            try {
                val installed = controller.install(uri).awaitResult()
                    ?: error("Extension installation returned nothing")
                rawExtensions[installed.id] = installed
                registerDelegates(installed)
                val model = toModel(installed)
                refreshInstalled()
                Result.success(model)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(describeInstallFailure(e))
            }
        }

    override suspend fun installTrusted(uri: String, expectedId: String): Result<InstalledExtension> =
        withContext(Dispatchers.Main.immediate) {
            trustedInstallIds.add(expectedId)
            quietUntil[expectedId] = SystemClock.uptimeMillis() + WELCOME_TAB_QUIET_MS
            try {
                // The review is skipped only for [expectedId]. Anything else was reviewed by the
                // user like any install, but isn't what was asked for: it is removed again, so a
                // retry on the next launch doesn't pile up stray add-ons.
                val result = installFromUri(uri)
                val installed = result.getOrNull()
                if (installed != null && installed.id != expectedId) {
                    uninstall(installed.id)
                    Result.failure(IllegalStateException("Installed ${installed.id} instead of $expectedId"))
                } else {
                    result
                }
            } finally {
                trustedInstallIds.remove(expectedId)
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
                    registerDelegates(updated)
                }
                if (!enabled && _activePopup.value?.extensionId == extensionId) {
                    dismissPopup()
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
                defaultActions.remove(extensionId)
                iconSources.remove(extensionId)
                iconBitmaps.remove(extensionId)
                iconRendersPending.remove(extensionId)
                sessionActions.values.forEach { it.remove(extensionId) }
                if (_activePopup.value?.extensionId == extensionId) {
                    dismissPopup()
                }
                refreshInstalled()
                Unit
            }
        }

    override fun triggerBrowserAction(extensionId: String) {
        val action = effectiveAction(extensionId)
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

    override fun dismissPrompt(prompt: ExtensionInstallPrompt, allow: Boolean, shareTechnicalData: Boolean) {
        prompts.answer(prompt, allow, shareTechnicalData)
    }

    private fun registerDelegates(extension: WebExtension) {
        extension.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(
                ext: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                storeAction(ext.id, session, action)
            }

            override fun onPageAction(
                ext: WebExtension,
                session: GeckoSession?,
                action: WebExtension.Action
            ) {
                // Only stands in for extensions that have no toolbar (browser) action at all
                if (session == null && !defaultActions.containsKey(ext.id)) {
                    storeAction(ext.id, null, action)
                }
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

        extension.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(
                source: WebExtension,
                createDetails: WebExtension.CreateTabDetails
            ): GeckoResult<GeckoSession>? {
                if (SystemClock.uptimeMillis() < (quietUntil[source.id] ?: 0L)) return null
                val session = openExtensionTab(createDetails.url ?: "about:blank") ?: return null
                return GeckoResult.fromValue(session)
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                val optionsUrl = source.metaData.optionsPageUrl
                if (!optionsUrl.isNullOrBlank()) {
                    openUrlInNewTab(optionsUrl)
                }
            }
        })
    }

    /**
     * Gecko reports an extension's default action with a null session and per-tab overrides
     * (uBlock's blocked-request count, for example) with the tab's session. Keeping them apart
     * stops one tab's badge from leaking onto every other tab.
     */
    private fun storeAction(extensionId: String, session: GeckoSession?, action: WebExtension.Action) {
        if (session == null) {
            defaultActions[extensionId] = action
        } else {
            sessionActions.getOrPut(session) { HashMap() }[extensionId] = action
        }
        refreshActionModels()
    }

    private fun effectiveAction(extensionId: String): WebExtension.Action? {
        val default = defaultActions[extensionId]
        val override = activeSession?.let { sessionActions[it]?.get(extensionId) }
        return when {
            override != null && default != null -> override.withDefault(default)
            override != null -> override
            else -> default
        }
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

    private fun refreshActionModels() {
        val current = _installedExtensions.value
        if (current.isEmpty()) return
        val updated = current.map { ext ->
            requestIcon(ext.id)
            val icon = iconBitmaps[ext.id] ?: ext.icon
            val action = effectiveAction(ext.id) ?: return@map ext.copy(icon = icon)
            ext.copy(
                hasBrowserAction = true,
                badgeText = action.badgeText,
                badgeBackgroundColor = action.badgeBackgroundColor,
                badgeTextColor = action.badgeTextColor,
                icon = icon
            )
        }
        if (updated != current) {
            _installedExtensions.value = updated
        }
    }

    /**
     * Allows [extension] in private browsing, once per run, if it is not already.
     *
     * RemBrowse has no private windows, so the permission does not expose anything; what it
     * does is unlock APIs that Gecko keeps from extensions without it. VPN extensions need it:
     * `proxy.settings` refuses them otherwise (and shows "possibly a permission is missing").
     * Extensions installed since this change get it at install; this covers those installed
     * before; the restart that applies it opens no tabs. If RemBrowse ever gains private
     * windows, this must become the user's choice.
     */
    private fun allowInPrivateBrowsing(extension: WebExtension) {
        if (extension.metaData.allowedInPrivateBrowsing || !privateBrowsingRequested.add(extension.id)) return
        // Gecko restarts the extension to apply the change, and some (AdGuard) greet a restart
        // with a "thanks for installing" tab; nothing the user did calls for one
        quietUntil[extension.id] = SystemClock.uptimeMillis() + WELCOME_TAB_QUIET_MS
        controller.setAllowedInPrivateBrowsing(extension, true).accept(
            { updated ->
                if (updated != null && rawExtensions.containsKey(updated.id)) {
                    rawExtensions[updated.id] = updated
                    registerDelegates(updated)
                }
            },
            { error -> Log.w(TAG, "Couldn't allow ${extension.id} in private browsing", error) }
        )
    }

    /** The icon [extensionId]'s button should show: its action's, else the extension's own. */
    private fun currentIcon(extensionId: String): Image? =
        effectiveAction(extensionId)?.icon ?: rawExtensions[extensionId]?.metaData?.icon

    /**
     * Makes sure [extensionId]'s current toolbar icon gets rendered. At most one render per
     * extension is scheduled or running at a time, and it picks up the newest icon when it
     * starts. An extension's first icon is rendered at once; later changes wait
     * [ICON_REFRESH_DELAY_MS], and the previous bitmap stays on screen meanwhile.
     */
    private fun requestIcon(extensionId: String) {
        val image = currentIcon(extensionId)
        if (image == null) {
            iconSources.remove(extensionId)
            iconBitmaps.remove(extensionId)
            return
        }
        if (iconSources[extensionId] === image) return
        if (!iconRendersPending.add(extensionId)) return
        val delay = if (iconBitmaps.containsKey(extensionId)) ICON_REFRESH_DELAY_MS else 0L
        mainHandler.postDelayed({ renderIcon(extensionId) }, delay)
    }

    private fun renderIcon(extensionId: String) {
        val image = currentIcon(extensionId)
        if (image == null || !rawExtensions.containsKey(extensionId)) {
            iconRendersPending.remove(extensionId)
            return
        }
        iconSources[extensionId] = image
        image.getBitmap(iconPixelSize).accept(
            { bitmap ->
                iconRendersPending.remove(extensionId)
                if (bitmap != null && rawExtensions.containsKey(extensionId)) {
                    val previous = iconBitmaps[extensionId]
                    if (previous == null || !looksTheSame(previous, bitmap)) {
                        iconBitmaps[extensionId] = bitmap
                        refreshActionModels()
                    }
                }
                // The icon may have changed again while this one was rendering
                requestIcon(extensionId)
            },
            { error ->
                // Not retried for this icon; the button shows the previous one or a puzzle piece
                iconRendersPending.remove(extensionId)
                Log.w(TAG, "Couldn't render the icon of $extensionId", error)
            }
        )
    }

    // Bitmap.sameAs is marked for worker threads because it compares every pixel; these are
    // toolbar icons of at most a few thousand pixels, compared at most a few times a second.
    @SuppressLint("WrongThread")
    private fun looksTheSame(a: Bitmap, b: Bitmap): Boolean = a.sameAs(b)

    private fun toModel(ext: WebExtension): InstalledExtension {
        val meta = ext.metaData
        val action = effectiveAction(ext.id)
        val hasAction = action != null || !meta.optionsPageUrl.isNullOrBlank()
        return InstalledExtension(
            id = ext.id,
            name = meta.name?.takeIf { it.isNotBlank() } ?: ext.id,
            version = meta.version.orEmpty().ifBlank { "1.0" },
            description = meta.description ?: "",
            enabled = meta.enabled,
            hasBrowserAction = hasAction,
            badgeText = action?.badgeText,
            icon = iconBitmaps[ext.id],
            badgeBackgroundColor = action?.badgeBackgroundColor,
            badgeTextColor = action?.badgeTextColor
        )
    }

    private fun describeInstallFailure(error: Throwable): Throwable {
        val code = (error as? WebExtension.InstallException)?.code ?: return error
        val message = when (code) {
            ErrorCodes.ERROR_USER_CANCELED -> return ExtensionInstallCancelledException()
            ErrorCodes.ERROR_NETWORK_FAILURE ->
                "Couldn't download the extension. Check your connection and try again."
            ErrorCodes.ERROR_INCORRECT_HASH, ErrorCodes.ERROR_CORRUPT_FILE ->
                "The extension file is damaged or incomplete."
            ErrorCodes.ERROR_FILE_ACCESS -> "The extension file couldn't be read."
            ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED ->
                "This extension isn't signed by Mozilla, so it can't be installed."
            ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE, ErrorCodes.ERROR_UNSUPPORTED_ADDON_TYPE ->
                "This file isn't a supported browser extension."
            ErrorCodes.ERROR_INCOMPATIBLE -> "This extension isn't compatible with this browser."
            ErrorCodes.ERROR_BLOCKLISTED, ErrorCodes.ERROR_SOFT_BLOCKED ->
                "This extension is blocked for security or stability reasons."
            ErrorCodes.ERROR_INVALID_DOMAIN -> "That site isn't allowed to install extensions."
            ErrorCodes.ERROR_ADMIN_INSTALL_ONLY -> "This extension can only be installed by an administrator."
            ErrorCodes.ERROR_POSTPONED -> "The update will finish the next time the extension restarts."
            else -> "The extension couldn't be installed (error $code)."
        }
        return IllegalStateException(message, error)
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
