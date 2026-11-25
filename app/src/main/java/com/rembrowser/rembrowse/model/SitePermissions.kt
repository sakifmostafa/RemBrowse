package com.rembrowser.rembrowse.model

import android.icu.text.IDNA
import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a site can ask to use. Everything else a page asks for is answered without the user. */
enum class SitePermissionKind {
    CAMERA,
    MICROPHONE,
    LOCATION,
    NOTIFICATIONS,

    /** DRM-protected video and music (Encrypted Media Extensions), as streaming sites use. */
    PROTECTED_CONTENT,

    /** An embedded site asking to use its own cookies on the page's site. */
    STORAGE_ACCESS,

    /**
     * A site on the internet connecting to a device on the local network (a router's page, a
     * media server), as the engine's Local Network Access checks see it.
     */
    LOCAL_NETWORK,

    /**
     * A site on the internet connecting to an app or service on this device (localhost). A page
     * on the local network does so without asking, as the engine has it.
     */
    LOCAL_DEVICE;

    /**
     * Whether a request for this kind offers "Allow this time". Only things a site uses while it
     * is open get it; a lasting one such as notifications would be meaningless granted once.
     */
    val offersAllowOnce: Boolean
        get() = this == CAMERA || this == MICROPHONE || this == LOCATION ||
            this == LOCAL_NETWORK || this == LOCAL_DEVICE
}

/** A decision remembered for a site. No decision at all means the site is asked again. */
enum class SitePermissionState { ALLOW, BLOCK }

/** How the user answered a [PermissionRequest]. */
enum class PermissionAnswer {
    /** Granted, and remembered for the site. */
    ALLOW,

    /**
     * Granted without being remembered: the tab keeps it while it stays on the same site, and
     * loses it when it goes to another site, closes, or the app restarts.
     */
    ALLOW_ONCE,

    /** Refused, and remembered: the site is refused without asking from then on. */
    BLOCK,

    /** The question was closed unanswered: this request is refused, and nothing is remembered. */
    DISMISS
}

/**
 * A site waiting for the user's answer about [kinds] (camera and microphone together when a page
 * asks for both). The UI shows it in the pane of tab [tabId] and answers it with
 * `BrowserViewModel.answerPermission`.
 */
data class PermissionRequest(
    val id: String,
    val tabId: String,
    /** The site asking, as scheme://host[:port]. For STORAGE_ACCESS, the page's site. */
    val origin: String,
    /** The site's host for people to read, with its port when that is not the usual one. */
    val displayHost: String,
    val kinds: Set<SitePermissionKind>,
    /** For STORAGE_ACCESS, the host of the embedded site that wants its cookies; otherwise null. */
    val embeddedHost: String?,
    /** Whether to offer [PermissionAnswer.ALLOW_ONCE] (see [SitePermissionKind.offersAllowOnce]). */
    val offersAllowOnce: Boolean
)

/** A decision remembered for a site, as Settings lists them. */
data class SitePermissionEntry(
    val origin: String,
    val displayHost: String,
    val kind: SitePermissionKind,
    val state: SitePermissionState,
    /**
     * For STORAGE_ACCESS, the embedded site the decision is about (a page's site can have one per
     * embedded site); otherwise null. Pass it back to `setSitePermission` to change that one.
     */
    val embeddedOrigin: String? = null
) {
    /** [embeddedOrigin]'s host for people to read, or null. */
    val embeddedHost: String?
        get() = embeddedOrigin?.let(SiteOrigins::displayHost)
}

/**
 * Site origins (scheme://host[:port]) the way site permissions are kept: web addresses only, the
 * host in lower-case ASCII, and the port only when it is not the scheme's usual one. Gecko reports
 * the same site in different spellings (a Unicode host here, punycode there), and one site must
 * not end up with two sets of decisions, nor two sites with one.
 */
object SiteOrigins {
    private val webAddress = Regex(
        "^(https?)://(?:[^@/?#]*@)?(\\[[^\\]/?#]*\\]|[^:/?#@]*)(?::(\\d*))?(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    /** Dots that IDNA reads as label separators, as Gecko does. */
    private val labelDots = Regex("[\u3002\uFF0E\uFF61]")

    private const val MAX_DISPLAY_FORMS = 1_024

    /**
     * Gecko's own host conversion: UTS #46 without the transitional mappings, which keeps "ß" and
     * "ς" instead of turning faß.de into fass.de, a different site. Absent only where android.icu
     * is (JVM unit tests); see [Punycode] for what stands in then.
     */
    private val uts46: IDNA? = runCatching {
        IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII)
    }.getOrNull()

    /**
     * How Gecko shows each host it has reported, by the host's ASCII form: the host part of the
     * `displaySpec` it gives with locations, permission requests and its permission store, which
     * is also what RemBrowse's address bar shows. Gecko writes a label in Unicode only when its IDN
     * rules find it safe (no blocklisted look-alike characters such as U+2044 or U+2215, no
     * confusable mix or whole-script look-alike), and in punycode otherwise. Kept in memory, for
     * the most recent hosts; a host not in it is shown in punycode.
     */
    private val displayForms = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_DISPLAY_FORMS
    }

    /** The origin of [url], or null for anything that is not an http or https address. */
    fun originOf(url: String?): String? {
        val match = webAddress.matchEntire(url?.trim() ?: return null) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        val host = asciiHost(match.groupValues[2]) ?: return null
        val port = match.groupValues[3].toIntOrNull()
        val usualPort = if (scheme == "https") 443 else 80
        return if (port == null || port == usualPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /**
     * The site of [url]: its scheme and registrable domain (one label below the public suffix),
     * without a port, as Gecko's `Services.eTLD.getSite` gives it and as Gecko keys storage access
     * grants. An IP address, a single-label host or a bare public suffix is its own site.
     */
    fun siteOf(url: String?): String? {
        val origin = originOf(url) ?: return null
        val match = webAddress.matchEntire(origin) ?: return null
        val scheme = match.groupValues[1]
        val host = match.groupValues[2]
        if (host.startsWith("[")) return "$scheme://$host"
        // Since OkHttp 5 its list of public suffixes is an Android asset, read through the context
        // AndroidX Startup gives it as the app starts. Without one the lookup throws and the whole
        // host stands as the site, which SiteOriginsSiteTest guards against.
        val domain = runCatching { "$scheme://$host/".toHttpUrlOrNull()?.topPrivateDomain() }.getOrNull()
        return "$scheme://${domain ?: host}"
    }

    /**
     * The host of [origin] for people to read, with a port that is not the usual one: as Gecko,
     * and so the address bar, shows it (see [rememberDisplayForm]), or in punycode when Gecko has
     * not shown it. Never more readable, so never more misleading, than the address bar. The
     * decisions themselves stay keyed by [originOf]'s ASCII form.
     */
    fun displayHost(origin: String): String {
        val match = webAddress.matchEntire(origin.trim()) ?: return origin
        val host = match.groupValues[2]
        val ascii = if (host.startsWith("[")) host.lowercase(Locale.ROOT) else asciiHost(host) ?: return origin
        val shown = synchronized(displayForms) { displayForms[ascii] } ?: ascii
        val port = match.groupValues[3]
        return if (port.isEmpty()) shown else "$shown:$port"
    }

    /**
     * Notes how Gecko displays [url]'s host. Only for addresses Gecko gave in its display form
     * (a `displaySpec`): a location it reported, a permission request's or a stored permission's
     * address. Never for an address typed or sent by a page, which Gecko has not vetted.
     */
    fun rememberDisplayForm(url: String?) {
        val match = webAddress.matchEntire(url?.trim() ?: return) ?: return
        val shown = match.groupValues[2].lowercase(Locale.ROOT)
        if (shown.isEmpty() || shown.startsWith("[")) return
        val ascii = asciiHost(shown) ?: return
        val asciiLabels = ascii.split('.')
        val shownLabels = shown.split('.')
        synchronized(displayForms) {
            displayForms[ascii] = shown
            // Gecko judges each label with the same top-level domain, so the parent domains (an
            // embedded page's site, say) are shown as their part of this host was
            if (asciiLabels.size == shownLabels.size) {
                for (drop in 1 until asciiLabels.size) {
                    val parent = asciiLabels.drop(drop).joinToString(".")
                    if (parent !in displayForms) displayForms[parent] = shownLabels.drop(drop).joinToString(".")
                }
            }
        }
    }

    private fun asciiHost(host: String): String? {
        if (host.isEmpty()) return null
        if (host.startsWith("[")) return host.lowercase(Locale.ROOT)
        // An ASCII host (punycode included) is taken as it is: converting it could only lose detail
        if (host.all { it.code < 0x80 }) return host.lowercase(Locale.ROOT)
        val icu = uts46
        val ascii = if (icu != null) {
            val info = IDNA.Info()
            val converted = icu.nameToASCII(host, StringBuilder(), info).toString()
            if (info.hasErrors()) null else converted
        } else {
            Punycode.hostToAscii(host)
        }
        return (ascii ?: host).lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /**
     * What stands in for android.icu's UTS #46 where it is missing: the same mapping for ordinary
     * hosts (compatibility forms folded, lower case, "ß" and "ς" kept), then RFC 3492 punycode.
     */
    internal object Punycode {
        private const val BASE = 36
        private const val T_MIN = 1
        private const val T_MAX = 26
        private const val SKEW = 38
        private const val DAMP = 700
        private const val INITIAL_BIAS = 72
        private const val INITIAL_N = 128

        fun hostToAscii(host: String): String? {
            val mapped = Normalizer.normalize(
                Normalizer.normalize(host.replace(labelDots, "."), Normalizer.Form.NFKC).lowercase(Locale.ROOT),
                Normalizer.Form.NFC
            )
            val labels = mapped.split('.').map { label ->
                if (label.all { it.code < 0x80 }) label else "xn--" + (encode(label) ?: return null)
            }
            return labels.joinToString(".")
        }

        /** RFC 3492 punycode of [input], or null if it is too long to encode. */
        fun encode(input: String): String? {
            val codePoints = input.codePoints().toArray()
            val output = StringBuilder()
            codePoints.filter { it < 0x80 }.forEach { output.append(it.toChar()) }
            val basicCount = output.length
            var handled = basicCount
            if (basicCount > 0) output.append('-')
            var n = INITIAL_N
            var delta = 0L
            var bias = INITIAL_BIAS
            while (handled < codePoints.size) {
                val next = codePoints.filter { it >= n }.minOrNull() ?: return null
                delta += (next - n).toLong() * (handled + 1)
                if (delta > Int.MAX_VALUE) return null
                n = next
                for (c in codePoints) {
                    if (c < n) delta++
                    if (c == n) {
                        var q = delta
                        var k = BASE
                        while (true) {
                            val t = when {
                                k <= bias -> T_MIN
                                k >= bias + T_MAX -> T_MAX
                                else -> k - bias
                            }
                            if (q < t) break
                            output.append(digit(t + ((q - t) % (BASE - t)).toInt()))
                            q = (q - t) / (BASE - t)
                            k += BASE
                        }
                        output.append(digit(q.toInt()))
                        bias = adapt(delta, handled + 1, handled == basicCount)
                        delta = 0
                        handled++
                    }
                }
                delta++
                n++
            }
            return output.toString()
        }

        private fun adapt(delta: Long, points: Int, first: Boolean): Int {
            var d = if (first) delta / DAMP else delta / 2
            d += d / points
            var k = 0
            while (d > ((BASE - T_MIN) * T_MAX) / 2) {
                d /= BASE - T_MIN
                k += BASE
            }
            return (k + (BASE - T_MIN + 1) * d / (d + SKEW)).toInt()
        }

        private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)
    }
}
