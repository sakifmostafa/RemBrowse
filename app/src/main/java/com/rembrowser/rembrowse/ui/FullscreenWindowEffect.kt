package com.rembrowser.rembrowse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Runs [onEnter] when [fullscreen] becomes true, and [onExit] when it becomes false again or this
 * leaves the composition while it is true. Every enter is matched by exactly one exit.
 *
 * The value is fixed per effect on purpose. Reading a `by` state inside `onDispose` would see the
 * new value (false) by the time the old effect is disposed, and the exit would never run: the
 * window would stay without its system bars, and a desktop window stayed fullscreen.
 */
@Composable
fun FullscreenWindowEffect(fullscreen: Boolean, onEnter: () -> Unit, onExit: () -> Unit) {
    val latestOnEnter by rememberUpdatedState(onEnter)
    val latestOnExit by rememberUpdatedState(onExit)
    DisposableEffect(fullscreen) {
        val entered = fullscreen
        if (entered) latestOnEnter()
        onDispose {
            if (entered) latestOnExit()
        }
    }
}
