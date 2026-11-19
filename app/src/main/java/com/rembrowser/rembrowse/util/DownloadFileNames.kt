package com.rembrowser.rembrowse.util

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Works out what to call a downloaded file. Everything a server or a page supplies is treated
 * as untrusted: the result is always a single, plain file name with no path in it.
 */
object DownloadFileNames {

    const val FALLBACK_NAME = "download"
    private const val MAX_LENGTH = 120

    private val EXTENDED_NAME = Regex("""filename\*\s*=\s*([\w!#$%&+\-^`{}~]*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val QUOTED_NAME = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
    private val PLAIN_NAME = Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
    private val RESERVED = Regex("""[<>:"|?*\\/\u0000-\u001f\u007f]""")

    /**
     * The name to save under: the server's `Content-Disposition` name if it gave one, otherwise
     * the last part of the address. [extensionForMime] supplies an extension (without the dot)
     * when the name has none.
     */
    fun resolve(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        extensionForMime: (String) -> String? = { null }
    ): String {
        val candidate = fromContentDisposition(contentDisposition) ?: fromUrl(url)
        val name = sanitize(candidate.orEmpty())
        if (extensionOf(name).isNotEmpty() || mimeType.isNullOrBlank()) return name
        val extension = extensionForMime(mimeType.trim().lowercase(Locale.US))
            ?.trim('.')
            ?.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
            ?: return name
        return sanitize("$name.$extension")
    }

    /** The file name in a `Content-Disposition` header, or null if it carries none. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        // RFC 6266: the encoded form (filename*=) wins over the plain one when both are sent
        EXTENDED_NAME.find(header)?.let { match ->
            val charset = match.groupValues[1].ifBlank { "UTF-8" }
            decodePercent(match.groupValues[2].trim(), charset)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        QUOTED_NAME.find(header)?.let { match ->
            return match.groupValues[1].replace(Regex("""\\(.)"""), "$1").takeIf { it.isNotBlank() }
        }
        return PLAIN_NAME.find(header)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** The last path segment of a web address, decoded. Null for `data:` and `blob:` addresses. */
    fun fromUrl(url: String): String? {
        if (!UrlResolver.isWebUrl(url)) return null
        val path = try {
            URI(url.trim()).rawPath
        } catch (_: Exception) {
            url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        } ?: return null
        val segment = path.trimEnd('/').substringAfterLast('/')
        return (decodePercent(segment, "UTF-8") ?: segment).takeIf { it.isNotBlank() }
    }

    /**
     * Reduces [name] to something safe to create: no directories, no characters a file system
     * rejects, no leading dot (which would hide the file) and a sensible length.
     */
    fun sanitize(name: String): String {
        var cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        cleaned = RESERVED.replace(cleaned, "_")
        cleaned = cleaned.trim().trim('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK_NAME
        if (cleaned.length > MAX_LENGTH) {
            val extension = extensionOf(cleaned)
            val suffix = if (extension.isEmpty() || extension.length > 10) "" else ".$extension"
            cleaned = cleaned.take(MAX_LENGTH - suffix.length).trimEnd('.', ' ') + suffix
        }
        return cleaned
    }

    /** [name], or `name (1)`, `name (2)`… keeping the extension, so nothing is overwritten. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val extension = extensionOf(name)
        val base = if (extension.isEmpty()) name else name.dropLast(extension.length + 1)
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        for (counter in 1..999) {
            val candidate = "$base ($counter)$suffix"
            if (!exists(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()})$suffix"
    }

    /** The extension without its dot, lower-cased; empty when there is none. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    private fun decodePercent(value: String, charset: String): String? = try {
        // URLDecoder is for form data and would turn a literal + into a space
        URLDecoder.decode(value.replace("+", "%2B"), charset)
    } catch (_: Exception) {
        null
    }
}

/** Human-readable file sizes: "512 B", "1.5 KB", "12.4 MB". */
object ByteSizes {
    private val UNITS = listOf("KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        // One decimal below 100 ("12.4 MB"), none above ("512 MB")
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, UNITS[unit])
        } else {
            String.format(Locale.US, "%.1f %s", value, UNITS[unit])
        }
    }
}
