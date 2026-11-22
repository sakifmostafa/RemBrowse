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

    /**
     * True once a copy set up with AdGuard AdBlocker has moved to uBlock Origin, or never needed
     * to (a new install, or a user who had switched blocking off). Done at most once.
     */
    var blockerMovedToUblock: Boolean
}

/** For tests and previews: behaves as if the defaults were set up long ago. */
object AlreadyProvisionedDefaults : DefaultExtensionStore {
    override var defaultBlockerProvisioned: Boolean
        get() = true
        set(_) = Unit
    override var blockerMovedToUblock: Boolean
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

    override var blockerMovedToUblock: Boolean
        get() = prefs.getBoolean(KEY_MOVED_TO_UBLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_MOVED_TO_UBLOCK, value).apply()

    private companion object {
        const val KEY_BLOCKER = "default_blocker_provisioned"
        const val KEY_MOVED_TO_UBLOCK = "blocker_moved_to_ublock"
    }
}
