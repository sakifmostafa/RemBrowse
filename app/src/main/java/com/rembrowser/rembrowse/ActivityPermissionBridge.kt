package com.rembrowser.rembrowse

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.rembrowser.rembrowse.engine.AndroidPermissionRequester

private const val TAG = "ActivityPermissionBridge"

/**
 * Asks Android for runtime permissions through whichever [MainActivity] is on screen.
 *
 * Owned by [BrowserSessionHolder], not the activity, for the same reason as the file picker's
 * request: when the system re-creates the activity while the dialog is open, the answer reaches
 * the new activity, and the request it belongs to must still be here, neither lost nor asked twice.
 * The activity [attach]es its launcher while it is started and hands every result to [onResult].
 */
class ActivityPermissionBridge(context: Context) : AndroidPermissionRequester {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Request(val permissions: List<String>, val onResult: (Set<String>) -> Unit) {
        /** Withdrawn by whoever asked: no dialog for it, and no answer. */
        var cancelled = false
    }

    // Main thread only
    private val queue = ArrayDeque<Request>()

    /** The request the system dialog is open for; it may outlive the activity that opened it. */
    private var inFlight: Request? = null

    private var launcher: ((Array<String>) -> Unit)? = null

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    override fun request(
        permissions: List<String>,
        onResult: (granted: Set<String>) -> Unit
    ): AndroidPermissionRequester.Pending {
        val request = Request(permissions.distinct(), onResult)
        runOnMain {
            queue.addLast(request)
            launchNext()
        }
        return AndroidPermissionRequester.Pending {
            runOnMain {
                request.cancelled = true
                // A request waiting for the window to come back must not bring up a dialog then
                queue.remove(request)
            }
        }
    }

    /** The started activity offers [launch] to open the system dialog with. */
    fun attach(launch: (Array<String>) -> Unit) {
        launcher = launch
        launchNext()
    }

    /** The activity stopped; requests wait for the next one. */
    fun detach(launch: (Array<String>) -> Unit) {
        if (launcher === launch) launcher = null
    }

    /**
     * The system dialog closed. What the app holds is read afresh rather than from its answer,
     * which is empty when the dialog was interrupted.
     */
    fun onResult() {
        val request = inFlight ?: return
        inFlight = null
        if (!request.cancelled) request.onResult(grantedOf(request))
        launchNext()
    }

    private fun launchNext() {
        while (inFlight == null) {
            val launch = launcher ?: return
            val next = queue.removeFirstOrNull() ?: return
            val missing = next.permissions.filterNot(::isGranted)
            if (missing.isEmpty()) {
                next.onResult(grantedOf(next))
                continue
            }
            inFlight = next
            try {
                launch(missing.toTypedArray())
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't ask for permissions", e)
                inFlight = null
                next.onResult(grantedOf(next))
            }
        }
    }

    private fun grantedOf(request: Request): Set<String> = request.permissions.filter(::isGranted).toSet()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
