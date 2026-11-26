package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.ExtensionPopupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The extension popup on screen, and what a click on an extension's button does to it.
 * [GeckoExtensionManager] keeps the engine's side, the popup's session [S]; the rules are kept
 * here, apart from the engine, so they can be tested. Main thread only.
 *
 * A button opens its extension's popup and, as in desktop Firefox, a click on it while the
 * popup is open closes it again. Two things made that close a popup the user had only just
 * asked for:
 * - A popup appears only once its page has said how big it wants to be (up to
 *   `POPUP_SIZE_WAIT_MS`, see ExtensionPopupDialog). A second click before then, made because
 *   nothing seemed to happen, closed a popup nobody had seen.
 * - Both clicks of a double click reached the engine before the first had opened anything, and
 *   the engine's answer to the second closed what the first opened.
 *
 * So a click on the button of a popup that is still appearing, and the second click of a double
 * click, do nothing.
 *
 * @param doubleClickMs how soon a second click on the same button counts as part of a double
 *   click: Android's double-tap timeout.
 */
internal class ExtensionPopups<S : Any>(private val doubleClickMs: Long) {

    /** What a click on an extension's button does. */
    enum class Click {
        /** Hand it to the extension: it opens its popup or runs its own action. */
        TRIGGER,

        /** Its popup is open and has been seen: close it. */
        CLOSE,

        /** Nothing: its popup is still appearing, or this is a double click's second click. */
        IGNORE
    }

    /** What the engine's request to toggle an extension's popup does. */
    enum class Toggle { OPEN, CLOSE, KEEP }

    private val _state = MutableStateFlow<ExtensionPopupState?>(null)
    val state: StateFlow<ExtensionPopupState?> = _state.asStateFlow()

    /** The open popup's session. */
    var session: S? = null
        private set

    // Whether the open popup has appeared on screen (shown)
    private var seen = false

    // Each popup gets a new id, so the screen never takes a new popup for the one before it
    private var opened = 0

    private var lastClickId: String? = null
    private var lastClickAt = 0L

    /** A click on [extensionId]'s button at [now], in milliseconds of uptime. */
    fun click(extensionId: String, now: Long): Click {
        val secondOfDouble = lastClickId == extensionId && now - lastClickAt < doubleClickMs
        lastClickId = extensionId
        lastClickAt = now
        if (secondOfDouble) return Click.IGNORE
        val open = _state.value
        return when {
            open?.extensionId != extensionId -> Click.TRIGGER
            seen -> Click.CLOSE
            else -> Click.IGNORE
        }
    }

    /** The engine asks to toggle [extensionId]'s popup, after a click its button passed on. */
    fun toggle(extensionId: String): Toggle {
        val open = _state.value
        return when {
            open?.extensionId != extensionId -> Toggle.OPEN
            seen -> Toggle.CLOSE
            else -> Toggle.KEEP
        }
    }

    /** [session] is now [extensionId]'s popup; the one before must have been [close]d. */
    fun open(extensionId: String, extensionName: String, session: S): ExtensionPopupState {
        this.session = session
        seen = false
        opened++
        val popup = ExtensionPopupState(
            extensionId = extensionId,
            extensionName = extensionName,
            sessionId = "popup-$opened"
        )
        _state.value = popup
        return popup
    }

    /** Takes the popup off screen; returns its session, for the engine to close. */
    fun close(): S? {
        val closed = session
        session = null
        seen = false
        _state.value = null
        return closed
    }

    /** The screen showed the popup [sessionId]; an earlier popup's notice is ignored. */
    fun shown(sessionId: String) {
        if (_state.value?.sessionId == sessionId) seen = true
    }

    /** The size [extensionId]'s popup content asked for; a report for another popup is stale. */
    fun sized(extensionId: String, width: Int, height: Int) {
        val popup = _state.value ?: return
        if (popup.extensionId != extensionId) return
        _state.value = popup.copy(contentWidth = width, contentHeight = height)
    }

    /** Whether [session] is the open popup's: a request from one already replaced is stale. */
    fun isCurrent(session: S): Boolean = this.session === session
}
