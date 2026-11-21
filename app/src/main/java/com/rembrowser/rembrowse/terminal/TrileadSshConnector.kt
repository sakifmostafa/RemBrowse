package com.rembrowser.rembrowse.terminal

import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.crypto.Base64
import com.trilead.ssh2.crypto.fingerprint.KeyFingerprint
import com.trilead.ssh2.crypto.keys.Ed25519Provider
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSH through ConnectBot's client library (`org.connectbot:sshlib`, BSD 3-Clause), which is made
 * for Android: Ed25519 and modern key exchange without the platform's crypto providers.
 */
class TrileadSshConnector(
    private val connectTimeoutMillis: Int = 15_000,
    private val keyExchangeTimeoutMillis: Int = 30_000
) : SshConnector {

    init {
        Ed25519Provider.insertIfNeeded()
    }

    override fun connect(
        request: SshConnectRequest,
        prompts: SshPrompts,
        hostKeys: HostKeyStore,
        onOutput: (ByteArray, Int) -> Unit,
        onClosed: (String?) -> Unit
    ): SshShell {
        val connection = Connection(request.hostname, request.port)
        connection.setClient2ServerCiphers(CIPHERS)
        connection.setServer2ClientCiphers(CIPHERS)
        var refusedKey = false
        val verifier = ServerHostKeyVerifier { hostname, port, keyType, key ->
            val check = hostKeyCheck(request.hostname, port, keyType, key, hostKeys.knownKey(request.hostname, port))
                ?: return@ServerHostKeyVerifier true
            val accepted = prompts.confirmHostKey(check)
            if (accepted) hostKeys.remember(check) else refusedKey = true
            accepted
        }
        try {
            try {
                connection.connect(verifier, connectTimeoutMillis, keyExchangeTimeoutMillis)
            } catch (e: IOException) {
                if (refusedKey) throw SshConnectException("Not connected: the server's identity was not accepted.", e)
                throw SshConnectException(connectFailure(request, e), e)
            }
            signIn(connection, request, prompts)
            val session = connection.openSession()
            session.requestPTY("xterm-256color", request.columns, request.rows, request.widthPx, request.heightPx, null)
            session.startShell()
            return TrileadShell(connection, session, onOutput, onClosed)
        } catch (e: Exception) {
            connection.close()
            throw e as? SshConnectException ?: SshConnectException(e.message ?: "The connection failed.", e)
        }
    }

    private fun signIn(connection: Connection, request: SshConnectRequest, prompts: SshPrompts) {
        val user = request.username
        when (val credentials = request.credentials) {
            is SshCredentials.Key -> {
                if (!connection.isAuthMethodAvailable(user, "publickey")) {
                    throw SshConnectException("The server doesn't accept keys for $user.")
                }
                val accepted = try {
                    connection.authenticateWithPublicKey(user, credentials.privateKeyPem.toCharArray(), null)
                } catch (e: IOException) {
                    throw SshConnectException("The key couldn't be used: ${e.message}", e)
                }
                if (!accepted) {
                    throw SshConnectException(
                        "The server refused the key. Add its public key to ~/.ssh/authorized_keys for $user on the server."
                    )
                }
            }
            is SshCredentials.Password -> signInWithPassword(connection, user, credentials.saved, prompts)
        }
    }

    private fun signInWithPassword(connection: Connection, user: String, saved: String?, prompts: SshPrompts) {
        val password = connection.isAuthMethodAvailable(user, "password")
        val interactive = connection.isAuthMethodAvailable(user, "keyboard-interactive")
        if (!password && !interactive) {
            throw SshConnectException("The server doesn't accept passwords for $user. Use a key instead.")
        }
        var attempt = 0
        var candidate = saved
        while (attempt < MAX_PASSWORD_ATTEMPTS) {
            val typed = candidate ?: prompts.askPassword(retry = attempt > 0, prompt = null)
                ?: throw SshConnectException("Not connected: no password was given.")
            candidate = null
            val accepted = if (password) {
                connection.authenticateWithPassword(user, typed)
            } else {
                connection.authenticateWithKeyboardInteractive(user, PasswordAnswers(typed))
            }
            if (accepted) {
                prompts.passwordAccepted(typed)
                return
            }
            attempt++
        }
        throw SshConnectException("Wrong password for $user.")
    }

    /** Answers a keyboard-interactive sign-in's hidden prompts with the password. */
    private class PasswordAnswers(private val password: String) : InteractiveCallback {
        override fun replyToChallenge(
            name: String?,
            instruction: String?,
            numPrompts: Int,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> = Array(numPrompts) { i -> if (echo?.getOrNull(i) == true) "" else password }
    }

    private fun connectFailure(request: SshConnectRequest, e: IOException): String {
        val where = "${request.hostname}:${request.port}"
        val cause = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            cause.any { it is UnknownHostException } -> "Couldn't find $where. Check the host name."
            cause.any { it is ConnectException } -> "$where refused the connection. Is an SSH server running there?"
            cause.any { it is NoRouteToHostException } -> "No route to $where. Check the network."
            cause.any { it is SocketTimeoutException } -> "$where didn't answer in time."
            else -> "Couldn't connect to $where: ${e.message ?: "the connection failed"}."
        }
    }

    companion object {
        const val MAX_PASSWORD_ATTEMPTS = 3

        /**
         * The ciphers offered, in order. AES-GCM and AES-CTR (with the library's encrypt-then-MAC
         * HMACs) are what every OpenSSH server accepts by default, and Googlebook processors do
         * AES in hardware.
         *
         * Left out: the library's ChaCha20-Poly1305, which OpenSSH would otherwise pick first. It
         * hands Android's crypto provider (Conscrypt) the cipher's counter in a form Conscrypt
         * doesn't apply, and every packet then fails its MAC check; tested against OpenSSH 9.6
         * with Conscrypt installed, where AES-GCM and AES-CTR work and ChaCha20 does not. Also
         * left out: CBC modes, Blowfish and 3DES, which nothing current needs.
         */
        val CIPHERS = arrayOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes128-ctr")

        /**
         * The check to put to the user for [key], given what was accepted for this server before
         * ([known]); null when it is the key accepted before.
         */
        fun hostKeyCheck(hostname: String, port: Int, keyType: String, key: ByteArray, known: Pair<String, String>?): HostKeyCheck? {
            val base64 = String(Base64.encode(key))
            if (known != null && known.second == base64) return null
            val previous = known?.let { (_, previousKey) ->
                runCatching { fingerprint(Base64.decode(previousKey.toCharArray())) }.getOrNull()
            }
            return HostKeyCheck(
                hostname = hostname,
                port = port,
                keyType = keyType,
                keyBase64 = base64,
                fingerprint = fingerprint(key),
                changed = known != null,
                previousFingerprint = previous
            )
        }

        /** `SHA256:…` without padding, as OpenSSH prints it. */
        fun fingerprint(key: ByteArray): String {
            val raw = KeyFingerprint.createSHA256Fingerprint(key)
            return if (raw.startsWith("SHA256:")) raw else "SHA256:${raw.trimEnd('=')}"
        }
    }
}

/** A running shell: one thread reads its output, one does everything that writes. */
private class TrileadShell(
    private val connection: Connection,
    private val session: Session,
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onClosed: (String?) -> Unit
) : SshShell {

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ssh-writer").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    init {
        Thread({ readUntilClosed() }, "ssh-reader").apply { isDaemon = true }.start()
    }

    private fun readUntilClosed() {
        val buffer = ByteArray(8192)
        var reason: String? = null
        try {
            val stdout = session.stdout
            while (true) {
                val read = stdout.read(buffer)
                if (read < 0) break
                if (read > 0) onOutput(buffer, read)
            }
        } catch (e: IOException) {
            if (!closed.get()) reason = e.message ?: "The connection was lost."
        } finally {
            finish(reason)
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed.get()) return
        val copy = bytes.copyOf()
        runCatching {
            writer.execute {
                try {
                    session.stdin.write(copy)
                    session.stdin.flush()
                } catch (e: IOException) {
                    finish(e.message ?: "The connection was lost.")
                }
            }
        }
    }

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (closed.get()) return
        runCatching { writer.execute { runCatching { session.resizePTY(columns, rows, widthPx, heightPx) } } }
    }

    override fun close() {
        finish(null)
    }

    private fun finish(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        writer.shutdown()
        runCatching { session.close() }
        runCatching { connection.close() }
        onClosed(reason)
    }
}
