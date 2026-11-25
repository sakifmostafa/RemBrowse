// In GeckoView's package on purpose: the engine tells a page's size only to its own classes
// (GeckoSession.Window.requestContentMetrics, which its own captureFullPage asks first). Nothing
// else belongs here. A GeckoView update that changes these names fails the build, not the app.
package org.mozilla.geckoview

import androidx.annotation.UiThread

/**
 * How large a page is, all of it, scrolled-out parts included: [width] by [height] CSS pixels,
 * each drawn as [devicePixelRatio] pixels on this screen.
 */
class PageContentSize(val width: Int, val height: Int, val devicePixelRatio: Float)

/**
 * Asks the engine how large the page in this session is, without drawing it. The result fails
 * when the session is closed or the engine can't say.
 */
@UiThread
fun GeckoSession.requestPageContentSize(): GeckoResult<PageContentSize> {
    val window = mWindow
    if (!isOpen || window == null) {
        return GeckoResult.fromException(IllegalStateException("The session is closed"))
    }
    val asked = GeckoResult<GeckoSession.Window.ContentMetrics>()
    window.requestContentMetrics(asked, GeckoSession.Window.ContentMetrics())
    return asked.map { metrics ->
        checkNotNull(metrics) { "The engine gave no size" }
        PageContentSize(metrics.width, metrics.height, metrics.devicePixelRatio)
    }
}
