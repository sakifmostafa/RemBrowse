package com.rembrowser.rembrowse.engine

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rembrowser.rembrowse.model.PageRendering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import org.robolectric.annotation.Config

/**
 * A page drawn into a texture must not have its compositor set up again for every frame it
 * shows: GeckoView does that when it hears `onSurfaceTextureUpdated`, which made scrolling
 * stutter. These run against the real GeckoView, so they also notice a GeckoView that is put
 * together differently after an upgrade.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PageSurfaceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class RecordingListener : TextureView.SurfaceTextureListener {
        val calls = mutableListOf<String>()

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            calls += "available ${width}x$height"
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            calls += "resized ${width}x$height"
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            calls += "destroyed"
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            calls += "frame"
        }
    }

    private fun textureOf(view: GeckoView): TextureView? = view.children.filterIsInstance<TextureView>().firstOrNull()

    @Test
    fun aNewFrameIsNotPassedOn_butTheTexturesLifeIs() {
        val gecko = RecordingListener()
        val listener = OncePerTextureListener(gecko)
        val texture = SurfaceTexture(0)

        listener.onSurfaceTextureAvailable(texture, 800, 600)
        repeat(120) { listener.onSurfaceTextureUpdated(texture) }
        listener.onSurfaceTextureSizeChanged(texture, 400, 600)
        repeat(120) { listener.onSurfaceTextureUpdated(texture) }
        val released = listener.onSurfaceTextureDestroyed(texture)

        assertEquals(listOf("available 800x600", "resized 400x600", "destroyed"), gecko.calls)
        assertFalse("GeckoView's answer is passed back: it does not want the texture released for it", released)
    }

    @Test
    fun standardRendering_isATextureThatGeckoViewHearsOfOnlyWhenItChanges() {
        val view = GeckoView(context).apply { usePageRendering(PageRendering.STANDARD) }

        val texture = textureOf(view)!!
        assertTrue(texture.surfaceTextureListener is OncePerTextureListener)
        assertEquals("One view shows the page", 1, view.childCount)

        // Frames arriving before the view has a session or a surface do nothing at all
        texture.surfaceTextureListener!!.onSurfaceTextureUpdated(SurfaceTexture(0))
    }

    /** The surface GeckoView would hand its compositor now (`SurfaceViewWrapper.getSurface`). */
    private fun compositorSurfaceOf(view: GeckoView): Any? {
        val wrapper = GeckoView::class.java.getDeclaredField("mSurfaceWrapper").apply { isAccessible = true }.get(view)
        return wrapper.javaClass.getMethod("getSurface").invoke(wrapper)
    }

    @Test
    fun geckoViewTakesEveryFrameForANewSurface_andStandardRenderingStopsThat() {
        val frames = SurfaceTexture(0)

        // As GeckoView comes: each frame shown replaces the surface, and with a session the
        // compositor is resumed on it (GeckoSession.onSurfaceChanged)
        val legacy = GeckoView(context).apply { usePageRendering(PageRendering.LEGACY) }
        val legacyListener = textureOf(legacy)!!.surfaceTextureListener!!
        legacyListener.onSurfaceTextureAvailable(frames, 800, 600)
        val legacySurface = compositorSurfaceOf(legacy)
        legacyListener.onSurfaceTextureUpdated(frames)
        assertFalse("This is what scrolling paid for", legacySurface === compositorSurfaceOf(legacy))

        val standard = GeckoView(context).apply { usePageRendering(PageRendering.STANDARD) }
        val standardListener = textureOf(standard)!!.surfaceTextureListener!!
        standardListener.onSurfaceTextureAvailable(frames, 800, 600)
        val surface = compositorSurfaceOf(standard)
        assertTrue("GeckoView got the texture's surface", surface != null)
        repeat(3) { standardListener.onSurfaceTextureUpdated(frames) }
        assertSame("One surface for as long as the texture lives", surface, compositorSurfaceOf(standard))
    }

    @Test
    fun legacyRendering_leavesGeckoViewsOwnListenerInPlace() {
        val view = GeckoView(context).apply { usePageRendering(PageRendering.LEGACY) }

        val listener = textureOf(view)!!.surfaceTextureListener!!
        assertFalse(listener is OncePerTextureListener)
        // The listener that is replaced for standard rendering is GeckoView's own
        assertTrue(listener.javaClass.name, listener.javaClass.name.startsWith("org.mozilla.gecko."))
    }

    @Test
    fun directRendering_keepsTheSurfaceViewGeckoViewStartsWith() {
        val untouched = GeckoView(context)
        val before = untouched.getChildAt(0)

        untouched.usePageRendering(PageRendering.DIRECT)

        // Not replaced: GeckoView only listens to the SurfaceView it created itself
        assertSame(before, untouched.getChildAt(0))
        assertTrue(before is SurfaceView)
        assertEquals(null, textureOf(untouched))
    }

    @Test
    fun whenGeckoViewAsksForANewSurface_theNextFrameIsLetThrough_once() {
        val gecko = RecordingListener()
        val listener = OncePerTextureListener(gecko)
        val texture = SurfaceTexture(0)

        listener.onSurfaceTextureUpdated(texture)
        listener.letNextFrameThrough()
        repeat(3) { listener.onSurfaceTextureUpdated(texture) }

        assertEquals("Exactly the frame after the request", listOf("frame"), gecko.calls)
    }

    @Test
    fun aPanesWebView_letsAFrameThroughWhenItsCompositorAsksForANewSurface() {
        val view = PaneGeckoView(context).apply { usePageRendering(PageRendering.STANDARD) }
        val texture = textureOf(view)!!
        val wrapper = texture.surfaceTextureListener as OncePerTextureListener
        val gecko = RecordingListener()
        // Watch what reaches GeckoView's own listener, through the same wrapper
        val watched = OncePerTextureListener(gecko)
        texture.surfaceTextureListener = watched

        view.requestNewSurface()
        watched.onSurfaceTextureUpdated(SurfaceTexture(0))

        assertEquals(listOf("frame"), gecko.calls)
        assertTrue(wrapper !== watched)
    }

    @Test
    fun thePanesOwnWebView_takesTheSameSetUp() {
        val view = PaneGeckoView(context) { false }.apply { usePageRendering(PageRendering.STANDARD) }

        assertTrue(textureOf(view)!!.surfaceTextureListener is OncePerTextureListener)
    }

    @Test
    fun settingUpTwice_wrapsTheListenerOnce() {
        val view = GeckoView(context).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
        val geckoListener = textureOf(view)!!.surfaceTextureListener

        assertTrue(view.setUpSurfaceOncePerTexture())
        val wrapped = textureOf(view)!!.surfaceTextureListener
        assertTrue(view.setUpSurfaceOncePerTexture())

        assertSame(wrapped, textureOf(view)!!.surfaceTextureListener)
        assertFalse(wrapped === geckoListener)
    }

    @Test
    fun aViewWithoutATexture_isLeftAlone() {
        assertFalse(FrameLayout(context).setUpSurfaceOncePerTexture())
        assertFalse("A SurfaceView-backed GeckoView", GeckoView(context).setUpSurfaceOncePerTexture())

        val bare = FrameLayout(context).apply { addView(TextureView(context)) }
        assertFalse("A texture nobody listens to", bare.setUpSurfaceOncePerTexture())
    }
}
