package com.rembrowser.rembrowse.util

import android.view.KeyEvent

/** A web page view that can take keys past the keyboard app (PaneGeckoView). */
interface PageKeyTarget {
    /** Whether a key can go past the keyboard app right now (see PaneGeckoView). */
    fun takesKeysPastKeyboardApp(enter: Boolean): Boolean

    /** Sends the key to the page; false if it couldn't. */
    fun sendKeyPastKeyboardApp(event: KeyEvent): Boolean
}

/**
 * Routes a physical keyboard's keys to the focused web page before the keyboard app sees it (see
 * [PhysicalTyping] for which: Enter). A key taken on its press is followed by its repeats and
 * its release, to the same page even if focus has moved since; a key not taken is left alone
 * throughout, so a page never gets half a key press.
 *
 * Used on the main thread only, from MainActivity's pre-keyboard-app layer.
 */
class PhysicalKeyRouter {
    private val taken = HashMap<Int, PageKeyTarget>()

    /**
     * Handles [event]; true if it was taken. [focusedPage] is the web page with the focus, if any.
     * [swallowEnterRelease] is asked once for an Enter release: true when that Enter submitted the
     * address bar or a popup and its release must reach nothing (focus has moved to the page).
     */
    fun route(event: KeyEvent, focusedPage: PageKeyTarget?, swallowEnterRelease: () -> Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_UP && PhysicalTyping.isEnter(event.keyCode) && swallowEnterRelease()) {
            taken.remove(event.keyCode)
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_UP -> {
                val target = taken.remove(event.keyCode) ?: return false
                target.sendKeyPastKeyboardApp(event)
                return true
            }
            KeyEvent.ACTION_DOWN -> Unit
            else -> return false
        }
        if (event.repeatCount > 0) return taken[event.keyCode]?.sendKeyPastKeyboardApp(event) ?: false
        val page = focusedPage ?: return false
        if (!PhysicalTyping.isPhysical(event.deviceId, event.flags)) return false
        if (!PhysicalTyping.goesStraightToPage(event.keyCode, event.unicodeChar, event.isCtrlPressed, event.isMetaPressed)) {
            return false
        }
        if (!page.takesKeysPastKeyboardApp(enter = PhysicalTyping.isEnter(event.keyCode))) return false
        if (!page.sendKeyPastKeyboardApp(event)) return false
        taken[event.keyCode] = page
        return true
    }
}
