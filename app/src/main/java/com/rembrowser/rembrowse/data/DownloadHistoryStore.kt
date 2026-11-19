package com.rembrowser.rembrowse.data

import android.content.Context
import android.util.Log
import com.rembrowser.rembrowse.model.DownloadItem
import com.rembrowser.rembrowse.model.DownloadStatus
import org.json.JSONArray
import org.json.JSONObject

/** Remembers the downloads list between launches. */
interface DownloadHistoryStore {
    fun load(): List<DownloadItem>
    fun save(items: List<DownloadItem>)
}

/** Keeps nothing. Used where the list does not need to outlive the process. */
object NoDownloadHistory : DownloadHistoryStore {
    override fun load(): List<DownloadItem> = emptyList()
    override fun save(items: List<DownloadItem>) = Unit
}

class SharedPrefsDownloadHistoryStore(context: Context) : DownloadHistoryStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> parse(array.optJSONObject(index)) }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable download history", e)
            emptyList()
        }
    }

    override fun save(items: List<DownloadItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("fileName", item.fileName)
                    put("sourceUrl", item.sourceUrl)
                    put("mimeType", item.mimeType)
                    put("totalBytes", item.totalBytes)
                    put("receivedBytes", item.receivedBytes)
                    put("status", item.status.name)
                    put("contentUri", item.contentUri ?: JSONObject.NULL)
                    put("startedAtMillis", item.startedAtMillis)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun parse(obj: JSONObject?): DownloadItem? {
        obj ?: return null
        val id = obj.optString("id")
        val fileName = obj.optString("fileName")
        if (id.isBlank() || fileName.isBlank()) return null
        val stored = DownloadStatus.entries.firstOrNull { it.name == obj.optString("status") }
        return DownloadItem(
            id = id,
            fileName = fileName,
            sourceUrl = obj.optString("sourceUrl"),
            mimeType = obj.optString("mimeType"),
            totalBytes = obj.optLong("totalBytes", DownloadItem.UNKNOWN_SIZE),
            receivedBytes = obj.optLong("receivedBytes", 0L),
            // A download cannot outlive the process, so one saved as running was interrupted
            status = if (stored == null || stored == DownloadStatus.IN_PROGRESS) DownloadStatus.FAILED else stored,
            contentUri = if (obj.isNull("contentUri")) null else obj.optString("contentUri").takeIf { it.isNotBlank() },
            startedAtMillis = obj.optLong("startedAtMillis", 0L)
        )
    }

    private companion object {
        const val TAG = "DownloadHistory"
        const val PREFS_NAME = "rembrowse_downloads"
        const val KEY_ITEMS = "items"
    }
}
