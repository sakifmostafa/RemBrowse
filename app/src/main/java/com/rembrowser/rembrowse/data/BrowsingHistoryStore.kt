package com.rembrowser.rembrowse.data

import android.content.Context
import android.util.Log
import com.rembrowser.rembrowse.model.HistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Remembers the browsing history between launches. */
interface BrowsingHistoryStore {
    fun load(): List<HistoryEntry>
    fun save(entries: List<HistoryEntry>)
}

/** Keeps nothing. Used where history does not need to outlive the process (tests, previews). */
object NoBrowsingHistory : BrowsingHistoryStore {
    override fun load(): List<HistoryEntry> = emptyList()
    override fun save(entries: List<HistoryEntry>) = Unit
}

/**
 * Stores the history as JSON in its own preferences file. Every page visit changes it, so
 * writes happen on a background thread, and a burst of visits is written once, with the
 * newest list.
 */
class SharedPrefsBrowsingHistoryStore(
    context: Context,
    private val writer: Executor = Executors.newSingleThreadExecutor()
) : BrowsingHistoryStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pending = AtomicReference<List<HistoryEntry>?>(null)

    override fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> parse(array.optJSONObject(index)) }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable browsing history", e)
            emptyList()
        }
    }

    override fun save(entries: List<HistoryEntry>) {
        // Only the first of several quick saves schedules a write; it writes the latest list
        if (pending.getAndSet(entries) == null) {
            writer.execute {
                val latest = pending.getAndSet(null) ?: return@execute
                prefs.edit().putString(KEY_ENTRIES, toJson(latest)).commit()
            }
        }
    }

    private fun toJson(entries: List<HistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("visitedAt", entry.visitedAtMillis)
                }
            )
        }
        return array.toString()
    }

    private fun parse(obj: JSONObject?): HistoryEntry? {
        obj ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        return HistoryEntry(url = url, title = obj.optString("title"), visitedAtMillis = obj.optLong("visitedAt", 0L))
    }

    private companion object {
        const val TAG = "BrowsingHistory"
        const val PREFS_NAME = "rembrowse_history"
        const val KEY_ENTRIES = "entries"
    }
}
