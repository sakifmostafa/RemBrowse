package com.rembrowser.rembrowse.util

import com.rembrowser.rembrowse.model.BrowserTab
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlResolver {
    const val DEFAULT_SEARCH_ENGINE_PREFIX = "https://duckduckgo.com/?q="

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val SPECIAL_SCHEME_REGEX =
        Regex("^(about|file|resource|data|blob|moz-extension|view-source):.*", RegexOption.IGNORE_CASE)
    private val LOCAL_HOST_REGEX =
        Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)

    // Labels may contain any letter or digit (IDN hosts), but the last label must look like a
    // TLD (letters only, 2+ chars) so "3.14" or "v1.2" are treated as searches, not hosts.
    private val DOMAIN_LIKE_REGEX =
        Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}(:\\d+)?([/?#].*)?$")

    fun resolveInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return BrowserTab.NEW_TAB_URL

        if (SCHEME_REGEX.matches(trimmed) || SPECIAL_SCHEME_REGEX.matches(trimmed)) {
            return trimmed
        }

        if (trimmed.none { it.isWhitespace() }) {
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

    /**
     * What an unfocused address bar shows for [url]: the full address, minus the bare trailing
     * slash browsers add to site roots. The start page shows as an empty field.
     */
    fun toDisplayUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == BrowserTab.NEW_TAB_URL) return ""
        if (!isWebUrl(trimmed)) return trimmed
        val pathStart = trimmed.indexOf('/', trimmed.indexOf("://") + 3)
        return if (pathStart >= 0 && pathStart == trimmed.length - 1) {
            trimmed.substring(0, pathStart)
        } else {
            trimmed
        }
    }

    /** Conventional icon location for a site, or null for pages that are not on the web. */
    fun faviconUrl(url: String): String? {
        if (!isWebUrl(url)) return null
        return try {
            val uri = URI(url.trim())
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme.lowercase()}://$host$port/favicon.ico"
        } catch (_: Exception) {
            null
        }
    }

    /** True for ordinary web pages. The only URLs accepted from other apps' intents. */
    fun isWebUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
    }

    fun isHttpUrl(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

    /** A single upper-case character that stands in for a site icon. */
    fun monogram(url: String, title: String = ""): String {
        val host = extractDisplayHost(url)
        val source = when {
            url.isBlank() || url == BrowserTab.NEW_TAB_URL -> title
            host.startsWith("about:", ignoreCase = true) -> host.substringAfter(':')
            else -> host
        }
        val first = source.firstOrNull { it.isLetterOrDigit() }
            ?: title.firstOrNull { it.isLetterOrDigit() }
        return first?.uppercaseChar()?.toString() ?: "•"
    }

    /**
     * Extension packages are executable code, so they may only come from an encrypted origin,
     * a local file the user picked, or a resource bundled with the app.
     */
    fun isValidExtensionUri(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("resource://", ignoreCase = true)
    }
}
