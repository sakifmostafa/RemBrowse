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

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val currentVersion = item.optJSONObject("current_version") ?: continue
            val fileObj = currentVersion.optJSONObject("file") ?: continue
            val xpiUrl = fileObj.optString("url", "").trim()
            if (xpiUrl.isEmpty()) continue

            val guid = item.optString("guid", "").ifBlank { item.optString("id", "") }
            val slug = item.optString("slug", guid)
            val name = extractLocalizedText(item, "name").ifBlank { slug }
            val summary = extractLocalizedText(item, "summary")
            val iconUrl = item.optString("icon_url", "").takeIf { it.isNotBlank() }
            val authorsArray = item.optJSONArray("authors")
            val author = if (authorsArray != null && authorsArray.length() > 0) {
                authorsArray.optJSONObject(0)?.optString("name", "")?.ifBlank { "Unknown author" }
                    ?: "Unknown author"
            } else {
                "Unknown author"
            }
            val ratingsObj = item.optJSONObject("ratings")
            val rating = ratingsObj?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val weeklyDownloads = item.optLong("weekly_downloads", 0L)
            val version = currentVersion.optString("version", "1.0")

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

    private fun extractLocalizedText(obj: JSONObject, key: String): String {
        val value = obj.opt(key) ?: return ""
        return when (value) {
            is String -> value
            is JSONObject -> {
                value.optString("en-US", "").ifBlank {
                    value.optString("en", "").ifBlank {
                        val keys = value.keys()
                        if (keys.hasNext()) value.optString(keys.next(), "") else ""
                    }
                }
            }
            else -> ""
        }
    }
}

class OkHttpAmoApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = "https://addons.mozilla.org/api/v5/addons/search/"
) : AmoApiClient {

    override suspend fun fetchFeaturedExtensions(pageSize: Int): Result<List<AmoAddon>> {
        return queryAmo(query = null, pageSize = pageSize)
    }

    override suspend fun searchExtensions(query: String, pageSize: Int): Result<List<AmoAddon>> {
        return queryAmo(query = query.trim().takeIf { it.isNotEmpty() }, pageSize = pageSize)
    }

    private suspend fun queryAmo(query: String?, pageSize: Int): Result<List<AmoAddon>> {
        return withContext(ioDispatcher) {
            runCatching {
                val urlBuilder = baseUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("app", "android")
                    .addQueryParameter("type", "extension")
                    .addQueryParameter("page_size", pageSize.coerceIn(1, 50).toString())

                if (query != null) {
                    urlBuilder.addQueryParameter("q", query)
                } else {
                    urlBuilder.addQueryParameter("sort", "users")
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Accept", "application/json")
                    .header("User-Agent", "RemBrowse/1.0 (Android; GeckoView)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("AMO API returned HTTP ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: throw IOException("Empty response from addons.mozilla.org")
                    AmoJsonParser.parseSearchResponse(body)
                }
            }
        }
    }
}
