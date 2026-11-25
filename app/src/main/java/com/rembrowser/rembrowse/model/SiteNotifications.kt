package com.rembrowser.rembrowse.model

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What RemBrowse posts as an Android notification for a site's web notification. Worked out from
 * the web notification's fields alone (see [SiteNotifications.contentOf]), so it can be checked
 * without a device.
 */
data class SiteNotificationContent(
    /**
     * Names the Android notification (it is posted under this tag). A site's new notification with
     * the same tag gets the same key, and so replaces the one before, as on the web.
     */
    val key: String,
    val title: String,
    /** The body, or null when the page gave none. */
    val text: String?,
    /** The site's host, shown next to the app's name; null for a notification without a page. */
    val subText: String?,
    /** Where to fetch the large icon from, or null when there is none RemBrowse can fetch. */
    val iconUrl: String?,
    /** No sound and no vibration. */
    val silent: Boolean,
    /** The page that showed it (a web address), opened again by a tap after a restart. */
    val sourceUrl: String?,
    /** The site that showed it, as scheme://host[:port]; null for a notification without a page. */
    val origin: String?,
    /** The page's buttons, in its order: at most [SiteNotifications.MAX_ACTIONS], each with a title. */
    val actions: List<SiteNotificationAction> = emptyList()
)

/**
 * A button on a site notification. [name] is what the page's `notificationclick` handler is told
 * (`event.action`) when it is tapped, [title] what the button says.
 */
data class SiteNotificationAction(val name: String, val title: String)

object SiteNotifications {
    /**
     * The most buttons a site notification shows. Android shows three at most and RemBrowse adds
     * none of its own; two leaves each button room for its words on a phone.
     */
    const val MAX_ACTIONS = 2

    /**
     * The longest button name, and the longest tag, in characters, that RemBrowse carries as the
     * page gave it. Both travel in the notification's intents, and Android refuses an intent of
     * more than about a megabyte; no real name or tag comes near this.
     */
    const val MAX_NAME_LENGTH = 1024

    /** The most bytes read for a large icon; a site's icon is far smaller. */
    const val MAX_ICON_BYTES = 1024 * 1024

    /** The large icon's longest side, in pixels, that a picture is scaled down towards (64dp at xxxhdpi). */
    const val ICON_TARGET_PX = 256

    /**
     * The longest side, in pixels, of a picture RemBrowse decodes for the large icon, which also
     * keeps it to 16 megapixels. A small file can describe a vast picture (a 40 KB PNG of
     * 1,000,000 by 255 pixels), and it is the picture's size that decoding costs.
     */
    const val MAX_ICON_SIDE_PX = 4096

    /** How many times longer one side of a picture for the icon may be than the other. */
    const val MAX_ICON_ASPECT = 8

    /**
     * Ports no page may load from (the Fetch standard's "bad ports", which Gecko also refuses):
     * they belong to mail, file sharing and other services that could be fooled by a web request.
     */
    val BLOCKED_PORTS: Set<Int> = setOf(
        1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102,
        103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465,
        512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993,
        995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667, 6668,
        6669, 6679, 6697, 10080
    )

    /**
     * What to post for a web notification with these fields. [tag] is Gecko's name for the
     * notification, which already carries the site and the page's own tag (`origin#tag:…`, or
     * `origin#notag:<unique id>` when the page gave none); a notification without one never
     * replaces another, so it gets a key from [uniqueId].
     *
     * The site shown beside the app's name is the notification's [principalOrigin] (Gecko's
     * `WebNotification.origin`, see [siteOriginOf]), or the site of [source] when there is none.
     * [actions] are the page's buttons, as [actionsOf] keeps them.
     */
    fun contentOf(
        title: String?,
        text: String?,
        tag: String?,
        imageUrl: String?,
        source: String?,
        silent: Boolean,
        principalOrigin: String? = null,
        actions: List<SiteNotificationAction> = emptyList(),
        uniqueId: () -> String = { UUID.randomUUID().toString() }
    ): SiteNotificationContent {
        val sourceOrigin = SiteOrigins.originOf(source)
        val origin = siteOriginOf(principalOrigin) ?: sourceOrigin
        val host = origin?.let(SiteOrigins::displayHost)
        return SiteNotificationContent(
            key = keyOf(source, tag) ?: "${sourceOrigin.orEmpty()} #unique:${uniqueId()}",
            // Android shows an empty title as a blank line: the site's name says more
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: host.orEmpty(),
            text = text?.trim()?.takeIf { it.isNotEmpty() },
            subText = host,
            iconUrl = iconUrlOf(imageUrl),
            silent = silent,
            sourceUrl = source?.trim()?.takeIf { sourceOrigin != null },
            origin = origin,
            actions = actionsOf(actions)
        )
    }

    /**
     * The site of a notification's [principalOrigin] as scheme://host[:port], or null when it is
     * not a web site (an extension's, or none). Gecko gives the origin of the page or service
     * worker's principal (`nsIPrincipal.origin`), which ends in its attributes after a caret
     * for a container, a private page or a frame partitioned under another site
     * (`https://chat.example^userContextId=1`); those are not part of the site.
     */
    fun siteOriginOf(principalOrigin: String?): String? =
        SiteOrigins.originOf(principalOrigin?.substringBefore('^'))

    /**
     * The buttons to show for a page's [offered] actions: in the page's order, those with a
     * title (trimmed), at most [MAX_ACTIONS]. A button without words would be a blank button,
     * and one whose name is longer than [MAX_NAME_LENGTH] is left out, since its name could not
     * travel in its intent.
     */
    fun actionsOf(offered: List<SiteNotificationAction>): List<SiteNotificationAction> =
        offered.mapNotNull { action ->
            if (action.name.length > MAX_NAME_LENGTH) return@mapNotNull null
            action.title.trim().takeIf { it.isNotEmpty() }?.let { action.copy(title = it) }
        }.take(MAX_ACTIONS)

    /**
     * The action to pass to the page for a tap that names [requested], or null for a tap on the
     * notification itself. Only a name among the buttons [shown] counts: the name arrives in an
     * intent, and the activity it opens can be started by any app.
     */
    fun clickedAction(requested: String?, shown: List<SiteNotificationAction>): String? =
        requested?.takeIf { name -> shown.any { it.name == name } }

    /**
     * The key of a notification from [source] with Gecko's [tag], or null without a tag. The
     * site's origin is part of it so two sites' tags can never meet; the rest of the page's
     * address is not, since it changes as a single-page app moves around (Telegram's address
     * names the open chat). A tag longer than [MAX_NAME_LENGTH] is stood in for by its SHA-256
     * digest, which keeps the key short and still the same for the same tag.
     */
    fun keyOf(source: String?, tag: String?): String? {
        if (tag.isNullOrEmpty()) return null
        val short = if (tag.length <= MAX_NAME_LENGTH) tag else "#long:${sha256Hex(tag)}"
        return "${SiteOrigins.originOf(source).orEmpty()} $short"
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * [imageUrl] when RemBrowse can fetch it for the large icon: an http or https address, or a
     * base64 `data:` picture. Anything else (a `blob:` address, which only the page can read, or
     * a relative one) is dropped, and the notification is posted without an icon.
     *
     * RemBrowse fetches the icon itself, outside the page, so a web address must not lead to this
     * device or the local network (see [isLocalHost]), nor to a port pages may not use: a page
     * could otherwise have RemBrowse knock on a router's or another app's door for it. The
     * address is read with OkHttp's parser, the one that fetches it.
     */
    fun iconUrlOf(imageUrl: String?): String? {
        val url = imageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isWeb = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
        if (isWeb) {
            val parsed = url.toHttpUrlOrNull() ?: return null
            return url.takeUnless { isLocalHost(parsed.host) || parsed.port in BLOCKED_PORTS }
        }
        val isDataImage = url.startsWith("data:image/", ignoreCase = true) &&
            url.substringBefore(',').endsWith(";base64", ignoreCase = true) && url.contains(',')
        return url.takeIf { isDataImage }
    }

    /**
     * Whether [host] names this device or the local network: `localhost` (and names under it), or
     * an address that is loopback, private, link-local, shared (carrier-grade NAT, which Tailscale
     * also uses) or unspecified (see [isLocalAddress]). Addresses count however they are written:
     * `127.1` and `2130706433` reach the same machine as `127.0.0.1`. Other names are not looked
     * up here; what they lead to is checked when the icon is fetched.
     */
    fun isLocalHost(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (name.isEmpty() || name == "localhost" || name.endsWith(".localhost")) return true
        val address = if (':' in name) ipv6Bytes(name) else ipv4Bytes(name)
        return address != null && isLocalAddress(address)
    }

    /**
     * Whether [address] (4 bytes for IPv4, 16 for IPv6) is on this device or the local network:
     * unspecified, loopback, private (10/8, 172.16/12, 192.168/16, IPv6 fc00::/7 and the old
     * fec0::/10), link-local (169.254/16, fe80::/10) or shared (100.64/10). An IPv4 address carried
     * inside an IPv6 one (::a.b.c.d, ::ffff:a.b.c.d, 64:ff9b::a.b.c.d) counts as itself. Anything
     * that is neither length counts as local, so it is never fetched.
     */
    fun isLocalAddress(address: ByteArray): Boolean {
        fun byte(i: Int) = address[i].toInt() and 0xFF
        return when (address.size) {
            4 -> {
                val a = byte(0)
                val b = byte(1)
                a == 0 || a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            16 -> {
                val firstTenZero = (0 until 10).all { byte(it) == 0 }
                val embedsIpv4 = (firstTenZero && byte(10) == byte(11) && (byte(10) == 0 || byte(10) == 0xFF)) ||
                    (byte(0) == 0x00 && byte(1) == 0x64 && byte(2) == 0xFF && byte(3) == 0x9B && (4 until 12).all { byte(it) == 0 })
                when {
                    // ::/96 holds :: and ::1 as well
                    embedsIpv4 -> isLocalAddress(address.copyOfRange(12, 16))
                    (byte(0) and 0xFE) == 0xFC -> true
                    byte(0) == 0xFE && (byte(1) and 0x80) == 0x80 -> true
                    else -> false
                }
            }
            else -> true
        }
    }

    /**
     * The picture in a base64 `data:image/…` address, or null when it is not one or would be
     * larger than [maxBytes]. Checked before decoding, so a huge address is never decoded.
     */
    fun dataUrlBytes(url: String, maxBytes: Int = MAX_ICON_BYTES): ByteArray? {
        if (iconUrlOf(url) == null || !url.trim().startsWith("data:", ignoreCase = true)) return null
        val payload = url.substringAfter(',').trim()
        // Four base64 characters hold three bytes
        if (payload.length.toLong() * 3 / 4 > maxBytes + 3L) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= maxBytes }
    }

    /**
     * Whether a [width] by [height] picture may be decoded for the large icon: no side over
     * [MAX_ICON_SIDE_PX], and neither side more than [MAX_ICON_ASPECT] times the other. Asked of
     * the picture's bounds before it is decoded.
     */
    fun isAcceptableIconSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_ICON_SIDE_PX || height > MAX_ICON_SIDE_PX) return false
        return maxOf(width, height).toLong() <= minOf(width, height).toLong() * MAX_ICON_ASPECT
    }

    /**
     * The BitmapFactory sample size that brings a [width] by [height] picture's longer side down
     * towards [target] pixels without going below it: a power of two, as the decoder uses. By the
     * longer side, so the decoded picture is never much more than [target] either way.
     */
    fun iconSampleSize(width: Int, height: Int, target: Int = ICON_TARGET_PX): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val longer = maxOf(width, height)
        var size = 1
        while (longer / (size * 2) >= target) size *= 2
        return size
    }

    /**
     * [host] as an IPv4 address the way the system's resolver reads one (inet_aton): one to four
     * parts, each decimal, octal after a leading 0, or hex after 0x, the last filling the bytes
     * left. Null when it is not one.
     */
    private fun ipv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        val values = parts.map { inetAtonPart(it) ?: return null }
        var address = 0L
        values.dropLast(1).forEachIndexed { i, value ->
            if (value > 0xFF) return null
            address = address or (value shl (24 - 8 * i))
        }
        // The last part fills 32, 24, 16 or 8 bits
        if (values.last() >= 1L shl (8 * (5 - values.size))) return null
        address = address or values.last()
        return ByteArray(4) { i -> (address shr (24 - 8 * i)).toByte() }
    }

    private fun inetAtonPart(part: String): Long? {
        val (digits, radix) = when {
            part.startsWith("0x", ignoreCase = true) -> part.substring(2) to 16
            part.length > 1 && part.startsWith("0") -> part.substring(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty() || digits.length > 11 || digits.any { Character.digit(it, radix) < 0 }) return null
        return digits.toLong(radix)
    }

    /** [text] (without brackets) as an IPv6 address, or null when it is not one. */
    private fun ipv6Bytes(text: String): ByteArray? {
        var address = text.substringBefore('%')
        // A dotted IPv4 address may stand for the last two groups
        var tail: ByteArray? = null
        val lastColon = address.lastIndexOf(':')
        if (address.indexOf('.', lastColon) >= 0) {
            val dotted = address.substring(lastColon + 1)
            if (dotted.split('.').size != 4) return null
            tail = ipv4Bytes(dotted) ?: return null
            address = address.substring(0, lastColon + 1) + "0:0"
        }
        val halves = address.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? = if (part.isEmpty()) {
            emptyList()
        } else {
            part.split(':').map { group ->
                if (group.length !in 1..4 || group.any { Character.digit(it, 16) < 0 }) return null
                group.toInt(16)
            }
        }
        val head = groups(halves[0]) ?: return null
        val all = if (halves.size == 2) {
            val rest = groups(halves[1]) ?: return null
            if (head.size + rest.size > 7) return null
            head + List(8 - head.size - rest.size) { 0 } + rest
        } else {
            head.takeIf { it.size == 8 } ?: return null
        }
        val bytes = ByteArray(16)
        all.forEachIndexed { i, group ->
            bytes[2 * i] = (group shr 8).toByte()
            bytes[2 * i + 1] = group.toByte()
        }
        tail?.copyInto(bytes, destinationOffset = 12)
        return bytes
    }
}

/**
 * The site notifications on show, by the key of the Android notification each is posted as
 * ([SiteNotificationContent.key]). Each also holds a random token that only its own tap and swipe
 * intents carry, since the activity a tap opens can be started by any app. Main thread only.
 */
class SiteNotificationRegistry<T : Any>(private val maxEntries: Int = MAX_ENTRIES) {

    private class Entry<T>(val value: T, val token: String)

    // Oldest first
    private val entries = LinkedHashMap<String, Entry<T>>()

    val size: Int
        get() = entries.size

    /** Keeps [value] as the notification posted as [key], replacing the one before it. */
    fun put(key: String, value: T, token: String) {
        entries.remove(key)
        entries[key] = Entry(value, token)
        // Android drops an app's notifications beyond about fifty; the oldest go here too
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    /** Whether [key] is still the notification [token] was given to (not closed, nor replaced). */
    fun holds(key: String, token: String): Boolean = entries[key]?.token == token

    /** Whether a notification posted as [key] is kept, whatever its token. */
    fun has(key: String): Boolean = key in entries

    /** Takes out the notification posted as [key] when [token] is its own; otherwise null. */
    fun take(key: String?, token: String?): T? {
        if (key == null || token == null || !holds(key, token)) return null
        return entries.remove(key)?.value
    }

    fun remove(key: String): T? = entries.remove(key)?.value

    /** The key [value] is kept under, or null when it is not kept. */
    fun keyOf(value: T): String? = entries.entries.firstOrNull { it.value.value === value }?.key

    companion object {
        const val MAX_ENTRIES = 64
    }
}
