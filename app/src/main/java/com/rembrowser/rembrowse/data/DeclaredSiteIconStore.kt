package com.rembrowser.rembrowse.data

import android.content.Context
import android.util.Log
import com.rembrowser.rembrowse.model.DeclaredSiteIcon
import com.rembrowser.rembrowse.model.SiteIcons
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers, between launches, the icons each site visited names for itself (see
 * [com.rembrowser.rembrowse.model.SiteIcons]), by origin. Like the site icons kept on disk, they
 * say which sites were visited, so they are kept only with the browsing history and go with it.
 */
interface DeclaredSiteIconStore {
    fun load(): Map<String, List<DeclaredSiteIcon>>
    fun save(sites: Map<String, List<DeclaredSiteIcon>>)
}

/** Keeps nothing. Used where nothing needs to outlive the process (tests, previews). */
object NoDeclaredSiteIcons : DeclaredSiteIconStore {
    override fun load(): Map<String, List<DeclaredSiteIcon>> = emptyMap()
    override fun save(sites: Map<String, List<DeclaredSiteIcon>>) = Unit
}

/**
 * Stores the sites' icons as JSON in their own preferences file, in the order the sites were
 * last heard from. Writes happen on a background thread, and a burst of them is written once,
 * with the newest map.
 */
class SharedPrefsDeclaredSiteIconStore(
    context: Context,
    private val writer: Executor = Executors.newSingleThreadExecutor()
) : DeclaredSiteIconStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pending = AtomicReference<Map<String, List<DeclaredSiteIcon>>?>(null)

    override fun load(): Map<String, List<DeclaredSiteIcon>> {
        val raw = prefs.getString(KEY_SITES, null) ?: return emptyMap()
        return try {
            fromJson(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable site icons", e)
            emptyMap()
        }
    }

    override fun save(sites: Map<String, List<DeclaredSiteIcon>>) {
        // Only the first of several quick saves schedules a write; it writes the latest map
        if (pending.getAndSet(sites) == null) {
            writer.execute {
                val latest = pending.getAndSet(null) ?: return@execute
                val editor = prefs.edit()
                if (latest.isEmpty()) editor.remove(KEY_SITES) else editor.putString(KEY_SITES, toJson(latest))
                editor.commit()
            }
        }
    }

    internal companion object {
        const val TAG = "DeclaredSiteIcons"
        const val PREFS_NAME = "rembrowse_site_icons"
        const val KEY_SITES = "sites"

        fun toJson(sites: Map<String, List<DeclaredSiteIcon>>): String {
            val array = JSONArray()
            sites.forEach { (origin, icons) ->
                val list = JSONArray()
                icons.forEach { icon ->
                    list.put(
                        JSONObject().apply {
                            put("url", icon.url)
                            if (icon.isTouchIcon) put("touch", true)
                            if (icon.sizes.isNotEmpty()) put("sizes", JSONArray(icon.sizes))
                        }
                    )
                }
                array.put(JSONObject().put("site", origin).put("icons", list))
            }
            return array.toString()
        }

        fun fromJson(raw: String): Map<String, List<DeclaredSiteIcon>> {
            val array = JSONArray(raw)
            val sites = LinkedHashMap<String, List<DeclaredSiteIcon>>()
            for (index in 0 until array.length()) {
                val site = array.optJSONObject(index) ?: continue
                val origin = site.optString("site").takeIf { it.isNotBlank() } ?: continue
                val list = site.optJSONArray("icons") ?: continue
                val icons = (0 until list.length()).mapNotNull { i ->
                    val icon = list.optJSONObject(i) ?: return@mapNotNull null
                    val url = icon.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val sizes = icon.optJSONArray("sizes")?.let { s -> (0 until s.length()).map { s.optInt(it) }.filter { it > 0 } }
                    DeclaredSiteIcon(url = url, isTouchIcon = icon.optBoolean("touch", false), sizes = sizes.orEmpty())
                }.take(SiteIcons.MAX_ICONS_PER_SITE)
                if (icons.isNotEmpty()) sites[origin] = icons
            }
            return sites.entries.toList().takeLast(SiteIcons.MAX_SITES).associate { it.key to it.value }
        }
    }
}
