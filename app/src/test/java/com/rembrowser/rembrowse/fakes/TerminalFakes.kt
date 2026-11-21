package com.rembrowser.rembrowse.fakes

import com.rembrowser.rembrowse.terminal.HostKeyCheck
import com.rembrowser.rembrowse.terminal.HostKeyStore
import com.rembrowser.rembrowse.terminal.SshConnectException
import com.rembrowser.rembrowse.terminal.SshConnectRequest
import com.rembrowser.rembrowse.terminal.SshConnector
import com.rembrowser.rembrowse.terminal.SshCredentials
import com.rembrowser.rembrowse.terminal.SshPrompts
import com.rembrowser.rembrowse.terminal.SshShell
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An SSH server in a box: it asks what [script] says (a server key to confirm, a password), and
 * accepts [password] or any key. Runs on the caller's (background) thread, like the real one.
 */
class FakeSshConnector(
    var password: String = "secret",
    /** The server's key; null skips the check entirely. */
    var hostKey: HostKeyCheck? = null,
    /** Fails every connection with this message, as an unreachable server would. */
    var failure: String? = null
) : SshConnector {
    val requests = CopyOnWriteArrayList<SshConnectRequest>()
    val shells = CopyOnWriteArrayList<FakeShell>()

    override fun connect(
        request: SshConnectRequest,
        prompts: SshPrompts,
        hostKeys: HostKeyStore,
        onOutput: (ByteArray, Int) -> Unit,
        onClosed: (String?) -> Unit
    ): SshShell {
        requests += request
        failure?.let { throw SshConnectException(it) }
        hostKey?.let { key ->
            val known = hostKeys.knownKey(request.hostname, request.port)
            if (known?.second != key.keyBase64) {
                val check = key.copy(hostname = request.hostname, port = request.port, changed = known != null)
                if (!prompts.confirmHostKey(check)) throw SshConnectException("Not connected: the server's identity was not accepted.")
                hostKeys.remember(check)
            }
        }
        when (val credentials = request.credentials) {
            is SshCredentials.Key -> Unit
            is SshCredentials.Password -> {
                var candidate = credentials.saved
                var attempt = 0
                while (candidate != password) {
                    candidate = prompts.askPassword(retry = attempt > 0 || credentials.saved != null, prompt = null)
                        ?: throw SshConnectException("Not connected: no password was given.")
                    attempt++
                }
                prompts.passwordAccepted(candidate!!)
            }
        }
        return FakeShell(request, onOutput, onClosed).also { shells += it }
    }

    class FakeShell(
        val request: SshConnectRequest,
        private val onOutput: (ByteArray, Int) -> Unit,
        private val onClosed: (String?) -> Unit
    ) : SshShell {
        val written = ConcurrentLinkedQueue<String>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        @Volatile var closed = false

        override fun write(bytes: ByteArray) {
            written += String(bytes, Charsets.UTF_8)
        }

        override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
            sizes += columns to rows
        }

        override fun close() {
            if (closed) return
            closed = true
            onClosed(null)
        }

        /** The server prints [text]. */
        fun print(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            onOutput(bytes, bytes.size)
        }

        /** The connection drops, or the shell exits ([reason] null). */
        fun end(reason: String?) {
            if (closed) return
            closed = true
            onClosed(reason)
        }

        fun typed(): String = written.joinToString("")
    }
}

/**
 * The main thread, for tests: what code posts to it waits here until [drain] runs it on the
 * test's thread, so the order of events is the real one and nothing races.
 */
class TestMainQueue {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    fun post(runnable: Runnable) {
        queue += runnable
    }

    fun drain() {
        while (true) (queue.poll() ?: return).run()
    }

    /** Drains until [condition] holds, waiting for background threads; fails after a few seconds. */
    fun drainUntil(description: String = "the condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            drain()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $description")
            Thread.sleep(5)
        }
    }
}
