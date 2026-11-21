package com.rembrowser.rembrowse.terminal

/** How the connection signs in. */
sealed interface SshCredentials {
    /** A password: the saved one first, if any, then whatever the user types when asked. */
    data class Password(val saved: String?) : SshCredentials

    /** A private key in OpenSSH or PEM format, unencrypted (keys are stored encrypted by the app). */
    data class Key(val privateKeyPem: String) : SshCredentials
}

/** Everything needed to open a shell on a server. */
data class SshConnectRequest(
    val hostname: String,
    val port: Int,
    val username: String,
    val credentials: SshCredentials,
    /** The terminal's size when the shell starts; it can change at any time after. */
    val columns: Int,
    val rows: Int,
    val widthPx: Int,
    val heightPx: Int
)

/** The key a server identified itself with, against what was accepted for it before. */
data class HostKeyCheck(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val keyBase64: String,
    /** `SHA256:…`, as `ssh-keygen -l` and the server's own logs print it. */
    val fingerprint: String,
    /** A different key was accepted for this server before: possibly someone in the way. */
    val changed: Boolean,
    /** The fingerprint accepted before, when [changed]. */
    val previousFingerprint: String? = null
)

/** What makes the user decide while a connection is being made. Called on the connecting thread. */
interface SshPrompts {
    /** Blocks until the user accepts (true) or refuses the server's new or changed key. */
    fun confirmHostKey(check: HostKeyCheck): Boolean

    /**
     * Blocks until the user types a password, or cancels (null). [retry] after a wrong one.
     * [prompt] is the server's own wording for keyboard-interactive sign-in, when it gave one.
     */
    fun askPassword(retry: Boolean, prompt: String?): String?

    /** The password that worked, so it can be saved if the user asked to. */
    fun passwordAccepted(password: String) {}
}

/** The servers whose keys were accepted, and where new ones are kept. */
interface HostKeyStore {
    /** The accepted key of `hostname:port`: (type, base64), or null for a server never seen. */
    fun knownKey(hostname: String, port: Int): Pair<String, String>?

    fun remember(check: HostKeyCheck)
}

/** A shell on a server. Safe to call from the main thread: the work happens elsewhere. */
interface SshShell {
    fun write(bytes: ByteArray)

    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)

    fun close()
}

/** Thrown when a connection is refused for a reason the user should read as it is. */
class SshConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Opens shells. Behind an interface so terminal tabs can be tested without a network. */
interface SshConnector {
    /**
     * Connects, checks the server's key, signs in and starts a shell. Blocks; call it off the
     * main thread. [onOutput] gets what the shell prints (on a reading thread), and [onClosed]
     * is called once when the shell ends: with null when it exited, or with the reason.
     *
     * @throws SshConnectException with a message for the user when the connection or sign-in
     *   fails, or the user refused the server's key or cancelled the password.
     */
    fun connect(
        request: SshConnectRequest,
        prompts: SshPrompts,
        hostKeys: HostKeyStore,
        onOutput: (ByteArray, Int) -> Unit,
        onClosed: (String?) -> Unit
    ): SshShell
}
