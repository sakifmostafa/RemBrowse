package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.DownloadRequest
import com.rembrowser.rembrowse.model.ExtensionInstallPrompt
import com.rembrowser.rembrowse.model.ExtensionPopupState
import com.rembrowser.rembrowse.model.InstalledExtension
import com.rembrowser.rembrowse.model.PageContextMenu
import com.rembrowser.rembrowse.model.PagePrompt
import com.rembrowser.rembrowse.model.PermissionAnswer
import com.rembrowser.rembrowse.model.SitePermissionEntry
import com.rembrowser.rembrowse.model.SitePermissionKind
import com.rembrowser.rembrowse.model.SitePermissionState
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * What a find in page turned up for [query]. [current] is the selected match, counted from 1;
 * [total] is how many there are, or -1 when there are more than the engine counts (1,000).
 * Both are 0 when nothing matched.
 */
data class FindResult(val query: String, val found: Boolean, val current: Int, val total: Int)

/**
 * A page's request for site permissions, as the engine hands it to the browser. The engine has
 * already answered what never needs the user (autoplay, persistent storage, screen capture and the
 * like) and looked up in [stored] what is remembered for the site, so the browser can decide
 * whether to ask at all.
 *
 * The page waits until the first call to [answer]; later calls are ignored. What each answer does:
 * - ALLOW grants the request and remembers ALLOW for each of [kinds] not in [stored];
 * - ALLOW_ONCE grants it and remembers nothing;
 * - BLOCK refuses it and remembers BLOCK for each of [kinds] not in [stored];
 * - DISMISS refuses it and leaves every remembered decision as it was.
 */
class SitePermissionAsk(
    val id: String,
    val tabId: String,
    /**
     * The site the decision belongs to, as scheme://host[:port]: always the page in the tab's
     * address bar, also when a frame inside it asked (as in Chrome).
     */
    val origin: String,
    val kinds: Set<SitePermissionKind>,
    /**
     * For STORAGE_ACCESS, the embedded site that wants its cookies, as Gecko grants it: its scheme
     * and registrable domain (see `SiteOrigins.siteOf`). Otherwise null.
     */
    val embeddedOrigin: String? = null,
    /** The decisions already remembered for [origin], for those of [kinds] that have one. */
    val stored: Map<SitePermissionKind, SitePermissionState> = emptyMap(),
    private val onAnswer: (PermissionAnswer) -> Unit
) {
    private val answered = AtomicBoolean(false)

    val isAnswered: Boolean
        get() = answered.get()

    fun answer(answer: PermissionAnswer) {
        if (answered.compareAndSet(false, true)) onAnswer(answer)
    }
}

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

    /**
     * Which of CAMERA and MICROPHONE the page in [tabId] is capturing now (none when it stopped),
     * and the origin of that page as the tab last committed to it (null if unknown): the site
     * whose decision the capture runs on. Reported together with [onRecordingChanged].
     */
    fun onCaptureDevicesChanged(tabId: String, devices: Set<SitePermissionKind>, pageOrigin: String?) {}

    /**
     * Remembered site permission decisions changed other than as answered: an Allow did not take
     * because Android refused the app the device, say. A list of them should be read again.
     */
    fun onSitePermissionsChanged() {}

    /** A right click on a page, which the page left to the browser, asks for [menu]. */
    fun onContextMenuRequested(menu: PageContextMenu) {}

    /**
     * A Ctrl+click or middle click on a link in [tabId]'s page, which the page left to the
     * browser, asks for [url] in a new tab: in front when [inForeground] (Shift was held too),
     * otherwise in the background. [url] is always a web address (http or https).
     */
    fun onOpenLinkRequested(tabId: String, url: String, inForeground: Boolean) {}

    /**
     * The page in [tabId] was laid out for printing as the PDF at [pdfPath]: the Print command,
     * or, when [requestedByPage], the page's own `window.print()`. The receiver owns the file
     * from here on and must delete it when done.
     */
    fun onPrintReady(tabId: String, pdfPath: String, documentName: String, requestedByPage: Boolean = false) {
        java.io.File(pdfPath).delete()
    }

    /**
     * The page in [tabId] called `window.close()`, and the engine allows it (it closes only
     * windows opened by script, such as a "Sign in with Google" popup once it is done).
     */
    fun onCloseRequested(tabId: String) {}

    /**
     * A page asked for site permissions. The receiver must answer [ask], at once or once the user
     * has: until then the page waits. Without a receiver, nothing is granted.
     */
    fun onPermissionRequested(ask: SitePermissionAsk) {
        ask.answer(PermissionAnswer.DISMISS)
    }

    /** The engine answered request [askId] itself, as DISMISS, because the page or tab that asked went away. */
    fun onPermissionRequestWithdrawn(askId: String) {}
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

    /** Opens the print dialog for the page (which can also save it as a PDF). */
    fun print() {}

    /**
     * Finds [query] on the page and highlights every match, selecting the next one after the
     * current selection, or the one before it when [backwards]. Searching for the same text
     * again moves on to the next match. [onResult] is called on the main thread, and not at all
     * when a newer search overtakes this one.
     */
    fun findInPage(query: String, backwards: Boolean, matchCase: Boolean, onResult: (FindResult) -> Unit) {}

    /** Removes the highlighting of a find in page. */
    fun clearFindInPage() {}
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

    /** Every remembered site permission decision. [onResult] is called on the main thread, possibly later. */
    fun loadSitePermissions(onResult: (List<SitePermissionEntry>) -> Unit) {
        onResult(emptyList())
    }

    /**
     * Remembers [state] for [kind] on [origin], or forgets the decision when [state] is null, so
     * the site is asked again. For STORAGE_ACCESS, [embeddedOrigin] names the embedded site; when
     * it is null the change applies to every embedded site [origin] has a decision for.
     */
    fun setSitePermission(
        origin: String,
        kind: SitePermissionKind,
        state: SitePermissionState?,
        embeddedOrigin: String? = null
    ) {}

    /** Forgets every decision remembered for [origin]. */
    fun clearSitePermissions(origin: String) {}
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
