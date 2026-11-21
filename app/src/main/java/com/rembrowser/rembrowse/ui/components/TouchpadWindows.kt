package com.rembrowser.rembrowse.ui.components

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.rembrowser.rembrowse.engine.TouchpadInputFilter

/**
 * Call from a dialog's content: the dialog's window gets the touchpad as a mouse, as the main
 * window does (MainActivity). A dialog is a window of its own, whose input never passes through
 * the activity, so without this a touchpad click in its text fields is a finger's tap: double
 * click, drag and triple click select nothing.
 */
@Composable
fun TouchpadAsMouseInDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null && original !is TouchpadWindowCallback) {
            val metrics = view.resources.displayMetrics
            window.callback = TouchpadWindowCallback(
                original,
                TouchpadInputFilter(
                    TouchpadInputFilter.scrollSlopPx(
                        dpi = maxOf(metrics.densityDpi.toFloat(), metrics.xdpi, metrics.ydpi),
                        touchSlopPx = ViewConfiguration.get(view.context).scaledTouchSlop
                    )
                )
            )
        }
        // The window, and the callback with it, goes when the dialog closes
        onDispose { }
    }
}

/** The dialog's own window callback, with its pointer input filtered first. */
private class TouchpadWindowCallback(
    private val original: Window.Callback,
    private val touchpad: TouchpadInputFilter
) : Window.Callback by original {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var handled = true
        touchpad.filter(event) { handled = original.dispatchTouchEvent(it) }
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        var handled = false
        TouchpadInputFilter.filterGeneric(event) { handled = original.dispatchGenericMotionEvent(it) }
        return handled
    }
}
