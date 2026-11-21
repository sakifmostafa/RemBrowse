package com.rembrowser.rembrowse.model

/** How RemBrowse signs in to an SSH server. */
enum class SshAuthMethod {
    /** A password, typed when connecting or saved (encrypted) with the host. */
    PASSWORD,

    /** One of the keys in Settings → Terminal. */
    KEY;

    companion object {
        fun fromNameOrDefault(name: String?): SshAuthMethod = entries.firstOrNull { it.name == name } ?: PASSWORD
    }
}

/**
 * A server saved in Settings → Terminal. Its password, when saved, is not here but encrypted on
 * the device (see `SecretStore`); [hasSavedPassword] says whether there is one.
 */
data class SshHost(
    val id: String,
    /** What the host is called in lists and on its tab; the address when left empty. */
    val label: String,
    val hostname: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    val auth: SshAuthMethod = SshAuthMethod.PASSWORD,
    /** The key to sign in with, for [SshAuthMethod.KEY]. */
    val keyId: String? = null,
    val hasSavedPassword: Boolean = false
) {
    val address: String
        get() = sshAddress(username, hostname, port)

    val displayName: String
        get() = label.ifBlank { address }
}

/**
 * A key pair kept on the device for signing in. Its private half is encrypted on the device
 * (see `SecretStore`); [publicKey] is the line to add to a server's `~/.ssh/authorized_keys`.
 */
data class SshKeyInfo(
    val id: String,
    val name: String,
    /** `ssh-ed25519 AAAA… name`, as it goes into `authorized_keys`. */
    val publicKey: String,
    /** `SHA256:…`, as `ssh-keygen -l` prints it. */
    val fingerprint: String,
    val createdAtMillis: Long
) {
    /** The key's type, from the start of [publicKey] (`ssh-ed25519`, `ssh-rsa`, …). */
    val type: String
        get() = publicKey.substringBefore(' ')
}

/** A server whose identity was accepted: the key it showed, so a different one can be noticed. */
data class KnownHost(
    /** `host:port`. */
    val hostPort: String,
    val keyType: String,
    /** The server's public key, base64, as in `known_hosts`. */
    val keyBase64: String,
    val fingerprint: String,
    val addedAtMillis: Long
)

const val DEFAULT_SSH_PORT = 22

/** `user@host`, with `:port` when it is not 22, and brackets around an IPv6 address. */
fun sshAddress(username: String, hostname: String, port: Int): String {
    val host = if (hostname.contains(':')) "[$hostname]" else hostname
    val user = if (username.isBlank()) "" else "$username@"
    return if (port == DEFAULT_SSH_PORT) "$user$host" else "$user$host:$port"
}

/** What a terminal tab shows, from its address (`rem://terminal…`). */
sealed interface TerminalTarget {
    /** A new terminal: pick a saved host or type an address. */
    data object Picker : TerminalTarget

    /** A saved host, by its id. */
    data class SavedHost(val hostId: String) : TerminalTarget

    /** A server typed straight into the terminal, not saved. */
    data class Address(val username: String, val hostname: String, val port: Int = DEFAULT_SSH_PORT) : TerminalTarget {
        val display: String get() = sshAddress(username, hostname, port)
    }
}

/**
 * Terminal tab addresses:
 * - `rem://terminal`: a new terminal, which offers the saved hosts;
 * - `rem://terminal/host/<id>`: a saved host;
 * - `rem://terminal/ssh/<user>@<host>[:<port>]`: a server typed in.
 *
 * The address is what a terminal tab keeps across restarts, so it never holds a password.
 */
object TerminalUrls {
    const val BASE = "rem://terminal"
    private const val HOST_PREFIX = "$BASE/host/"
    private const val SSH_PREFIX = "$BASE/ssh/"

    fun isTerminalUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.equals(BASE, ignoreCase = true) || trimmed.startsWith("$BASE/", ignoreCase = true)
    }

    /** The target of a terminal address; null for any other address. A malformed one is a new terminal. */
    fun parse(url: String): TerminalTarget? {
        if (!isTerminalUrl(url)) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith(HOST_PREFIX, ignoreCase = true) ->
                trimmed.substring(HOST_PREFIX.length).trim('/').takeIf { it.isNotBlank() }
                    ?.let { TerminalTarget.SavedHost(it) } ?: TerminalTarget.Picker
            trimmed.startsWith(SSH_PREFIX, ignoreCase = true) ->
                parseAddress(trimmed.substring(SSH_PREFIX.length).trim('/')) ?: TerminalTarget.Picker
            else -> TerminalTarget.Picker
        }
    }

    fun urlFor(target: TerminalTarget): String = when (target) {
        TerminalTarget.Picker -> BASE
        is TerminalTarget.SavedHost -> HOST_PREFIX + target.hostId
        is TerminalTarget.Address -> SSH_PREFIX + target.display
    }

    /**
     * An address typed into a new terminal: `user@host`, `user@host:2222`, `user@[::1]:22`, or
     * the way it is typed for `ssh` (`ssh user@host -p 2222`). Null unless it names both a user
     * and a host, with a valid port.
     */
    fun parseAddress(input: String): TerminalTarget.Address? {
        var text = input.trim()
        var port: Int? = null
        if (text.startsWith("ssh ")) {
            text = text.removePrefix("ssh ").trim()
            val portFlag = Regex("""(?:^|\s)-p\s*(\d+)(?:\s|$)""").find(text)
            if (portFlag != null) {
                port = portFlag.groupValues[1].toIntOrNull() ?: return null
                text = text.removeRange(portFlag.range).trim()
            }
            if (text.contains(' ')) return null
        }
        val at = text.lastIndexOf('@')
        if (at <= 0 || at == text.length - 1) return null
        val username = text.substring(0, at)
        var hostPart = text.substring(at + 1)
        if (username.any { it.isWhitespace() }) return null
        val hostname: String
        if (hostPart.startsWith("[")) {
            val close = hostPart.indexOf(']')
            if (close < 0) return null
            hostname = hostPart.substring(1, close)
            val rest = hostPart.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                port = rest.substring(1).toIntOrNull() ?: return null
            }
        } else {
            val colon = hostPart.lastIndexOf(':')
            if (colon >= 0 && hostPart.count { it == ':' } == 1) {
                port = hostPart.substring(colon + 1).toIntOrNull() ?: return null
                hostPart = hostPart.substring(0, colon)
            }
            hostname = hostPart
        }
        if (hostname.isBlank() || hostname.any { it.isWhitespace() || it == '/' }) return null
        val finalPort = port ?: DEFAULT_SSH_PORT
        if (finalPort !in 1..65535) return null
        return TerminalTarget.Address(username, hostname, finalPort)
    }
}
