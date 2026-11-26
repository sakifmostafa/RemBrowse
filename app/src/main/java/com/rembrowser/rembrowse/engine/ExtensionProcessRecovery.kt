package com.rembrowser.rembrowse.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.mozilla.geckoview.WebExtensionController

/**
 * Turns the engine's extension process back on after the engine has stopped restarting it.
 *
 * Every extension's background runs in one process of its own. When that process ends
 * abnormally (it crashed, or Android killed it for memory), Gecko starts it again the next time
 * an extension needs it, but once it has ended more than 5 times within 30 seconds
 * (`extensions.webextensions.crash.threshold` and `.timeframe`, `ExtensionProcessCrashObserver`)
 * Gecko stops, tells the app (`ExtensionProcessDelegate.onDisabledProcessSpawning`) and waits for
 * the app to allow it again (`WebExtensionController.enableExtensionProcessSpawning`). Until
 * then no extension's background runs: a VPN extension such as Proton VPN no longer routes or
 * reconnects, an ad blocker no longer blocks, and a popup that asks its background for anything
 * gets no answer. RemBrowse never answered, so this lasted until RemBrowse itself was closed.
 *
 * A VPN extension makes such a run of ends likely while RemBrowse is in the background: Proton
 * VPN's background wakes for every request a page makes (`proxy.onRequest`), and RemBrowse keeps
 * pages running when minimised, so each time Android kills the extension process a page's next
 * request starts it again, only for it to be killed again.
 *
 * So the process is turned back on by itself, as follows:
 * - **No window on screen**: the ends are most likely Android taking memory back from an app in
 *   the background. Waits until a window is shown again, then turns it on at once.
 * - **A window on screen**: turns it on after a wait from [RETRY_DELAYS_MS]: 5 seconds the first
 *   time. Each time it is turned off again within [STAYED_UP_MS] of being turned on, the process
 *   really keeps failing, and the next wait is longer: 1, 5 and 15 minutes, then every 30 minutes.
 *   Once it has stayed on for [STAYED_UP_MS], the waits start again from 5 seconds. A window can
 *   stay on screen for hours on a laptop, so it never just waits for the next return to the app;
 *   a crash loop costs one burst of restarts per retry, at most one every half hour.
 * - A window shown again while it waits turns it on at once, as before.
 *
 * Main thread only: Gecko's delegate is called there (and [connect] posts it there to be sure),
 * and so are the window callbacks.
 */
internal class ExtensionProcessRecovery(
    private val enableSpawning: () -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Cancellable,
    private val now: () -> Long
) {
    fun interface Cancellable {
        fun cancel()
    }

    private var windowsShown = 0
    private var spawningOff = false
    private var lastTurnedOnAt: Long? = null
    private var pendingRetry: Cancellable? = null

    // How many times in a row it was turned off again soon after being turned on
    private var quickFailures = 0

    /** Whether the engine has stopped starting the extension process and is waiting for this. */
    val isWaiting: Boolean get() = spawningOff

    /** A RemBrowse window came on screen (an activity started). */
    fun onWindowShown() {
        windowsShown++
        if (windowsShown == 1 && spawningOff) turnOn()
    }

    /** A RemBrowse window left the screen (an activity stopped). */
    fun onWindowHidden() {
        if (windowsShown > 0) windowsShown--
        if (windowsShown == 0) cancelRetry()
    }

    /** The engine stopped starting the extension process ([WebExtensionController.ExtensionProcessDelegate]). */
    fun onSpawningDisabled() {
        spawningOff = true
        cancelRetry()
        val last = lastTurnedOnAt
        quickFailures = if (last != null && now() - last < STAYED_UP_MS) quickFailures + 1 else 0
        if (windowsShown == 0) return
        pendingRetry = schedule(retryDelayMs(quickFailures)) {
            pendingRetry = null
            if (spawningOff && windowsShown > 0) turnOn()
        }
    }

    private fun turnOn() {
        cancelRetry()
        spawningOff = false
        lastTurnedOnAt = now()
        enableSpawning()
    }

    private fun cancelRetry() {
        pendingRetry?.cancel()
        pendingRetry = null
    }

    companion object {
        private const val TAG = "ExtensionProcess"

        /**
         * How long a window on screen waits before the process is turned back on: the first entry
         * the first time, the next one each time it is turned off again soon after ([STAYED_UP_MS]),
         * and the last one from then on.
         */
        val RETRY_DELAYS_MS = listOf(5_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

        /** Turned on for this long without being turned off again, it counts as recovered. */
        const val STAYED_UP_MS = 10 * 60_000L

        /** The wait before the next try, after [quickFailures] quick failures in a row. */
        fun retryDelayMs(quickFailures: Int): Long =
            RETRY_DELAYS_MS[quickFailures.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]

        /**
         * Watches [application]'s windows from now on. Call it from `Application.onCreate`, before
         * any activity starts, so none is missed; [connect] hands it the engine later.
         */
        fun watchWindows(application: Application, recovery: ExtensionProcessRecovery) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = recovery.onWindowShown()
                override fun onActivityStopped(activity: Activity) = recovery.onWindowHidden()
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }

        /** A recovery on the main thread, turning on [controller]'s extension process. */
        fun forMainThread(controller: () -> WebExtensionController?): ExtensionProcessRecovery {
            val handler = Handler(Looper.getMainLooper())
            return ExtensionProcessRecovery(
                enableSpawning = {
                    Log.w(TAG, "Turning the extension process back on")
                    controller()?.enableExtensionProcessSpawning()
                },
                schedule = { delayMs, action ->
                    val runnable = Runnable(action)
                    handler.postDelayed(runnable, delayMs)
                    Cancellable { handler.removeCallbacks(runnable) }
                },
                now = SystemClock::elapsedRealtime
            )
        }

        /** Has [controller] report to [recovery] when the engine stops starting the extension process. */
        fun connect(controller: WebExtensionController, recovery: ExtensionProcessRecovery) {
            val handler = Handler(Looper.getMainLooper())
            controller.setExtensionProcessDelegate(object : WebExtensionController.ExtensionProcessDelegate {
                override fun onDisabledProcessSpawning() {
                    Log.w(TAG, "The engine stopped restarting the extension process after repeated ends")
                    handler.post { recovery.onSpawningDisabled() }
                }
            })
        }
    }
}
