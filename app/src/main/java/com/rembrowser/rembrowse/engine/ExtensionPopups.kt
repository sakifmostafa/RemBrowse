package com.rembrowser.rembrowse.engine

import com.rembrowser.rembrowse.model.ExtensionPopupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * The extension popup on screen, what a click on an extension's button does to it, and the size
 * its content asks for (see [sized]).
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

    // The open popup's width and height as its content last reported them (see sized)
    private val reportedWidth = ReportedLength()
    private val reportedHeight = ReportedLength()

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
        reportedWidth.reset()
        reportedHeight.reset()
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
        reportedWidth.reset()
        reportedHeight.reset()
        _state.value = null
        return closed
    }

    /** The screen showed the popup [sessionId]; an earlier popup's notice is ignored. */
    fun shown(sessionId: String) {
        if (_state.value?.sessionId == sessionId) seen = true
    }

    /**
     * The size the open popup's content asked for ([report]); a report for another popup is
     * stale.
     *
     * A width or height that changed by as much as the viewport it was measured in, and no
     * more, usually keeps the size the popup has. Content sized from the viewport (100vh, or 100%
     * of a root as tall as the viewport) measures the viewport plus what sits around it, such as
     * the body's margin. The viewport itself can measure more than the popup: on screens whose
     * density the engine rounds (its CSS pixel a fraction smaller than a dp), and when it counts a
     * keyboard over the page. Taken as asked, every resize made to fit such content made it ask
     * for more again, and the popup crept up the screen to its largest size.
     *
     * Such a change is still taken once right after the content changed by itself (the first
     * report counts as that): a list whose rows appear one at a time can grow by a row just as
     * the popup grows by the row before, which reads the same. So content sized from the viewport
     * settles within two resizes of opening, or of a change of its own. Changes in the same
     * viewport, such as a list opening, are always taken; only a run of equal steps, each measured
     * just as the popup grew by the step before, can leave the popup a step short until the
     * content next changes. A report that brings nothing new for a dimension (it is for the
     * other one) leaves that dimension as it is.
     */
    fun sized(report: PopupContentSize) {
        val popup = _state.value ?: return
        if (popup.extensionId != report.extensionId) return
        _state.value = popup.copy(
            contentWidth = reportedWidth.take(report.width, report.viewportWidth, popup.contentWidth),
            contentHeight = reportedHeight.take(report.height, report.viewportHeight, popup.contentHeight)
        )
    }

    /** Whether [session] is the open popup's: a request from one already replaced is stale. */
    fun isCurrent(session: S): Boolean = this.session === session

    /** One of the open popup's dimensions as its content reports it; see [sized]. */
    private class ReportedLength {
        private var last: Int? = null
        private var lastViewport: Int? = null

        // Whether the last report changed by more than its viewport did; the first one counts
        private var lastChangedByItself = false

        /** The length the popup takes for [length], measured in [viewport], when it has [current]. */
        fun take(length: Int, viewport: Int?, current: Int?): Int {
            val before = last
            // Nothing new here, give or take rounding: the report is for a change in the other
            // dimension. The popup keeps what it has, which may be less than this length, if that
            // length only followed the viewport, and taking it now would let it grow a step with
            // every such report.
            if (before != null && abs(length - before) <= FOLLOW_TOLERANCE_PX && sameViewport(lastViewport, viewport)) {
                return current ?: length
            }
            val keep = before != null &&
                !lastChangedByItself &&
                followsViewport(before, lastViewport, length, viewport)
            lastChangedByItself = before == null || changedByItself(before, lastViewport, length, viewport)
            last = length
            lastViewport = viewport
            return if (keep) current ?: length else length
        }

        fun reset() {
            last = null
            lastViewport = null
            lastChangedByItself = false
        }
    }

    internal companion object {
        /**
         * The smallest change the popup helper reports (MIN_CHANGE_PX in popupViewport.js): a
         * viewport that moved this much has moved.
         */
        private const val MIN_CHANGE_PX = 2

        /**
         * How far a size's change may stray from its viewport's and still only follow it: the
         * helper rounds sizes up to whole pixels, and the viewport is rounded too.
         */
        private const val FOLLOW_TOLERANCE_PX = 2

        /**
         * Whether a size measured as [before] in a viewport of [beforeViewport] and then as [now]
         * in [nowViewport] changed only because the viewport did. Reports without a viewport (a
         * popup with a viewport tag of its own) never compare.
         */
        fun followsViewport(before: Int, beforeViewport: Int?, now: Int, nowViewport: Int?): Boolean {
            if (beforeViewport == null || nowViewport == null) return false
            val viewportChange = nowViewport - beforeViewport
            return abs(viewportChange) >= MIN_CHANGE_PX &&
                abs((now - before) - viewportChange) <= FOLLOW_TOLERANCE_PX
        }

        /**
         * Whether two reports' viewports are the same, give or take what the helper ignores.
         * Reports without one (a popup with a viewport tag of its own) are taken as they come.
         */
        fun sameViewport(before: Int?, now: Int?): Boolean =
            before != null && now != null && abs(now - before) < MIN_CHANGE_PX

        /**
         * Whether a size measured as [before] and then as [now] changed by more than its viewport
         * did (by all of it, when the reports carry no viewport): the content changed by itself.
         */
        fun changedByItself(before: Int, beforeViewport: Int?, now: Int, nowViewport: Int?): Boolean {
            val change = now - before
            val viewportChange = if (beforeViewport != null && nowViewport != null) nowViewport - beforeViewport else 0
            return abs(change) >= MIN_CHANGE_PX && abs(change - viewportChange) > FOLLOW_TOLERANCE_PX
        }
    }
}
