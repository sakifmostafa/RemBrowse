package com.rembrowser.rembrowse.terminal

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a terminal tab's connection is. [label] names the server (`user@host`, or its saved name). */
sealed interface TerminalStatus {
    /** Nothing chosen yet: the tab offers the saved hosts. */
    data object Idle : TerminalStatus

    data class Connecting(val label: String) : TerminalStatus

    /** The server's key is new or has changed: the user decides whether to go on. */
    data class ConfirmingHostKey(val label: String, val check: HostKeyCheck) : TerminalStatus

    /** The server wants a password and none is saved (or the saved one was wrong). */
    data class NeedsPassword(val label: String, val retry: Boolean, val canRemember: Boolean) : TerminalStatus

    data class Connected(val label: String) : TerminalStatus

    /**
     * The shell ended, or never started. [reason] is null when it simply exited (`exit`, or the
     * user disconnected); [failedToConnect] when there never was a shell.
     */
    data class Disconnected(val label: String, val reason: String?, val failedToConnect: Boolean = false) : TerminalStatus
}

/** What to connect to, with how to sign in, as the terminal tab resolved it. */
data class TerminalConnectSpec(
    val label: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val credentials: SshCredentials,
    /** Saves a password that worked, when the user ticks "Remember"; null when there is nowhere to save it. */
    val savePassword: ((String) -> Unit)? = null
)

/**
 * One terminal tab's screen and connection. The screen ([emulator]) lives as long as the tab, so
 * a terminal survives being hidden behind another tab or the activity being re-created, and its
 * scrollback is still there after a reconnect.
 *
 * Main thread only, apart from what the connector does on its own threads.
 *
 * @param postToMain runs a block on the main thread.
 * @param runInBackground runs a block on a thread that may block (connecting waits for the user).
 */
class TerminalConnection(
    val tabId: String,
    private val connector: SshConnector,
    private val hostKeys: HostKeyStore,
    private val postToMain: (Runnable) -> Unit,
    private val runInBackground: (Runnable) -> Unit
) : TerminalOutput() {

    /** The cursor's shape, as Settings → Terminal chose it (a program can still change it). */
    private var cursorStyle = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // What the emulator asks of its client: the cursor's shape, and where to log
    private val client = object : TerminalSessionClient {
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = cursorStyle
        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.w(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.w(tag, e) }
    }

    // The emulator copies the colour scheme when it is made, so the scheme must be in place first
    val emulator: TerminalEmulator = run {
        TerminalTheme.ensureApplied()
        TerminalEmulator(this, DEFAULT_COLUMNS, DEFAULT_ROWS, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT, SCROLLBACK_ROWS, client)
    }

    /** Which theme change the screen's colours come from (see TerminalTheme.generation). */
    private var colorGeneration = TerminalTheme.generation

    /** Takes on the current theme's colours, if they changed since the screen last did. */
    fun applyTheme() {
        if (colorGeneration == TerminalTheme.generation) return
        colorGeneration = TerminalTheme.generation
        emulator.mColors.reset()
        onScreenChanged?.invoke()
    }

    /** Sets the cursor's shape: one of TerminalEmulator.TERMINAL_CURSOR_STYLE_*. */
    fun setCursorStyle(style: Int) {
        if (style == cursorStyle) return
        cursorStyle = style
        emulator.setCursorStyle()
        onScreenChanged?.invoke()
    }

    /** Whether a server has printed anything yet: until then the screen is left blank (no lone cursor). */
    var hasOutput = false
        private set

    private val _status = MutableStateFlow<TerminalStatus>(TerminalStatus.Idle)
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    /** The title the shell gave its window (`user@host: ~`), if any. */
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    /** Called on the main thread when the screen changed. Set by the view while it shows this terminal. */
    var onScreenChanged: (() -> Unit)? = null

    /** The last connection asked for, for Reconnect. */
    var lastSpec: TerminalConnectSpec? = null
        private set

    private var shell: SshShell? = null

    // Every connection attempt gets a number; whatever an older one reports later is ignored
    private var attempt = 0

    // The connecting thread waits on this while the user decides; null when nothing is asked
    private var pendingAnswer: ArrayBlockingQueue<Any>? = null
    private var rememberPassword = false

    private var cellWidth = DEFAULT_CELL_WIDTH
    private var cellHeight = DEFAULT_CELL_HEIGHT

    val isConnected: Boolean get() = _status.value is TerminalStatus.Connected

    fun connect(spec: TerminalConnectSpec) {
        disconnect(silently = true)
        lastSpec = spec
        val thisAttempt = ++attempt
        rememberPassword = false
        _status.value = TerminalStatus.Connecting(spec.label)
        val request = SshConnectRequest(
            hostname = spec.hostname,
            port = spec.port,
            username = spec.username,
            credentials = spec.credentials,
            columns = emulator.mColumns,
            rows = emulator.mRows,
            widthPx = emulator.mColumns * cellWidth,
            heightPx = emulator.mRows * cellHeight
        )
        val prompts = Prompts(thisAttempt, spec)
        runInBackground {
            // A shell can end before connect() has even returned (a login that only prints a
            // message, a server's forced command): then it must not be shown as connected
            val endedEarly = AtomicBoolean(false)
            try {
                val opened = connector.connect(
                    request = request,
                    prompts = prompts,
                    hostKeys = hostKeys,
                    onOutput = { bytes, count ->
                        val copy = bytes.copyOf(count)
                        postToMain {
                            if (thisAttempt == attempt) {
                                hasOutput = true
                                emulator.append(copy, count)
                                onScreenChanged?.invoke()
                            }
                        }
                    },
                    onClosed = { reason ->
                        endedEarly.set(true)
                        postToMain {
                            if (thisAttempt == attempt) {
                                shell = null
                                _status.value = TerminalStatus.Disconnected(spec.label, reason)
                            }
                        }
                    }
                )
                postToMain {
                    if (thisAttempt != attempt) {
                        opened.close()
                    } else if (endedEarly.get()) {
                        // Its end is (or will be) reported as Disconnected
                        opened.close()
                    } else {
                        shell = opened
                        _status.value = TerminalStatus.Connected(spec.label)
                        // The view may have changed size while the connection was being made
                        if (emulator.mColumns != request.columns || emulator.mRows != request.rows) {
                            opened.resize(emulator.mColumns, emulator.mRows, emulator.mColumns * cellWidth, emulator.mRows * cellHeight)
                        }
                    }
                }
            } catch (e: SshConnectException) {
                postToMain {
                    if (thisAttempt == attempt) {
                        _status.value = TerminalStatus.Disconnected(spec.label, e.message, failedToConnect = true)
                    }
                }
            } catch (e: RuntimeException) {
                postToMain {
                    if (thisAttempt == attempt) {
                        _status.value = TerminalStatus.Disconnected(spec.label, e.message ?: "The connection failed.", failedToConnect = true)
                    }
                }
            }
        }
    }

    /**
     * Shows why there is nothing to connect to (a deleted host, a missing key) the way a failed
     * connection is shown, without trying.
     */
    fun fail(label: String, reason: String) {
        disconnect(silently = true)
        lastSpec = null
        _status.value = TerminalStatus.Disconnected(label, reason, failedToConnect = true)
    }

    /** Connects again to the server last connected to. */
    fun reconnect() {
        lastSpec?.let(::connect)
    }

    /** The user accepted or refused the server's key. */
    fun answerHostKey(accept: Boolean) {
        if (_status.value !is TerminalStatus.ConfirmingHostKey) return
        pendingAnswer?.offer(accept)
    }

    /** The user typed a password ([remember] to save it with the host), or cancelled (null). */
    fun answerPassword(password: String?, remember: Boolean = false) {
        if (_status.value !is TerminalStatus.NeedsPassword) return
        rememberPassword = remember && password != null
        pendingAnswer?.offer(password ?: CANCELLED)
    }

    /** Ends the connection. The screen stays, with its scrollback. */
    fun disconnect(silently: Boolean = false) {
        attempt++
        pendingAnswer?.offer(CANCELLED)
        pendingAnswer = null
        shell?.close()
        shell = null
        val status = _status.value
        if (!silently && status !is TerminalStatus.Idle && status !is TerminalStatus.Disconnected) {
            _status.value = TerminalStatus.Disconnected(labelOf(status), reason = null)
        }
    }

    /** Typed text or keys, as the terminal encodes them. */
    fun sendInput(bytes: ByteArray) {
        shell?.write(bytes)
    }

    fun sendInput(text: String) {
        write(text)
    }

    /** The view's size changed: the screen reflows and the server is told. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (columns == emulator.mColumns && rows == emulator.mRows && cellWidthPx == cellWidth && cellHeightPx == cellHeight) return
        cellWidth = cellWidthPx
        cellHeight = cellHeightPx
        emulator.resize(columns, rows, cellWidthPx, cellHeightPx)
        shell?.resize(columns, rows, columns * cellWidthPx, rows * cellHeightPx)
        onScreenChanged?.invoke()
    }

    // --- TerminalOutput: what the emulator sends back (keys, answers to the server's queries) ---

    override fun write(data: ByteArray, offset: Int, count: Int) {
        shell?.write(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        _title.value = newTitle?.takeIf { it.isNotBlank() }
    }

    // A server may not read or replace the clipboard (OSC 52): a remote program could otherwise
    // take whatever the user last copied, or plant something to be pasted elsewhere.
    override fun onCopyTextToClipboard(text: String?) {}

    override fun onPasteTextFromClipboard() {}

    override fun onBell() {}

    override fun onColorsChanged() {
        onScreenChanged?.invoke()
    }

    private inner class Prompts(private val forAttempt: Int, private val spec: TerminalConnectSpec) : SshPrompts {

        override fun confirmHostKey(check: HostKeyCheck): Boolean =
            ask(TerminalStatus.ConfirmingHostKey(spec.label, check)) == true

        override fun askPassword(retry: Boolean, prompt: String?): String? =
            ask(TerminalStatus.NeedsPassword(spec.label, retry, canRemember = spec.savePassword != null)) as? String

        override fun passwordAccepted(password: String) {
            val save = spec.savePassword ?: return
            postToMain { if (forAttempt == attempt && rememberPassword) save(password) }
        }

        /** Shows [question] and waits for the answer; [CANCELLED] (or a closed tab) answers no. */
        private fun ask(question: TerminalStatus): Any? {
            val answer = ArrayBlockingQueue<Any>(1)
            postToMain {
                if (forAttempt == attempt) {
                    pendingAnswer = answer
                    _status.value = question
                } else {
                    answer.offer(CANCELLED)
                }
            }
            // Generous, but not forever: a forgotten question must not hold a thread for good
            val result = answer.poll(ANSWER_TIMEOUT_MINUTES, TimeUnit.MINUTES) ?: CANCELLED
            postToMain {
                if (forAttempt == attempt) {
                    pendingAnswer = null
                    if (result != CANCELLED) _status.value = TerminalStatus.Connecting(spec.label)
                }
            }
            return result.takeUnless { it == CANCELLED }
        }
    }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val SCROLLBACK_ROWS = 5_000
        private const val DEFAULT_CELL_WIDTH = 9
        private const val DEFAULT_CELL_HEIGHT = 18
        private const val ANSWER_TIMEOUT_MINUTES = 10L
        private val CANCELLED = Any()

        fun labelOf(status: TerminalStatus): String = when (status) {
            TerminalStatus.Idle -> ""
            is TerminalStatus.Connecting -> status.label
            is TerminalStatus.ConfirmingHostKey -> status.label
            is TerminalStatus.NeedsPassword -> status.label
            is TerminalStatus.Connected -> status.label
            is TerminalStatus.Disconnected -> status.label
        }
    }
}
