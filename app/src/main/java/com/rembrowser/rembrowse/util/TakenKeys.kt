package com.rembrowser.rembrowse.util

import android.view.KeyEvent

/**
 * Keys whose press the browser took for itself (a shortcut, or a key pressed while the screenshot
 * overlay is up), so that the auto-repeats and the release of that press are taken too, and
 * nothing after it: a page never sees half a key press, and the next press of the same key is the
 * page's again. Pure, so it is tested on the JVM; used on the main thread only.
 */
class TakenKeys {
    private val keys = HashSet<Int>()

    /** The press of [keyCode] (its first key-down) was taken. */
    fun take(keyCode: Int) {
        keys += keyCode
    }

    /**
     * Whether this key event belongs to a taken press and is taken with it: an auto-repeat, or the
     * release, which ends it. A fresh press of a key still marked (its release went missing) is a
     * new press, not taken here.
     */
    fun takesFollowUp(action: Int, keyCode: Int, repeatCount: Int): Boolean = when (action) {
        KeyEvent.ACTION_UP -> keys.remove(keyCode)
        KeyEvent.ACTION_DOWN -> if (repeatCount > 0) keyCode in keys else {
            keys.remove(keyCode)
            false
        }
        else -> false
    }
}
