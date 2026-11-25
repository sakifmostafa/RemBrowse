package com.rembrowser.rembrowse.data

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import java.util.concurrent.Executor

/**
 * The site icons RemBrowse keeps so it need not fetch them again. Each is kept under its site's
 * address, so together they say which sites were visited, as the browsing history does.
 */
fun interface SiteIconCache {
    /** Forgets every icon kept. Returns at once; the icons on screen stay until they are next loaded. */
    fun clear()
}

/** Keeps nothing. Used where no icons are loaded (tests, previews). */
object NoSiteIconCache : SiteIconCache {
    override fun clear() = Unit
}

/**
 * The icons Coil keeps for [loader], on disk and in memory. Add-on icons are kept there too and
 * go with them; they are fetched again when next shown.
 *
 * Emptying the disk cache deletes files, so it happens on [worker], never on the caller's thread.
 */
class CoilSiteIconCache(
    private val loader: () -> ImageLoader,
    private val worker: Executor = Executor { Thread(it, "site-icon-cache").start() }
) : SiteIconCache {

    /** The cache of the app's own image loader (see RemBrowseApplication.newImageLoader). */
    constructor(context: Context) : this({ SingletonImageLoader.get(context.applicationContext) })

    override fun clear() {
        worker.execute {
            try {
                val loader = loader()
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't clear the site icons", e)
            }
        }
    }

    private companion object {
        const val TAG = "SiteIconCache"
    }
}
