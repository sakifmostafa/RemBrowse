package com.rembrowser.rembrowse.engine

/**
 * Asks Android for the app's own runtime permissions, such as the camera, the microphone,
 * location or posting notifications. A site's permission is worth nothing while the app itself
 * lacks the matching one, and the system dialog needs an activity on screen to appear over, so the
 * app provides this through its activity (see `ActivityPermissionBridge`).
 */
interface AndroidPermissionRequester {
    /** Whether the app holds [permission] right now. */
    fun isGranted(permission: String): Boolean

    /**
     * Asks for [permissions], showing the system dialog for those the app does not hold yet, and
     * calls [onResult] on the main thread with the ones it holds afterwards. Requests queue, so
     * only one dialog is open at a time; one made while no window is showing waits for it.
     *
     * The returned [Pending] withdraws a request nobody needs any more (its tab closed, say):
     * one still waiting is dropped without a dialog and [onResult] is not called.
     */
    fun request(permissions: List<String>, onResult: (granted: Set<String>) -> Unit): Pending

    /** A request made with [request]. */
    fun interface Pending {
        /** Drops the request if its dialog has not been shown yet. A dialog already open stays. */
        fun cancel()
    }
}
