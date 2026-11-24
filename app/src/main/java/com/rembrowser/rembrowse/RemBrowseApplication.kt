package com.rembrowser.rembrowse

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import com.rembrowser.rembrowse.engine.GeckoSiteNotifications
import org.mozilla.geckoview.GeckoRuntime

class RemBrowseApplication : Application() {

    val geckoRuntime: GeckoRuntime by lazy {
        GeckoBrowserEngine.createRuntime(applicationContext).also { runtime ->
            // Sites' notifications become Android ones, for as long as the runtime runs; a tap
            // comes back to MainActivity
            runtime.webNotificationDelegate = GeckoSiteNotifications(applicationContext, MainActivity::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything is composed: the flags are read as input and focus events arrive
        keepComposeInputAsTested()
    }
}

/**
 * Holds Compose's touchpad and focus handling to what RemBrowse was built and tried with.
 *
 * Compose 1.11 (BOM 2026.06.01) switches newer touchpad and focus handling on by default, where
 * Compose 1.7 had none of it. RemBrowse's touchpad handling (TouchpadInputFilter, the two-finger
 * swipe back and forward, touchpad windows) and the focus hand-over between Compose and the page
 * and terminal views were tuned against Compose 1.7, and the new handling has not been tried on
 * a touchpad and keyboard device yet. Each flag below is one whose 1.11 default differs from
 * 1.7; the focus flags that 1.11 leaves off (isViewFocusFixEnabled,
 * isInitialFocusOnFocusableAvailable, isFocusRestorationEnabled) are left alone.
 *
 * Turn one back on only after trying touchpad and keyboard use on a real device (a Googlebook):
 * two-finger scrolling and swipes over pages, the tab rail and lists, Tab and arrow keys through
 * the toolbar, and focus moving into and out of a page and a terminal.
 *
 * Called from [RemBrowseApplication.onCreate]; the tests call it directly.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
internal fun keepComposeInputAsTested() {
    // Android 14+ touchpad gestures (two-finger swipe, pinch) as pan and zoom events instead of
    // the pretend finger they arrive as, which TouchpadInputFilter and the swipe navigator expect
    ComposeUiFlags.isTrackpadGestureHandlingEnabled = false
    ComposeFoundationFlags.isTrackpadGestureHandlingEnabled = false
    // Swipes on a touch navigation pad (SOURCE_TOUCH_NAVIGATION) moving focus
    ComposeUiFlags.isIndirectPointerNavigationGestureDetectorEnabled = false
    // A Compose view with nothing focusable of its own left out of Android's focus search, and
    // Tab handled differently between Compose and the views inside it (pages, terminals)
    ComposeUiFlags.isBypassUnfocusableComposeViewEnabled = false
    // A different path for sending focus changes to onFocusChanged and onFocusEvent
    ComposeUiFlags.isOptimizedFocusEventDispatchEnabled = false
}
