package com.rembrowser.rembrowse.engine

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.view.children
import com.rembrowser.rembrowse.model.PageRendering
import org.mozilla.geckoview.GeckoView

private const val TAG = "PageSurface"

/**
 * Sets a web view that was just created up to show its page as [rendering] says. Call it once,
 * before the view gets a session. A view cannot change its mind later (GeckoView does not
 * listen to a second SurfaceView it is switched back to), so a new choice needs a new view.
 */
fun GeckoView.usePageRendering(rendering: PageRendering) {
    when (rendering) {
        // What a GeckoView is when it is created: nothing to set up
        PageRendering.DIRECT -> Unit
        PageRendering.STANDARD -> {
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            if (!setUpSurfaceOncePerTexture()) {
                Log.w(TAG, "This GeckoView has no TextureView to tame; pages are drawn as in 0.13.2")
            }
        }
        PageRendering.LEGACY -> setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
    }
}

/**
 * Stops a texture-backed GeckoView from setting its compositor up again for every frame.
 *
 * GeckoView listens to its TextureView and treats `onSurfaceTextureUpdated` like a new
 * surface. Android calls that for *every frame* the texture shows, on the main thread, so for
 * each frame of a scrolling page GeckoView 138 (`SurfaceViewWrapper.ListenerWrapper`, and
 * `GeckoSession.onSurfaceChanged` behind it):
 *  - wraps the texture in a new `Surface` and sends it to the GPU process;
 *  - makes a blocking call (`syncResumeResizeCompositor`) during which the compositor destroys
 *    its EGL surface, creates a new one for the same texture and renders a forced frame.
 *
 * None of that is needed. The texture, and so the surface, is the same one from
 * `onSurfaceTextureAvailable` until `onSurfaceTextureDestroyed`, and a new size is announced
 * by `onSurfaceTextureSizeChanged`. Those three still reach GeckoView; with them a texture
 * goes through exactly what a SurfaceView's surface does (created, resized, destroyed).
 *
 * @return false if the view has no TextureView with a listener: it is not texture-backed, or
 * a later GeckoView is built differently. Nothing is changed then.
 */
internal fun ViewGroup.setUpSurfaceOncePerTexture(): Boolean {
    val texture = children.filterIsInstance<TextureView>().firstOrNull() ?: return false
    val listener = texture.surfaceTextureListener ?: return false
    if (listener !is OncePerTextureListener) {
        texture.surfaceTextureListener = OncePerTextureListener(listener)
    }
    return true
}

/**
 * GeckoView asked for a new surface because its compositor could not use the one it had
 * (`GeckoView.requestNewSurface`). For a texture, GeckoView gets one only through the next frame
 * callback, so that one frame is let through.
 */
internal fun ViewGroup.letNextTextureFrameThrough() {
    val texture = children.filterIsInstance<TextureView>().firstOrNull() ?: return
    (texture.surfaceTextureListener as? OncePerTextureListener)?.letNextFrameThrough()
}

/**
 * Passes on everything about a texture's life to [delegate], and nothing about its frames,
 * except one when GeckoView asks for a new surface ([letNextFrameThrough]).
 */
internal class OncePerTextureListener(
    private val delegate: TextureView.SurfaceTextureListener
) : TextureView.SurfaceTextureListener {

    // Used on the main thread only, as the texture's callbacks are
    private var passNextFrame = false

    /** The next frame reaches GeckoView, which then sets its compositor up again on the texture. */
    fun letNextFrameThrough() {
        passNextFrame = true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
        delegate.onSurfaceTextureAvailable(surface, width, height)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
        delegate.onSurfaceTextureSizeChanged(surface, width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
        delegate.onSurfaceTextureDestroyed(surface)

    // A new frame is on screen. The surface it was drawn into has not changed, unless GeckoView
    // asked for it to be set up again.
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (passNextFrame) {
            passNextFrame = false
            delegate.onSurfaceTextureUpdated(surface)
        }
    }
}
