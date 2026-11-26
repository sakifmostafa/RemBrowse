package com.rembrowser.rembrowse.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rembrowser.rembrowse.model.InstalledExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Lets extensions close and update the browser's tabs (`tabs.remove`, `tabs.update`).
 *
 * GeckoView asks the app before it does either, through a `WebExtension.SessionTabDelegate`
 * that must be set on each tab's session for each extension
 * (`GeckoSession.getWebExtensionController().setTabDelegate`, kept per extension id). Without
 * one it refuses: `tabs.remove` failed with "tabs.remove is not supported" and `tabs.update`
 * with "tabs.update is not supported", so an extension could not close a tab it had opened
 * (a sign-in or welcome tab) or bring one to the front.
 *
 * Gecko has already checked the call before asking (the tab id, the `tabs` permission where
 * one is needed, and for `tabs.update` that the address may be loaded), so nothing is added on
 * top: a request for one of the browser's tabs is allowed, and one for a session that is not a
 * tab (an extension's popup) is refused.
 * - `onCloseTab` closes the tab as the user closing it would ([closeTab]): its pane, in a split
 *   view too, takes another tab. The close is posted to the main thread, and Gecko passes its
 *   answer on through two posts of its own, so the tab goes (and `tabs.onRemoved` fires) just
 *   before the extension's `tabs.remove` settles, as in desktop Firefox.
 * - `onUpdateTab` brings the tab to the front for `active: true` ([selectTab]). A new `url` is
 *   loaded by Gecko itself once allowed (`ext-tabs.js` calls `loadURIInTab` after asking), so it
 *   is not loaded a second time here; Gecko also handles the other properties it supports.
 *
 * Generic over the session ([S]) and extension ([E]) types so it can be tested on the JVM;
 * [GeckoExtensionTabs] connects it to GeckoView. Main thread only.
 */
internal class ExtensionTabControl<S : Any, E : Any>(
    /** Every tab's session. */
    private val sessions: () -> List<S>,
    /** Sets the tab delegate for [E] on [S]. Setting it again for the same extension replaces it. */
    private val register: (session: S, extension: E) -> Unit,
    /** The tab [S] belongs to, or null when it is not one of the browser's tabs. */
    private val tabIdOf: (S) -> String?,
    private val closeTab: (tabId: String) -> Unit,
    private val selectTab: (tabId: String) -> Unit
) {
    private var extensions: List<E> = emptyList()

    /** The installed extensions, built-in ones included, now [list]: each gets the delegate on every tab. */
    fun onExtensionsListed(list: List<E>) {
        extensions = list
        for (session in sessions()) {
            list.forEach { register(session, it) }
        }
    }

    /** A tab's session was created: every known extension gets the delegate on it. */
    fun onSessionCreated(session: S) {
        extensions.forEach { register(session, it) }
    }

    /** `tabs.remove` for [session]'s tab. True (allowed) when it is one of the browser's tabs. */
    fun close(session: S): Boolean {
        val tabId = tabIdOf(session) ?: return false
        closeTab(tabId)
        return true
    }

    /** `tabs.update` for [session]'s tab with [active]. True (allowed) when it is one of the browser's tabs. */
    fun update(session: S, active: Boolean?): Boolean {
        val tabId = tabIdOf(session) ?: return false
        if (active == true) selectTab(tabId)
        return true
    }
}

/** Connects [ExtensionTabControl] to GeckoView and the browser's tabs. */
internal object GeckoExtensionTabs {
    private const val TAG = "ExtensionTabs"

    /**
     * From now on, extensions may close ([closeTab]) and bring to the front ([selectTab]) the
     * tabs of [engine]. The extensions are read from the engine again whenever the list of
     * [installed] ones changes, so one added later gets the delegate on every tab, and every tab
     * created later gets it for every extension.
     */
    fun connect(
        engine: GeckoBrowserEngine,
        installed: Flow<List<InstalledExtension>>,
        scope: CoroutineScope,
        closeTab: (tabId: String) -> Unit,
        selectTab: (tabId: String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var delegate: WebExtension.SessionTabDelegate
        val control = ExtensionTabControl<GeckoSession, WebExtension>(
            sessions = engine::geckoSessions,
            register = { session, extension ->
                runCatching { session.webExtensionController.setTabDelegate(extension, delegate) }
                    .onFailure { Log.w(TAG, "Couldn't let ${extension.id} control tabs", it) }
            },
            tabIdOf = engine::tabIdOf,
            // Posted, so Gecko's own call finishes first; tabs.remove settles just after the tab goes
            closeTab = { tabId -> mainHandler.post { closeTab(tabId) } },
            selectTab = selectTab
        )
        delegate = object : WebExtension.SessionTabDelegate {
            override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (control.close(session)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)

            override fun onUpdateTab(
                source: WebExtension,
                session: GeckoSession,
                details: WebExtension.UpdateTabDetails
            ): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (control.update(session, details.active)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }
        engine.onGeckoSessionCreated = { session ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                control.onSessionCreated(session)
            } else {
                mainHandler.post { control.onSessionCreated(session) }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            installed.map { list -> list.map { it.id to it.version } }
                .distinctUntilChanged()
                .collect {
                    // Built-in extensions are not in that list; Gecko's own list has them too
                    engine.runtime.webExtensionController.list().accept(
                        { all -> control.onExtensionsListed(all.orEmpty()) },
                        { error -> Log.w(TAG, "Couldn't list extensions for tab control", error) }
                    )
                }
        }
    }
}
