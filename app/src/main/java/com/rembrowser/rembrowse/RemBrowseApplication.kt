package com.rembrowser.rembrowse

import android.app.Application
import com.rembrowser.rembrowse.engine.GeckoBrowserEngine
import org.mozilla.geckoview.GeckoRuntime

class RemBrowseApplication : Application() {

    val geckoRuntime: GeckoRuntime by lazy {
        GeckoBrowserEngine.createRuntime(applicationContext)
    }
}
