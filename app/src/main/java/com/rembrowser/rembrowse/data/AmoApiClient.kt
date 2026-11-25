package com.rembrowser.rembrowse.data

import com.rembrowser.rembrowse.model.AmoAddon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface AmoApiClient {
    suspend fun fetchFeaturedExtensions(pageSize: Int = 20): Result<List<AmoAddon>>
    suspend fun searchExtensions(query: String, pageSize: Int = 20): Result<List<AmoAddon>>
}

object AmoJsonParser {
    fun parseSearchResponse(jsonBody: String): List<AmoAddon> {
        val root = JSONObject(jsonBody)
        val results = root.optJSONArray("results") ?: return emptyList()
        val parsed = mutableListOf<AmoAddon>()
        val seenIds = HashSet<String>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val currentVersion = item.optJSONObject("current_version") ?: continue
            val fileObj = currentVersion.optJSONObject("file") ?: continue
            val xpiUrl = stringOrEmpty(fileObj, "url").trim()
            // Only offer packages that can be fetched over an encrypted connection
            if (!xpiUrl.startsWith("https://", ignoreCase = true)) continue

            val guid = stringOrEmpty(item, "guid").ifBlank { stringOrEmpty(item, "id") }
            // The id doubles as the list key in the UI, so it must be present and unique
            if (guid.isBlank() || !seenIds.add(guid)) continue

            val slug = stringOrEmpty(item, "slug").ifBlank { guid }
            val name = extractLocalizedText(item, "name").ifBlank { slug }
            val summary = extractLocalizedText(item, "summary")
            val iconUrl = stringOrEmpty(item, "icon_url")
                .takeIf { it.startsWith("https://", ignoreCase = true) }
            val author = item.optJSONArray("authors")
                ?.optJSONObject(0)
                ?.let { stringOrEmpty(it, "name") }
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown author"
            val ratingsObj = item.optJSONObject("ratings")
            val rating = ratingsObj?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val weeklyDownloads = item.optLong("weekly_downloads", 0L)
            val version = stringOrEmpty(currentVersion, "version").ifBlank { "1.0" }

            parsed.add(
                AmoAddon(
                    id = guid,
                    slug = slug,
                    name = name,
                    summary = summary,
                    iconUrl = iconUrl,
                    author = author,
                    rating = rating,
                    weeklyDownloads = weeklyDownloads,
                    version = version,
                    xpiUrl = xpiUrl
                )
            )
        }
        return parsed
    }

    /**
     * AMO returns translated fields either as a plain string or as a locale map. When a `lang`
     * is requested and that translation is missing, the map holds `null` for it plus a
     * `_default` key naming the locale that does have text.
     */
    private fun extractLocalizedText(obj: JSONObject, key: String): String {
        return when (val value = obj.opt(key)) {
            null -> ""
            is String -> value
            is JSONObject -> {
                val defaultLocale = stringOrEmpty(value, "_default")
                val preferred = listOf(AMO_LANGUAGE, defaultLocale, "en-US", "en")
                    .filter { it.isNotBlank() }
                    .firstNotNullOfOrNull { locale -> stringOrEmpty(value, locale).takeIf { it.isNotBlank() } }
                preferred ?: value.keys().asSequence()
                    .filter { it != "_default" }
                    .firstNotNullOfOrNull { locale -> stringOrEmpty(value, locale).takeIf { it.isNotBlank() } }
                    .orEmpty()
            }
            else -> ""
        }
    }

    /** `optString` turns a JSON `null` into the text "null"; this returns "" for it instead. */
    private fun stringOrEmpty(obj: JSONObject, key: String): String {
        if (obj.isNull(key)) return ""
        return when (val value = obj.opt(key)) {
            is String -> value
            null -> ""
            else -> value.toString()
        }
    }
}

/** Asking for one language keeps each catalog response to a fraction of the all-locales size. */
internal const val AMO_LANGUAGE = "en-US"

class OkHttpAmoApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = "https://addons.mozilla.org/api/v5/addons/search/",
    private val userAgent: String = "RemBrowse (Android; GeckoView)"
) : AmoApiClient {

    override suspend fun fetchFeaturedExtensions(pageSize: Int): Result<List<AmoAddon>> {
        return queryAmo(query = null, pageSize = pageSize)
    }

    override suspend fun searchExtensions(query: String, pageSize: Int): Result<List<AmoAddon>> {
        return queryAmo(query = query.trim().takeIf { it.isNotEmpty() }, pageSize = pageSize)
    }

    private suspend fun queryAmo(query: String?, pageSize: Int): Result<List<AmoAddon>> {
        return withContext(ioDispatcher) {
            try {
                Result.success(fetch(query, pageSize))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                Result.failure(IOException("You appear to be offline. Check your connection and try again.", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun fetch(query: String?, pageSize: Int): List<AmoAddon> {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("app", "android")
            .addQueryParameter("type", "extension")
            .addQueryParameter("lang", AMO_LANGUAGE)
            .addQueryParameter("page_size", pageSize.coerceIn(1, 50).toString())

        if (query != null) {
            urlBuilder.addQueryParameter("q", query)
        } else {
            urlBuilder.addQueryParameter("sort", "users")
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("addons.mozilla.org returned HTTP ${response.code}")
            }
            AmoJsonParser.parseSearchResponse(response.body.string())
        }
    }
}
