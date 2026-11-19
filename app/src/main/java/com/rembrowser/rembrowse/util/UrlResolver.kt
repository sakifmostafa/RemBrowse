package com.rembrowser.rembrowse.util

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlResolver {
    const val DEFAULT_SEARCH_ENGINE_PREFIX = "https://duckduckgo.com/?q="

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val SPECIAL_SCHEME_REGEX = Regex("^(about|file|resource|data|blob|moz-extension):.*", RegexOption.IGNORE_CASE)
    private val LOCAL_HOST_REGEX = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val DOMAIN_LIKE_REGEX = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?(/.*)?$")

    fun resolveInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return "https://duckduckgo.com"

        if (SCHEME_REGEX.matches(trimmed) || SPECIAL_SCHEME_REGEX.matches(trimmed)) {
            return trimmed
        }

        if (!trimmed.contains(" ")) {
            if (LOCAL_HOST_REGEX.matches(trimmed)) {
                return "http://$trimmed"
            }
            if (DOMAIN_LIKE_REGEX.matches(trimmed)) {
                return "https://$trimmed"
            }
        }

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return "$DEFAULT_SEARCH_ENGINE_PREFIX$encoded"
    }

    fun extractDisplayHost(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("about:", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val host = URI(trimmed).host
            if (!host.isNullOrBlank()) {
                host.removePrefix("www.")
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    fun isValidExtensionUri(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("resource://", ignoreCase = true)
    }
}
