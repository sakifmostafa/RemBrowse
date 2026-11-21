package com.rembrowser.rembrowse.terminal

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The terminal's SSH client against a real SSH server (Apache MINA SSHD, started inside the test
 * on a free local port): host key checks, password and key sign-in, the pseudo-terminal's size
 * and resizing, input and output, and the shell ending.
 */
class SshEndToEndTest {

    private lateinit var server: SshServer
    private val shells = Collections.synchronizedList(mutableListOf<EchoShell>())
    private var acceptedKey: PublicKey? = null

    // Robolectric tests running earlier in this JVM install Conscrypt as its first security
    // provider, for everyone, and the test server (MINA) cannot sign its host key with it. It is
    // set aside while these tests run and put back afterwards. (The client itself was checked
    // with Conscrypt first, as on Android, against OpenSSH 9.6: see TrileadSshConnector.CIPHERS.)
    private val setAside = mutableListOf<Pair<Int, Provider>>()

    @Before
    fun setConscryptAside() {
        Security.getProviders().forEachIndexed { index, provider ->
            if (provider.name.contains("Conscrypt", ignoreCase = true)) setAside += (index + 1) to provider
        }
        setAside.forEach { (_, provider) -> Security.removeProvider(provider.name) }
    }

    @After
    fun restoreProviders() {
        setAside.forEach { (position, provider) -> Security.insertProviderAt(provider, position) }
    }

    @Test
    fun onlyAesIsOffered_neverChaCha20() {
        // With Android's provider (Conscrypt) the library's ChaCha20-Poly1305 fails every MAC
        // check, and OpenSSH would pick it first if it were offered
        assertTrue(TrileadSshConnector.CIPHERS.none { it.contains("chacha", ignoreCase = true) })
        assertTrue(TrileadSshConnector.CIPHERS.all { it.startsWith("aes") && (it.contains("gcm") || it.endsWith("-ctr")) })
        assertEquals("aes256-gcm@openssh.com", TrileadSshConnector.CIPHERS.first())
    }

    @Before
    fun startServer() {
        val hostKeyFile = Files.createTempFile("hostkey", ".ser").also { Files.delete(it) }
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                user == USER && acceptedKey != null && KeyUtils.compareKeys(acceptedKey, key)
            }
            shellFactory = ShellFactory { EchoShell().also { shells += it } }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(true)
    }

    // --- Helpers ---

    private class MemoryHostKeys : HostKeyStore {
        val known = mutableMapOf<String, Pair<String, String>>()
        override fun knownKey(hostname: String, port: Int) = known["$hostname:$port"]
        override fun remember(check: HostKeyCheck) {
            known["${check.hostname}:${check.port}"] = check.keyType to check.keyBase64
        }
    }

    private class ScriptedPrompts(
        private val acceptKey: Boolean = true,
        private val passwords: MutableList<String> = mutableListOf()
    ) : SshPrompts {
        val hostKeyChecks = mutableListOf<HostKeyCheck>()
        val passwordRequests = mutableListOf<Boolean>()
        var accepted: String? = null
        override fun confirmHostKey(check: HostKeyCheck): Boolean {
            hostKeyChecks += check
            return acceptKey
        }
        override fun askPassword(retry: Boolean, prompt: String?): String? {
            passwordRequests += retry
            return passwords.removeFirstOrNull()
        }
        override fun passwordAccepted(password: String) {
            accepted = password
        }
    }

    private class Output {
        private val bytes = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        @Volatile var closeReason: String? = "not closed"
        fun add(data: ByteArray, count: Int) = synchronized(bytes) { bytes.write(data, 0, count) }
        fun text() = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
        fun waitFor(text: String) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!text().contains(text)) {
                if (System.currentTimeMillis() > deadline) fail("Never saw \"$text\" in: ${text()}")
                Thread.sleep(20)
            }
        }
    }

    private fun request(credentials: SshCredentials, columns: Int = 100, rows: Int = 30) = SshConnectRequest(
        hostname = "127.0.0.1",
        port = server.port,
        username = USER,
        credentials = credentials,
        columns = columns,
        rows = rows,
        widthPx = columns * 9,
        heightPx = rows * 18
    )

    private fun connect(
        credentials: SshCredentials,
        prompts: SshPrompts = ScriptedPrompts(),
        hostKeys: HostKeyStore = MemoryHostKeys(),
        output: Output = Output()
    ): SshShell = TrileadSshConnector().connect(
        request(credentials),
        prompts,
        hostKeys,
        onOutput = output::add,
        onClosed = { reason ->
            output.closeReason = reason
            output.closed.countDown()
        }
    )

    private fun lastShell(): EchoShell {
        val deadline = System.currentTimeMillis() + 5_000
        while (shells.isEmpty() || !shells.last().started.await(10, TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() > deadline) fail("The shell never started")
        }
        return shells.last()
    }

    // --- Tests ---

    @Test
    fun passwordSignIn_opensAShellWithThePtySize_andEchoesInput() {
        val prompts = ScriptedPrompts()
        val output = Output()
        val shell = connect(SshCredentials.Password(saved = PASSWORD), prompts, output = output)

        val server = lastShell()
        assertEquals("xterm-256color", server.term)
        assertEquals("100", server.columns)
        assertEquals("30", server.lines)
        assertTrue("A saved password asks for nothing", prompts.passwordRequests.isEmpty())
        assertEquals(PASSWORD, prompts.accepted)

        shell.write("hello\r".toByteArray())
        output.waitFor("you typed: hello")

        shell.resize(120, 40, 1080, 720)
        val deadline = System.currentTimeMillis() + 5_000
        while (server.columns != "120") {
            if (System.currentTimeMillis() > deadline) fail("The server never heard of the new size (${server.columns})")
            Thread.sleep(20)
        }
        assertEquals("40", server.lines)

        shell.write("exit\r".toByteArray())
        assertTrue(output.closed.await(10, TimeUnit.SECONDS))
        assertNull("The shell exited", output.closeReason)
    }

    @Test
    fun aNewServerIsConfirmedOnce_andAChangedKeyIsFlagged() {
        val hostKeys = MemoryHostKeys()
        val first = ScriptedPrompts()
        connect(SshCredentials.Password(PASSWORD), first, hostKeys).close()
        val check = first.hostKeyChecks.single()
        assertFalse(check.changed)
        assertTrue(check.fingerprint, check.fingerprint.startsWith("SHA256:"))
        assertFalse("OpenSSH prints it without padding", check.fingerprint.endsWith("="))
        assertNotNull(hostKeys.knownKey("127.0.0.1", server.port))

        val second = ScriptedPrompts()
        connect(SshCredentials.Password(PASSWORD), second, hostKeys).close()
        assertTrue("A known key asks nothing", second.hostKeyChecks.isEmpty())

        // The same address now answers with another key
        hostKeys.known["127.0.0.1:${server.port}"] = "ssh-ed25519" to "AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
        val third = ScriptedPrompts(acceptKey = false)
        try {
            connect(SshCredentials.Password(PASSWORD), third, hostKeys)
            fail("A refused key must not connect")
        } catch (e: SshConnectException) {
            assertTrue(e.message!!, e.message!!.contains("identity"))
        }
        val changed = third.hostKeyChecks.single()
        assertTrue(changed.changed)
        assertNotNull(changed.previousFingerprint)
    }

    @Test
    fun aWrongPasswordIsAskedAgain_andGivingUpFails() {
        val prompts = ScriptedPrompts(passwords = mutableListOf("nope", PASSWORD))
        connect(SshCredentials.Password(saved = "stale"), prompts).close()
        assertEquals("Asked twice, both as retries after the stale saved one", listOf(true, true), prompts.passwordRequests)
        assertEquals(PASSWORD, prompts.accepted)

        val givesUp = ScriptedPrompts(passwords = mutableListOf())
        try {
            connect(SshCredentials.Password(saved = null), givesUp)
            fail("No password, no shell")
        } catch (e: SshConnectException) {
            assertTrue(e.message!!.contains("no password"))
        }
    }

    @Test
    fun aGeneratedKeySignsIn_onceTheServerHasItsPublicHalf() {
        val key = SshKeys.generateEd25519("rembrowse-test")
        assertTrue(key.publicKey, key.publicKey.startsWith("ssh-ed25519 AAAA"))
        assertTrue(key.publicKey.endsWith(" rembrowse-test"))
        assertTrue(key.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))

        try {
            connect(SshCredentials.Key(key.privateKeyPem))
            fail("The server doesn't know the key yet")
        } catch (e: SshConnectException) {
            assertTrue(e.message!!, e.message!!.contains("authorized_keys"))
        }

        acceptedKey = org.apache.sshd.common.config.keys.AuthorizedKeyEntry.parseAuthorizedKeyEntry(key.publicKey)
            .resolvePublicKey(null, org.apache.sshd.common.config.keys.PublicKeyEntryResolver.FAILING)
        val output = Output()
        val shell = connect(SshCredentials.Key(key.privateKeyPem), output = output)
        shell.write("hi\r".toByteArray())
        output.waitFor("you typed: hi")
        shell.close()
    }

    @Test
    fun anImportedKeyWithAPassphrase_isKeptWithoutIt() {
        val original = SshKeys.generateEd25519("laptop")
        val withPassphrase = com.trilead.ssh2.crypto.OpenSSHKeyEncoder.exportOpenSSHEd25519(
            com.trilead.ssh2.crypto.PEMDecoder.decode(original.privateKeyPem.toCharArray(), null).private
                as com.trilead.ssh2.crypto.keys.Ed25519PrivateKey,
            com.trilead.ssh2.crypto.PEMDecoder.decode(original.privateKeyPem.toCharArray(), null).public
                as com.trilead.ssh2.crypto.keys.Ed25519PublicKey,
            "laptop",
            "hunter2"
        )
        try {
            SshKeys.import(withPassphrase, passphrase = null, comment = "laptop")
            fail("A passphrase is needed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("passphrase"))
        }
        val imported = SshKeys.import(withPassphrase, passphrase = "hunter2", comment = "laptop")
        assertEquals(original.publicKey, imported.publicKey)
        assertEquals(original.fingerprint, imported.fingerprint)
        // What is kept opens without the passphrase
        SshKeys.import(imported.privateKeyPem, passphrase = null, comment = "laptop")

        try {
            SshKeys.import(original.publicKey, null, "x")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("private key"))
        }
    }

    @Test
    fun aClosedPortIsExplained() {
        val port = server.port
        server.stop(true)
        try {
            TrileadSshConnector(connectTimeoutMillis = 3_000).connect(
                request(SshCredentials.Password(PASSWORD)).copy(port = port),
                ScriptedPrompts(),
                MemoryHostKeys(),
                onOutput = { _, _ -> },
                onClosed = {}
            )
            fail()
        } catch (e: SshConnectException) {
            assertTrue(e.message!!, e.message!!.contains("refused the connection"))
        }
    }

    @Test
    fun closingTheShell_endsTheServersSession_andReportsClosedOnceWithNoReason() {
        val closedCalls = java.util.concurrent.atomic.AtomicInteger()
        val reasons = Collections.synchronizedList(mutableListOf<String?>())
        val shell = TrileadSshConnector().connect(
            request(SshCredentials.Password(PASSWORD)),
            ScriptedPrompts(),
            MemoryHostKeys(),
            onOutput = { _, _ -> },
            onClosed = { reason ->
                closedCalls.incrementAndGet()
                reasons += reason
            }
        )
        lastShell()
        assertEquals("One session is open on the server", 1, server.activeSessions.size)

        // Closing from a thread that may not use the network, as the main thread on Android
        shell.close()
        shell.close()

        val deadline = System.currentTimeMillis() + 10_000
        while (server.activeSessions.isNotEmpty()) {
            if (System.currentTimeMillis() > deadline) fail("The server's session is still open")
            Thread.sleep(50)
        }
        assertEquals(1, closedCalls.get())
        assertEquals(listOf<String?>(null), reasons.toList())
        Thread.sleep(300)
        assertEquals("Still once", 1, closedCalls.get())
    }

    @Test
    fun aSlowHostKeyQuestion_doesNotCountAgainstTheKeyExchangeTimeLimit() {
        val output = Output()
        val slowPrompts = object : SshPrompts {
            override fun confirmHostKey(check: HostKeyCheck): Boolean {
                Thread.sleep(2_500)
                return true
            }
            override fun askPassword(retry: Boolean, prompt: String?): String? = null
            override fun passwordAccepted(password: String) {}
        }
        val shell = TrileadSshConnector(keyExchangeTimeoutMillis = 1_000).connect(
            request(SshCredentials.Password(PASSWORD)),
            slowPrompts,
            MemoryHostKeys(),
            onOutput = output::add,
            onClosed = { reason ->
                output.closeReason = reason
                output.closed.countDown()
            }
        )
        shell.write("hello\r".toByteArray())
        output.waitFor("you typed: hello")
        shell.close()
    }

    @Test
    fun aServerThatNeverAnswers_failsTheKeyExchangeInTime() {
        java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted = mutableListOf<java.net.Socket>()
            val acceptor = Thread {
                try {
                    while (true) {
                        val socket = silent.accept()
                        synchronized(accepted) { accepted += socket }
                    }
                } catch (_: java.io.IOException) {
                }
            }.apply { isDaemon = true; start() }
            // Safety net so a client that never gives up cannot hang the whole test run: after
            // 12 s the silent server hangs up, which ends the client's read
            Thread {
                Thread.sleep(12_000)
                synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
            }.apply { isDaemon = true; start() }
            val started = System.currentTimeMillis()
            try {
                TrileadSshConnector(connectTimeoutMillis = 8_000, keyExchangeTimeoutMillis = 1_000).connect(
                    request(SshCredentials.Password(PASSWORD)).copy(port = silent.localPort),
                    ScriptedPrompts(),
                    MemoryHostKeys(),
                    onOutput = { _, _ -> },
                    onClosed = {}
                )
                fail("A silent server must not connect")
            } catch (e: SshConnectException) {
                assertTrue(e.message!!, e.message!!.contains("didn't answer in time"))
            } finally {
                synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
                acceptor.interrupt()
            }
            val took = System.currentTimeMillis() - started
            assertTrue("Took $took ms", took < 7_000)
        }
    }

    /** A shell that echoes each line back and remembers its terminal's size. */
    private class EchoShell : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private var exit: ExitCallback? = null
        val started = CountDownLatch(1)
        @Volatile var term: String? = null
        @Volatile var columns: String? = null
        @Volatile var lines: String? = null

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) {}

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            term = env.env[Environment.ENV_TERM]
            columns = env.env[Environment.ENV_COLUMNS]
            lines = env.env[Environment.ENV_LINES]
            env.addSignalListener({ _, _ ->
                columns = env.env[Environment.ENV_COLUMNS]
                lines = env.env[Environment.ENV_LINES]
            })
            Thread {
                started.countDown()
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\r'.code || c == '\n'.code) {
                        if (line.toString() == "exit") break
                        output.write("\r\nyou typed: $line\r\n".toByteArray())
                        output.flush()
                        line.clear()
                    } else {
                        line.append(c.toChar())
                    }
                }
                exit?.onExit(0)
            }.start()
        }

        override fun destroy(channel: ChannelSession) {}
    }

    private companion object {
        const val USER = "sakif"
        const val PASSWORD = "secret"
    }
}
