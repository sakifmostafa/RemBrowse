package com.rembrowser.rembrowse

import android.app.Application
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
}
