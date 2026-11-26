package com.rembrowser.rembrowse.data

import android.content.Context
import org.json.JSONObject

/** What the add-on updater remembers between launches. */
interface AddonUpdatePreferences {
    /** Check for and install new versions of add-ons once a day. On unless switched off. */
    var updateAutomatically: Boolean

    /** When add-ons were last checked, automatically or when asked; null before the first check. */
    var lastCheckedMillis: Long?

    /**
     * Updates whose new permissions the user didn't allow, as add-on id to the version declined.
     * Automatic checks don't ask about that version again; "Check for updates" does.
     */
    var declinedUpdates: Map<String, String>
}

/** Remembers nothing beyond the process. */
class InMemoryAddonUpdatePreferences(
    override var updateAutomatically: Boolean = true,
    override var lastCheckedMillis: Long? = null,
    override var declinedUpdates: Map<String, String> = emptyMap()
) : AddonUpdatePreferences

class SharedPrefsAddonUpdatePreferences(context: Context) : AddonUpdatePreferences {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var updateAutomatically: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    override var lastCheckedMillis: Long?
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L }
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKED, value ?: 0L).apply()

    override var declinedUpdates: Map<String, String>
        get() = decodeDeclined(prefs.getString(KEY_DECLINED, null))
        set(value) = prefs.edit().putString(KEY_DECLINED, encodeDeclined(value)).apply()

    internal companion object {
        const val PREFS_NAME = "rembrowse_addon_updates"
        private const val KEY_AUTO = "update_automatically"
        private const val KEY_LAST_CHECKED = "last_checked_millis"
        private const val KEY_DECLINED = "declined_updates"

        /** `{"id": "version", …}` */
        fun encodeDeclined(declined: Map<String, String>): String = JSONObject(declined).toString()

        /** What [encodeDeclined] wrote; anything unreadable counts as nothing declined. */
        fun decodeDeclined(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val root = JSONObject(json)
                root.keys().asSequence()
                    .mapNotNull { id -> root.optString(id, "").takeIf { it.isNotEmpty() }?.let { id to it } }
                    .toMap()
            }.getOrDefault(emptyMap())
        }
    }
}
