package com.rembrowser.rembrowse

import android.app.Application
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.rembrowser.rembrowse.engine.ExtensionProcessRecovery
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import com.rembrowser.rembrowse.engine.GeckoEngineFetch
import com.rembrowser.rembrowse.engine.GeckoSiteNotifications
import com.rembrowser.rembrowse.engine.iconImageLoader
import java.io.IOException
import org.mozilla.geckoview.GeckoRuntime

class RemBrowseApplication : Application(), SingletonImageLoader.Factory {

    // Turns extensions back on after the engine stops restarting their process (see the class)
    private val extensionProcessRecovery: ExtensionProcessRecovery = ExtensionProcessRecovery.forMainThread {
        if (runtimeOnce.isInitialized()) geckoRuntime.webExtensionController else null
    }

    private val runtimeOnce: Lazy<GeckoRuntime> = lazy {
        GeckoBrowserEngine.createRuntime(applicationContext).also { runtime ->
            // Sites' notifications become Android ones, for as long as the runtime runs; a tap
            // comes back to MainActivity
            runtime.webNotificationDelegate = GeckoSiteNotifications(applicationContext, MainActivity::class.java)
            ExtensionProcessRecovery.connect(runtime.webExtensionController, extensionProcessRecovery)
            // Since GeckoView 150 the runtime no longer starts a content process by itself, so
            // the first page would wait for one. Starting it here, while the window is still being
            // built, keeps that wait out of a cold start (emulator, launch to the first page drawn:
            // median 2.41 s with it, 2.86 s without). The runtime is first asked for on the main
            // thread, as warmUp needs.
            runtime.warmUp()
        }
    }

    val geckoRuntime: GeckoRuntime by runtimeOnce

    override fun onCreate() {
        super.onCreate()
        // Before anything is composed: the flags are read as input and focus events arrive
        keepComposeInputAsTested()
        // Before any window starts, so the recovery knows when one is on screen
        ExtensionProcessRecovery.watchWindows(this, extensionProcessRecovery)
    }

    /**
     * The image loader for site and add-on icons, which fetches them through the engine (see
     * iconImageLoader). Coil asks for it when the first icon is shown, which only the browser's
     * window does. An icon never starts the engine: it uses the runtime the window started (before
     * anything is drawn), and fails without one. So Gecko's own processes, which start this class
     * too, can never create a runtime through it.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        iconImageLoader(context, GeckoEngineFetch {
            if (runtimeOnce.isInitialized()) geckoRuntime else throw IOException("The engine isn't running")
        })
}

/**
 * Holds Compose's touchpad and focus handling, where Compose still lets an app choose, to what
 * RemBrowse was built and tried with.
 *
 * Compose 1.11 (BOM 2026.06.01) switched newer touchpad and focus handling on by default, where
 * Compose 1.7 had none of it. RemBrowse's touchpad handling (TouchpadInputFilter, the two-finger
 * swipe back and forward, touchpad windows) and the focus hand-over between Compose and the page
 * and terminal views were tuned against Compose 1.7, and the new handling has not been tried on
 * a touchpad and keyboard device yet, so the switches for it were turned off here.
 *
 * Compose 1.13 (BOM 2026.11.01) removed four of those five switches and always does what they
 * turned on:
 * - ComposeUiFlags and ComposeFoundationFlags.isTrackpadGestureHandlingEnabled: Android 14+
 *   touchpad gestures (two-finger swipe, pinch) reach Compose as pan and zoom events instead
 *   of the pretend finger they arrive as. Compose 1.13 adds two refinements of that handling,
 *   isTrackpadPanHoverFixEnabled and isTrackpadPinchReinterpretationEnabled, on by default and
 *   left so. RemBrowse's own touchpad code reads Android's events: TouchpadInputFilter in the
 *   activity, before Compose; a page's view through Compose's view interop, so whether a page
 *   still gets a two-finger swipe exactly as before is for the device trial below.
 * - ComposeUiFlags.isIndirectPointerNavigationGestureDetectorEnabled: swipes on a touch
 *   navigation pad (SOURCE_TOUCH_NAVIGATION, not a laptop's touchpad) move focus.
 * - ComposeUiFlags.isOptimizedFocusEventDispatchEnabled: the newer path for sending focus
 *   changes to onFocusChanged and onFocusEvent is the only one.
 *
 * The one still there is turned off below. The focus flags that Compose leaves off
 * (isViewFocusFixEnabled, isInitialFocusOnFocusableAvailable, isFocusRestorationEnabled) are
 * left alone. Turn it back on only after trying touchpad and keyboard use on a real device (a
 * Googlebook): Tab and arrow keys through the toolbar, and focus moving into and out of a page
 * and a terminal. The same trial is what the handling Compose 1.13 always uses still needs:
 * two-finger scrolling and swipes over pages, the tab rail and lists.
 *
 * Called from [RemBrowseApplication.onCreate]; the tests call it directly.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun keepComposeInputAsTested() {
    // A Compose view with nothing focusable of its own left out of Android's focus search, and
    // Tab handled differently between Compose and the views inside it (pages, terminals)
    ComposeUiFlags.isBypassUnfocusableComposeViewEnabled = false
}
