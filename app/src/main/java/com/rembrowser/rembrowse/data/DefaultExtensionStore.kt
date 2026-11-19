package com.rembrowser.rembrowse.data

import android.content.Context
import android.content.SharedPreferences

/** Remembers whether the browser has already set up its default extensions once. */
interface DefaultExtensionStore {
    /**
     * True once the default ad blocker has been installed successfully. From then on it is the
     * user's to keep or remove: it is never reinstalled behind their back.
     */
    var defaultBlockerProvisioned: Boolean
}

/** For tests and previews: behaves as if the defaults were set up long ago. */
object AlreadyProvisionedDefaults : DefaultExtensionStore {
    override var defaultBlockerProvisioned: Boolean
        get() = true
        set(_) = Unit
}

class SharedPrefsDefaultExtensionStore(
    private val prefs: SharedPreferences
) : DefaultExtensionStore {

    constructor(context: Context) : this(
        context.getSharedPreferences("rembrowse_defaults_v1", Context.MODE_PRIVATE)
    )

    override var defaultBlockerProvisioned: Boolean
        get() = prefs.getBoolean(KEY_BLOCKER, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKER, value).apply()

    private companion object {
        const val KEY_BLOCKER = "default_blocker_provisioned"
    }
}
